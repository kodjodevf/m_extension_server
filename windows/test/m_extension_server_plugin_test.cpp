#include <flutter/method_call.h>
#include <flutter/method_result_functions.h>
#include <flutter/standard_method_codec.h>
#include <gtest/gtest.h>
#include <windows.h>

#include <memory>
#include <string>
#include <variant>
#include <vector>

#include "m_extension_server_plugin.h"

namespace m_extension_server {
namespace test {

namespace {

using flutter::EncodableMap;
using flutter::EncodableValue;
using flutter::MethodCall;
using flutter::MethodResultFunctions;

PROCESS_INFORMATION StartLongRunningTestProcess() {
  std::string command = "ping -n 20 127.0.0.1";
  std::vector<char> command_buffer(command.begin(), command.end());
  command_buffer.push_back('\0');

  STARTUPINFOA startup_info = {};
  startup_info.cb = sizeof(startup_info);
  startup_info.dwFlags = STARTF_USESHOWWINDOW;
  startup_info.wShowWindow = SW_HIDE;

  PROCESS_INFORMATION process_info = {};
  const BOOL ok = CreateProcessA(
      nullptr, command_buffer.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW,
      nullptr, nullptr, &startup_info, &process_info);
  EXPECT_TRUE(ok);
  return process_info;
}

}  // namespace

// Calling stopServer when no server is running should succeed gracefully.
TEST(MExtensionServerPlugin, StopServerWhenNotRunning) {
  MExtensionServerPlugin plugin;

  std::string result_string;
  std::string error_code;

  plugin.HandleMethodCall(
      MethodCall("stopServer", std::make_unique<EncodableValue>()),
      std::make_unique<MethodResultFunctions<>>(
          [&result_string](const EncodableValue* result) {
            if (result) result_string = std::get<std::string>(*result);
          },
          [&error_code](const std::string& code, const std::string&,
                        const EncodableValue*) {
            error_code = code;
          },
          nullptr));

  EXPECT_TRUE(error_code.empty());
  EXPECT_EQ(result_string, "Server was not running");
}

// startServer without a serverJarPath argument must return INVALID_ARGS.
TEST(MExtensionServerPlugin, StartServerMissingJarReturnsError) {
  MExtensionServerPlugin plugin;

  std::string error_code;
  std::string error_message;

  EncodableMap args;
  args[EncodableValue("port")] = EncodableValue(8080);
  // Intentionally omit "serverJarPath".

  plugin.HandleMethodCall(
      MethodCall("startServer",
                 std::make_unique<EncodableValue>(args)),
      std::make_unique<MethodResultFunctions<>>(
          nullptr,
          [&error_code, &error_message](const std::string& code,
                                        const std::string& msg,
                                        const EncodableValue*) {
            error_code    = code;
            error_message = msg;
          },
          nullptr));

  EXPECT_EQ(error_code, "INVALID_ARGS");
  EXPECT_FALSE(error_message.empty());
}

// startServer without a port argument must return INVALID_ARGS.
TEST(MExtensionServerPlugin, StartServerMissingPortReturnsError) {
  MExtensionServerPlugin plugin;

  std::string error_code;

  EncodableMap args;
  args[EncodableValue("serverJarPath")] = EncodableValue(std::string("server.jar"));
  // Intentionally omit "port".

  plugin.HandleMethodCall(
      MethodCall("startServer",
                 std::make_unique<EncodableValue>(args)),
      std::make_unique<MethodResultFunctions<>>(
          nullptr,
          [&error_code](const std::string& code, const std::string&,
                        const EncodableValue*) {
            error_code = code;
          },
          nullptr));

  EXPECT_EQ(error_code, "INVALID_ARGS");
}

// A process started before plugin re-instantiation must still be stoppable.
TEST(MExtensionServerPlugin, StopServerReconnectsAfterPluginRecreation) {
  PROCESS_INFORMATION process_info = StartLongRunningTestProcess();
  ASSERT_NE(process_info.hProcess, nullptr);
  ASSERT_NE(process_info.dwProcessId, 0u);
  CloseHandle(process_info.hThread);

  MExtensionServerPlugin::TrackProcessForTesting(process_info.hProcess,
                                                 process_info.dwProcessId);

  {
    MExtensionServerPlugin old_plugin;
  }

  MExtensionServerPlugin new_plugin;

  std::string result_string;
  std::string error_code;

  new_plugin.HandleMethodCall(
      MethodCall("stopServer", std::make_unique<EncodableValue>()),
      std::make_unique<MethodResultFunctions<>>(
          [&result_string](const EncodableValue* result) {
            if (result) result_string = std::get<std::string>(*result);
          },
          [&error_code](const std::string& code, const std::string&,
                        const EncodableValue*) {
            error_code = code;
          },
          nullptr));

  EXPECT_TRUE(error_code.empty());
  EXPECT_EQ(result_string, "Server stopped");
}

// An unknown method must return NotImplemented (nullptr result, no error).
TEST(MExtensionServerPlugin, UnknownMethodReturnsNotImplemented) {
  MExtensionServerPlugin plugin;

  bool not_implemented_called = false;

  plugin.HandleMethodCall(
      MethodCall("unknownMethod", std::make_unique<EncodableValue>()),
      std::make_unique<MethodResultFunctions<>>(
          nullptr, nullptr,
          [&not_implemented_called]() {
            not_implemented_called = true;
          }));

  EXPECT_TRUE(not_implemented_called);
}

}  // namespace test
}  // namespace m_extension_server
