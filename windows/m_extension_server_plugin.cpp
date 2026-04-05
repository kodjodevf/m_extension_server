#include "m_extension_server_plugin.h"

// Must be included before many other Windows headers.
#include <windows.h>

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>
#include <flutter/standard_method_codec.h>

#include <memory>
#include <sstream>
#include <string>

namespace m_extension_server {
static std::string GetStringArg(
    const flutter::EncodableMap& args,
    const std::string& key) {
  auto it = args.find(flutter::EncodableValue(key));
  if (it == args.end()) return {};
  const auto* val = std::get_if<std::string>(&it->second);
  return val ? *val : std::string{};
}

static int GetIntArg(
    const flutter::EncodableMap& args,
    const std::string& key) {
  auto it = args.find(flutter::EncodableValue(key));
  if (it == args.end()) return -1;
  const auto* val = std::get_if<int>(&it->second);
  return val ? *val : -1;
}

void MExtensionServerPlugin::RegisterWithRegistrar(
    flutter::PluginRegistrarWindows* registrar) {
  auto channel =
      std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
          registrar->messenger(), "m_extension_server",
          &flutter::StandardMethodCodec::GetInstance());

  auto plugin = std::make_unique<MExtensionServerPlugin>();

  channel->SetMethodCallHandler(
      [plugin_pointer = plugin.get()](const auto& call, auto result) {
        plugin_pointer->HandleMethodCall(call, std::move(result));
      });

  registrar->AddPlugin(std::move(plugin));
}

MExtensionServerPlugin::MExtensionServerPlugin() {}

MExtensionServerPlugin::~MExtensionServerPlugin() {
  StopRunningProcess();
}

void MExtensionServerPlugin::HandleMethodCall(
    const flutter::MethodCall<flutter::EncodableValue>& method_call,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {

  const auto& method = method_call.method_name();

  if (method == "startServer") {
    const auto* args =
        std::get_if<flutter::EncodableMap>(method_call.arguments());
    if (!args) {
      result->Error("INVALID_ARGS", "Expected argument map for startServer");
      return;
    }

    const int port = GetIntArg(*args, "port");
    if (port <= 0) {
      result->Error("INVALID_ARGS", "Missing or invalid 'port' argument");
      return;
    }

    const std::string jvm_path      = GetStringArg(*args, "jvmPath");
    const std::string server_jar    = GetStringArg(*args, "serverJarPath");

    if (server_jar.empty()) {
      result->Error("INVALID_ARGS",
                    "Missing 'serverJarPath' argument – required on Windows");
      return;
    }

    StartServer(port, jvm_path, server_jar, std::move(result));

  } else if (method == "stopServer") {
    StopServer(std::move(result));

  } else {
    result->NotImplemented();
  }
}

void MExtensionServerPlugin::StopRunningProcess() {
  if (java_process_ == INVALID_HANDLE_VALUE) {
    return;
  }

  TerminateProcess(java_process_, 0);
  WaitForSingleObject(java_process_, 3000);
  CloseHandle(java_process_);
  java_process_ = INVALID_HANDLE_VALUE;
}

void MExtensionServerPlugin::StartServer(
    int port,
    const std::string& jvm_path,
    const std::string& server_jar_path,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  StopRunningProcess();

  const std::string java_exe = jvm_path.empty() ? "java" : jvm_path;

  std::ostringstream cmd;
  cmd << "\"" << java_exe << "\""
      << " -jar \"" << server_jar_path << "\""
      << " " << port;

  std::string cmd_str = cmd.str();
  std::vector<char> cmd_buf(cmd_str.begin(), cmd_str.end());
  cmd_buf.push_back('\0');

  STARTUPINFOA si = {};
  si.cb = sizeof(si);
  si.dwFlags = STARTF_USESHOWWINDOW;
  si.wShowWindow = SW_HIDE;

  PROCESS_INFORMATION pi = {};

  const BOOL ok = CreateProcessA(
      /*lpApplicationName=*/nullptr,
      /*lpCommandLine=*/cmd_buf.data(),
      /*lpProcessAttributes=*/nullptr,
      /*lpThreadAttributes=*/nullptr,
      /*bInheritHandles=*/FALSE,
      /*dwCreationFlags=*/CREATE_NO_WINDOW,
      /*lpEnvironment=*/nullptr,
      /*lpCurrentDirectory=*/nullptr,
      &si,
      &pi);

  if (!ok) {
    const DWORD err = GetLastError();
    std::ostringstream msg;
    msg << "CreateProcess failed (error " << err << "): " << cmd_str;
    result->Error("START_ERROR", msg.str());
    return;
  }

  CloseHandle(pi.hThread);
  java_process_ = pi.hProcess;

  std::ostringstream ok_msg;
  ok_msg << "Server started on port " << port;
  result->Success(flutter::EncodableValue(ok_msg.str()));
}

void MExtensionServerPlugin::StopServer(
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  if (java_process_ == INVALID_HANDLE_VALUE) {
    result->Success(flutter::EncodableValue("Server was not running"));
    return;
  }

  TerminateProcess(java_process_, 0);
  WaitForSingleObject(java_process_, 5000);
  CloseHandle(java_process_);
  java_process_ = INVALID_HANDLE_VALUE;

  result->Success(flutter::EncodableValue("Server stopped"));
}

}  // namespace m_extension_server
