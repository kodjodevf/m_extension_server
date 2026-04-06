#include "m_extension_server_plugin.h"

// Must be included before many other Windows headers.
#include <windows.h>

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>
#include <flutter/standard_method_codec.h>

#include <fstream>
#include <memory>
#include <optional>
#include <algorithm>
#include <cctype>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

namespace m_extension_server {

namespace {

HANDLE g_java_process = nullptr;
DWORD g_java_process_id = 0;
uint64_t g_java_process_creation_time = 0;
std::string g_java_process_image_path;
std::string g_server_jar_path;
HANDLE g_java_job = nullptr;

struct PersistedProcessInfo {
  DWORD process_id;
  uint64_t creation_time;
  std::string image_path;
  std::string server_jar_path;
};

std::string ToLower(std::string value) {
  std::transform(value.begin(), value.end(), value.begin(),
                 [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return value;
}

std::string GetStateDirectoryPath() {
  char local_app_data[MAX_PATH] = {};
  const DWORD env_length =
      GetEnvironmentVariableA("LOCALAPPDATA", local_app_data, MAX_PATH);
  if (env_length > 0 && env_length < MAX_PATH) {
    return std::string(local_app_data) + "\\m_extension_server";
  }

  char temp_path[MAX_PATH] = {};
  const DWORD temp_length = GetTempPathA(MAX_PATH, temp_path);
  if (temp_length == 0 || temp_length > MAX_PATH) {
    return "m_extension_server_state";
  }

  return std::string(temp_path) + "m_extension_server";
}

std::string GetTrackedProcessStatePath() {
  return GetStateDirectoryPath() + "\\server.pid";
}

void EnsureStateDirectoryExists() {
  const std::string state_dir = GetStateDirectoryPath();
  if (CreateDirectoryA(state_dir.c_str(), nullptr) != 0) {
    return;
  }
}

bool EnsureJavaJobObject(std::string* error_message = nullptr) {
  if (g_java_job != nullptr) {
    return true;
  }

  HANDLE job = CreateJobObjectA(nullptr, nullptr);
  if (job == nullptr) {
    const DWORD error = GetLastError();
    std::ostringstream message;
    message << "CreateJobObjectA failed (error " << error << ")";
    if (error_message != nullptr) {
      *error_message = message.str();
    }
    return false;
  }

  JOBOBJECT_EXTENDED_LIMIT_INFORMATION info = {};
  info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
  if (SetInformationJobObject(job, JobObjectExtendedLimitInformation, &info,
                              sizeof(info)) == 0) {
    const DWORD error = GetLastError();
    std::ostringstream message;
    message << "SetInformationJobObject failed (error " << error << ")";
    if (error_message != nullptr) {
      *error_message = message.str();
    }
    CloseHandle(job);
    return false;
  }

  g_java_job = job;
  return true;
}

bool AssignProcessToJavaJob(HANDLE process, std::string* error_message = nullptr) {
  if (process == nullptr) {
    if (error_message != nullptr) {
      *error_message = "Cannot assign a null process handle to the Java job object";
    }
    return false;
  }

  if (!EnsureJavaJobObject(error_message)) {
    return false;
  }

  if (AssignProcessToJobObject(g_java_job, process) != 0) {
    return true;
  }

  const DWORD error = GetLastError();
  std::ostringstream message;
  message << "AssignProcessToJobObject failed (error " << error << ")";
  if (error_message != nullptr) {
    *error_message = message.str();
  }
  return false;
}

uint64_t ToUint64(const FILETIME& file_time) {
  ULARGE_INTEGER value = {};
  value.LowPart = file_time.dwLowDateTime;
  value.HighPart = file_time.dwHighDateTime;
  return value.QuadPart;
}

std::optional<uint64_t> GetProcessCreationTime(HANDLE process) {
  FILETIME creation_time = {};
  FILETIME exit_time = {};
  FILETIME kernel_time = {};
  FILETIME user_time = {};

  if (GetProcessTimes(process, &creation_time, &exit_time, &kernel_time,
                      &user_time) == 0) {
    return std::nullopt;
  }

  return ToUint64(creation_time);
}

bool IsTrackedProcessRunning(HANDLE process) {
  if (process == nullptr) {
    return false;
  }

  DWORD exit_code = 0;
  return GetExitCodeProcess(process, &exit_code) != 0 &&
         exit_code == STILL_ACTIVE;
}

std::string GetProcessImagePath(HANDLE process) {
  std::vector<char> buffer(MAX_PATH, '\0');
  DWORD size = static_cast<DWORD>(buffer.size());
  if (QueryFullProcessImageNameA(process, 0, buffer.data(), &size) == 0) {
    return {};
  }

  return std::string(buffer.data(), size);
}

bool DoesTrackedProcessMatch(HANDLE process) {
  if (!IsTrackedProcessRunning(process)) {
    return false;
  }

  if (g_java_process_creation_time != 0) {
    const auto actual_creation_time = GetProcessCreationTime(process);
    if (!actual_creation_time.has_value() ||
        actual_creation_time.value() != g_java_process_creation_time) {
      return false;
    }
  }

  if (!g_java_process_image_path.empty()) {
    const std::string actual_image_path = GetProcessImagePath(process);
    if (actual_image_path.empty() ||
        ToLower(actual_image_path) != ToLower(g_java_process_image_path)) {
      return false;
    }
  }

  return true;
}

bool IsSpecificProcessRunning(DWORD process_id, uint64_t creation_time) {
  if (process_id == 0) {
    return false;
  }

  HANDLE process =
      OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, FALSE,
                  process_id);
  if (process == nullptr) {
    return false;
  }

  bool is_running = IsTrackedProcessRunning(process);
  if (is_running && creation_time != 0) {
    const auto actual_creation_time = GetProcessCreationTime(process);
    is_running = actual_creation_time.has_value() &&
                 actual_creation_time.value() == creation_time;
  }
  CloseHandle(process);
  return is_running;
}

void PersistTrackedProcessState() {
  if (g_java_process_id == 0) {
    const std::string path = GetTrackedProcessStatePath();
    DeleteFileA(path.c_str());
    return;
  }

  EnsureStateDirectoryExists();
  const std::string path = GetTrackedProcessStatePath();
  std::ofstream state(path, std::ios::trunc);
  if (!state.is_open()) {
    return;
  }

  state << g_java_process_id << "\n"
        << g_java_process_creation_time << "\n"
        << g_java_process_image_path << "\n"
        << g_server_jar_path << "\n";
}

std::optional<PersistedProcessInfo> LoadTrackedProcessState() {
  const std::string path = GetTrackedProcessStatePath();
  std::ifstream state(path);
  if (!state.is_open()) {
    return std::nullopt;
  }

  PersistedProcessInfo info = {};
  if (!(state >> info.process_id)) {
    return std::nullopt;
  }
  state.ignore((std::numeric_limits<std::streamsize>::max)(), '\n');

  std::string creation_time_line;
  if (!std::getline(state, creation_time_line)) {
    creation_time_line = "0";
  }
  try {
    info.creation_time = creation_time_line.empty()
                             ? 0
                             : static_cast<uint64_t>(std::stoull(creation_time_line));
  } catch (...) {
    info.creation_time = 0;
  }

  std::getline(state, info.image_path);
  std::getline(state, info.server_jar_path);

  if (info.process_id == 0) {
    return std::nullopt;
  }

  return info;
}

void CloseTrackedProcessHandle() {
  if (g_java_process != nullptr) {
    CloseHandle(g_java_process);
    g_java_process = nullptr;
  }
}

void ClearTrackedProcess() {
  CloseTrackedProcessHandle();
  g_java_process_id = 0;
  g_java_process_creation_time = 0;
  g_java_process_image_path.clear();
  g_server_jar_path.clear();
  DeleteFileA(GetTrackedProcessStatePath().c_str());
}

HANDLE GetTrackedProcessHandle() {
  if (DoesTrackedProcessMatch(g_java_process)) {
    return g_java_process;
  }

  if (g_java_process != nullptr) {
    CloseTrackedProcessHandle();
  }

  if (g_java_process_id == 0) {
    const auto persisted = LoadTrackedProcessState();
    if (!persisted.has_value()) {
      return nullptr;
    }

    g_java_process_id = persisted->process_id;
    g_java_process_creation_time = persisted->creation_time;
    g_java_process_image_path = persisted->image_path;
    g_server_jar_path = persisted->server_jar_path;
  }

  if (g_java_process_id == 0) {
    return nullptr;
  }

  HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION |
                                   PROCESS_TERMINATE | SYNCHRONIZE,
                               FALSE, g_java_process_id);
  if (process == nullptr) {
    ClearTrackedProcess();
    return nullptr;
  }

  if (!DoesTrackedProcessMatch(process)) {
    CloseHandle(process);
    ClearTrackedProcess();
    return nullptr;
  }

  g_java_process = process;
  return g_java_process;
}

bool ForceKillProcessTree(DWORD process_id,
                          uint64_t creation_time,
                          std::string* error_message) {
  std::ostringstream command;
  command << "taskkill /PID " << process_id << " /T /F";
  std::string command_line = command.str();
  std::vector<char> command_buffer(command_line.begin(), command_line.end());
  command_buffer.push_back('\0');

  STARTUPINFOA startup_info = {};
  startup_info.cb = sizeof(startup_info);
  startup_info.dwFlags = STARTF_USESHOWWINDOW;
  startup_info.wShowWindow = SW_HIDE;

  PROCESS_INFORMATION process_info = {};
  const BOOL ok = CreateProcessA(
      nullptr, command_buffer.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW,
      nullptr, nullptr, &startup_info, &process_info);
  if (!ok) {
    if (error_message != nullptr) {
      std::ostringstream message;
      message << "taskkill launch failed for pid " << process_id
              << " (error " << GetLastError() << ")";
      *error_message = message.str();
    }
    return false;
  }

  CloseHandle(process_info.hThread);
  const DWORD wait_result = WaitForSingleObject(process_info.hProcess, 10000);
  DWORD exit_code = 0;
  GetExitCodeProcess(process_info.hProcess, &exit_code);
  CloseHandle(process_info.hProcess);
  (void)wait_result;
  (void)exit_code;

  if (!IsSpecificProcessRunning(process_id, creation_time)) {
    return true;
  }

  if (error_message != nullptr && error_message->empty()) {
    std::ostringstream failure;
    failure << "taskkill did not terminate pid " << process_id;
    *error_message = failure.str();
  }
  return false;
}

bool StopTrackedProcess(DWORD process_id,
                        uint64_t creation_time,
                        HANDLE process,
                        DWORD timeout_ms,
                        std::string* error_message) {
  if (process == nullptr) {
    if (error_message != nullptr) {
      *error_message = "No tracked process handle was available";
    }
    return false;
  }

  if (TerminateProcess(process, 0) == 0) {
    std::ostringstream message;
    message << "TerminateProcess failed for pid " << process_id
            << " (error " << GetLastError() << ")";
    if (error_message != nullptr) {
      *error_message = message.str();
    }
    return ForceKillProcessTree(process_id, creation_time, error_message);
  }

  const DWORD wait_result = WaitForSingleObject(process, timeout_ms);
  if (wait_result == WAIT_OBJECT_0 && !IsTrackedProcessRunning(process)) {
    return true;
  }

  std::ostringstream message;
  message << "Process did not exit cleanly after TerminateProcess for pid "
          << process_id << " (wait_result=" << wait_result << ")";
  if (error_message != nullptr) {
    *error_message = message.str();
  }
  return ForceKillProcessTree(process_id, creation_time, error_message);
}

}

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

MExtensionServerPlugin::~MExtensionServerPlugin() = default;

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

bool MExtensionServerPlugin::StopRunningProcess(std::string* error_message) {
  HANDLE process = GetTrackedProcessHandle();
  if (process == nullptr) {
    return true;
  }

  const DWORD process_id = g_java_process_id;
  const uint64_t creation_time = g_java_process_creation_time;

  if (!StopTrackedProcess(process_id, creation_time, process, 3000,
                          error_message)) {
    return false;
  }

  ClearTrackedProcess();
  return true;
}

void MExtensionServerPlugin::StartServer(
    int port,
    const std::string& jvm_path,
    const std::string& server_jar_path,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  std::string stop_error;
  if (!StopRunningProcess(&stop_error)) {
    result->Error("START_ERROR",
                  "Failed to stop previous Java server: " + stop_error);
    return;
  }

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

  if (WaitForSingleObject(pi.hProcess, 250) == WAIT_OBJECT_0) {
    DWORD exit_code = 0;
    GetExitCodeProcess(pi.hProcess, &exit_code);
    CloseHandle(pi.hProcess);

    std::ostringstream msg;
    msg << "Java server exited immediately with code " << exit_code << ": "
        << cmd_str;
    result->Error("START_ERROR", msg.str());
    return;
  }

  g_java_process = pi.hProcess;
  g_java_process_id = pi.dwProcessId;
  g_java_process_creation_time =
      GetProcessCreationTime(pi.hProcess).value_or(0);
  g_java_process_image_path = GetProcessImagePath(pi.hProcess);
  g_server_jar_path = server_jar_path;

  std::string job_error;
  if (!AssignProcessToJavaJob(pi.hProcess, &job_error)) {
    TerminateProcess(pi.hProcess, 0);
    WaitForSingleObject(pi.hProcess, 3000);
    ClearTrackedProcess();
    result->Error("START_ERROR",
                  "Failed to attach Java server to shutdown job: " + job_error);
    return;
  }

  PersistTrackedProcessState();

  std::ostringstream ok_msg;
  ok_msg << "Server started on port " << port;
  result->Success(flutter::EncodableValue(ok_msg.str()));
}

void MExtensionServerPlugin::StopServer(
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  HANDLE process = GetTrackedProcessHandle();
  if (process == nullptr) {
    result->Success(flutter::EncodableValue("Server was not running"));
    return;
  }

  const DWORD process_id = g_java_process_id;
  const uint64_t creation_time = g_java_process_creation_time;
  std::string stop_error;
  if (!StopTrackedProcess(process_id, creation_time, process, 5000,
                          &stop_error)) {
    result->Error("STOP_ERROR", stop_error);
    return;
  }

  ClearTrackedProcess();

  result->Success(flutter::EncodableValue("Server stopped"));
}

#ifdef M_EXTENSION_SERVER_TESTING
void MExtensionServerPlugin::TrackProcessForTesting(HANDLE process,
                                                    DWORD process_id) {
  ClearTrackedProcess();
  g_java_process = process;
  g_java_process_id = process_id;
  g_java_process_creation_time = GetProcessCreationTime(process).value_or(0);
  PersistTrackedProcessState();
}
#endif

}
