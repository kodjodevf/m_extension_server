#ifndef FLUTTER_PLUGIN_m_extension_server_PLUGIN_H_
#define FLUTTER_PLUGIN_m_extension_server_PLUGIN_H_

#include <windows.h>

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>

#include <memory>

namespace m_extension_server {

class MExtensionServerPlugin : public flutter::Plugin {
 public:
  static void RegisterWithRegistrar(flutter::PluginRegistrarWindows* registrar);

  MExtensionServerPlugin();
  ~MExtensionServerPlugin() override;

  MExtensionServerPlugin(const MExtensionServerPlugin&) = delete;
  MExtensionServerPlugin& operator=(const MExtensionServerPlugin&) = delete;

  void HandleMethodCall(
      const flutter::MethodCall<flutter::EncodableValue>& method_call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);

 private:
  HANDLE java_process_ = INVALID_HANDLE_VALUE;

  void StopRunningProcess();

  void StartServer(
      int port,
      const std::string& jvm_path,
      const std::string& server_jar_path,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);

  void StopServer(
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);
};

}  // namespace m_extension_server

#endif  // FLUTTER_PLUGIN_m_extension_server_PLUGIN_H_
