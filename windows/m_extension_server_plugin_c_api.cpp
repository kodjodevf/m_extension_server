#include "include/m_extension_server/m_extension_server_plugin_c_api.h"

#include <flutter/plugin_registrar_windows.h>

#include "m_extension_server_plugin.h"

void MExtensionServerPluginCApiRegisterWithRegistrar(
    FlutterDesktopPluginRegistrarRef registrar) {
  m_extension_server::MExtensionServerPlugin::RegisterWithRegistrar(
      flutter::PluginRegistrarManager::GetInstance()
          ->GetRegistrar<flutter::PluginRegistrarWindows>(registrar));
}
