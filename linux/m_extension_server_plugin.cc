#include "include/m_extension_server/m_extension_server_plugin.h"

#include <flutter_linux/flutter_linux.h>
#include <gtk/gtk.h>

// POSIX process management
#include <signal.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <fcntl.h>

#include <cstring>
#include <string>
#include <sstream>

#include "m_extension_server_plugin_private.h"

#define m_extension_server_PLUGIN(obj) \
  (G_TYPE_CHECK_INSTANCE_CAST((obj), m_extension_server_plugin_get_type(), \
                              MExtensionServerPlugin))

struct _MExtensionServerPlugin {
  GObject parent_instance;
  pid_t java_pid;
};

G_DEFINE_TYPE(MExtensionServerPlugin, m_extension_server_plugin,
              g_object_get_type())

static FlMethodResponse* start_server(MExtensionServerPlugin* self,
                                      FlValue* args);
static FlMethodResponse* stop_server(MExtensionServerPlugin* self);
static void kill_java_process(MExtensionServerPlugin* self);

static const gchar* get_string_arg(FlValue* args, const gchar* key) {
  if (!args || fl_value_get_type(args) != FL_VALUE_TYPE_MAP) return nullptr;
  FlValue* v = fl_value_lookup_string(args, key);
  if (!v || fl_value_get_type(v) != FL_VALUE_TYPE_STRING) return nullptr;
  return fl_value_get_string(v);
}

static int get_int_arg(FlValue* args, const gchar* key) {
  if (!args || fl_value_get_type(args) != FL_VALUE_TYPE_MAP) return -1;
  FlValue* v = fl_value_lookup_string(args, key);
  if (!v || fl_value_get_type(v) != FL_VALUE_TYPE_INT) return -1;
  return static_cast<int>(fl_value_get_int(v));
}

static void m_extension_server_plugin_handle_method_call(
    MExtensionServerPlugin* self,
    FlMethodCall* method_call) {

  g_autoptr(FlMethodResponse) response = nullptr;
  const gchar* method = fl_method_call_get_name(method_call);
  FlValue* args = fl_method_call_get_args(method_call);

  if (strcmp(method, "startServer") == 0) {
    response = start_server(self, args);
  } else if (strcmp(method, "stopServer") == 0) {
    response = stop_server(self);
  } else {
    response = FL_METHOD_RESPONSE(fl_method_not_implemented_response_new());
  }

  fl_method_call_respond(method_call, response, nullptr);
}

static FlMethodResponse* start_server(MExtensionServerPlugin* self,
                                      FlValue* args) {
  const gchar* jvm_path_arg    = get_string_arg(args, "jvmPath");
  const gchar* jar_path_arg    = get_string_arg(args, "serverJarPath");
  const int    port            = get_int_arg(args, "port");

  if (port <= 0) {
    return FL_METHOD_RESPONSE(fl_method_error_response_new(
        "INVALID_ARGS", "Missing or invalid 'port' argument", nullptr));
  }
  if (!jar_path_arg || strlen(jar_path_arg) == 0) {
    return FL_METHOD_RESPONSE(fl_method_error_response_new(
        "INVALID_ARGS",
        "Missing 'serverJarPath' argument – required on Linux", nullptr));
  }

  kill_java_process(self);

  const std::string java_exe =
      (jvm_path_arg && strlen(jvm_path_arg) > 0)
          ? std::string(jvm_path_arg)
          : std::string("java");

  const std::string jar_path(jar_path_arg);
  const std::string port_str = std::to_string(port);

  const char* argv[] = {
      java_exe.c_str(),
      "-jar",
      jar_path.c_str(),
      port_str.c_str(),
      nullptr
  };

  pid_t pid = fork();
  if (pid < 0) {
    g_autofree gchar* msg = g_strdup_printf(
        "fork() failed: %s", strerror(errno));
    return FL_METHOD_RESPONSE(
        fl_method_error_response_new("START_ERROR", msg, nullptr));
  }

  if (pid == 0) {
    const int devnull = open("/dev/null", O_WRONLY);
    if (devnull >= 0) {
      dup2(devnull, STDOUT_FILENO);
      dup2(devnull, STDERR_FILENO);
      close(devnull);
    }
    execvp(java_exe.c_str(), const_cast<char* const*>(argv));
    _exit(127);
  }

  self->java_pid = pid;

  g_autofree gchar* ok_msg =
      g_strdup_printf("Server started on port %d", port);
  g_autoptr(FlValue) result = fl_value_new_string(ok_msg);
  return FL_METHOD_RESPONSE(fl_method_success_response_new(result));
}

static void kill_java_process(MExtensionServerPlugin* self) {
  if (self->java_pid <= 0) return;

  kill(self->java_pid, SIGTERM);

  int status = 0;
  waitpid(self->java_pid, &status, WNOHANG);
  self->java_pid = 0;
}

static FlMethodResponse* stop_server(MExtensionServerPlugin* self) {
  if (self->java_pid <= 0) {
    g_autoptr(FlValue) result =
        fl_value_new_string("Server was not running");
    return FL_METHOD_RESPONSE(fl_method_success_response_new(result));
  }

  kill(self->java_pid, SIGTERM);

  for (int i = 0; i < 50; ++i) {
    int status = 0;
    pid_t ret = waitpid(self->java_pid, &status, WNOHANG);
    if (ret == self->java_pid) break;
    g_usleep(100000);  // 100 ms
  }

  if (waitpid(self->java_pid, nullptr, WNOHANG) == 0) {
    kill(self->java_pid, SIGKILL);
    waitpid(self->java_pid, nullptr, 0);
  }

  self->java_pid = 0;

  g_autoptr(FlValue) result = fl_value_new_string("Server stopped");
  return FL_METHOD_RESPONSE(fl_method_success_response_new(result));
}

static void m_extension_server_plugin_dispose(GObject* object) {
  MExtensionServerPlugin* self = m_extension_server_PLUGIN(object);
  kill_java_process(self);
  G_OBJECT_CLASS(m_extension_server_plugin_parent_class)->dispose(object);
}

static void m_extension_server_plugin_class_init(
    MExtensionServerPluginClass* klass) {
  G_OBJECT_CLASS(klass)->dispose = m_extension_server_plugin_dispose;
}

static void m_extension_server_plugin_init(MExtensionServerPlugin* self) {
  self->java_pid = 0;
}

static void method_call_cb(FlMethodChannel* channel,
                           FlMethodCall* method_call,
                           gpointer user_data) {
  MExtensionServerPlugin* plugin = m_extension_server_PLUGIN(user_data);
  m_extension_server_plugin_handle_method_call(plugin, method_call);
}

void m_extension_server_plugin_register_with_registrar(
    FlPluginRegistrar* registrar) {
  MExtensionServerPlugin* plugin = m_extension_server_PLUGIN(
      g_object_new(m_extension_server_plugin_get_type(), nullptr));

  g_autoptr(FlStandardMethodCodec) codec = fl_standard_method_codec_new();
  g_autoptr(FlMethodChannel) channel =
      fl_method_channel_new(fl_plugin_registrar_get_messenger(registrar),
                            "m_extension_server",
                            FL_METHOD_CODEC(codec));
  fl_method_channel_set_method_call_handler(channel, method_call_cb,
                                            g_object_ref(plugin),
                                            g_object_unref);

  g_object_unref(plugin);
}
