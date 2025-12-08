#ifndef FLUTTER_PLUGIN_m_extension_server_PLUGIN_H_
#define FLUTTER_PLUGIN_m_extension_server_PLUGIN_H_

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>

#include <memory>

namespace m_extension_server {

class MExtensionServerPlugin : public flutter::Plugin {
 public:
  static void RegisterWithRegistrar(flutter::PluginRegistrarWindows *registrar);

  MExtensionServerPlugin();

  virtual ~MExtensionServerPlugin();

  // Disallow copy and assign.
  MExtensionServerPlugin(const MExtensionServerPlugin&) = delete;
  MExtensionServerPlugin& operator=(const MExtensionServerPlugin&) = delete;

  // Called when a method is called on this plugin's channel from Dart.
  void HandleMethodCall(
      const flutter::MethodCall<flutter::EncodableValue> &method_call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);
};

}  // namespace m_extension_server

#endif  // FLUTTER_PLUGIN_m_extension_server_PLUGIN_H_
