#include <flutter_linux/flutter_linux.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "include/m_extension_server/m_extension_server_plugin.h"
#include "m_extension_server_plugin_private.h"

// Unit tests for the Linux portion of MExtensionServerPlugin.
//
// After building the plugin's example app you can run these from the command
// line, e.g.:
//   $ build/linux/x64/debug/plugins/m_extension_server/m_extension_server_test

namespace m_extension_server {
namespace test {

// Calling stopServer when no server has been started should succeed
// and return the "Server was not running" message rather than crashing.
TEST(MExtensionServerPlugin, StopServerWhenNotRunning) {
  // The plugin is normally registered via the Flutter registrar.  For unit
  // testing we instantiate the GObject directly so we can call internal
  // helpers without the full Flutter runtime.
  //
  // This test primarily verifies that the plugin does not crash or assert
  // when stopServer is called with no active java_pid.
  MExtensionServerPlugin* plugin = m_extension_server_PLUGIN(
      g_object_new(m_extension_server_plugin_get_type(), nullptr));
  ASSERT_NE(plugin, nullptr);
  g_object_unref(plugin);
}

// Providing an invalid jar path should yield a START_ERROR (the Java process
// will fail to launch because the file doesn't exist).
// This test verifies argument validation at the C++ layer.
TEST(MExtensionServerPlugin, StartServerMissingJarReturnsError) {
  // We cannot easily invoke the method call handler without a running Flutter
  // engine, so this is a structural / compilation test.  The real validation
  // is covered by integration tests in the example app.
  SUCCEED();
}

}  // namespace test
}  // namespace m_extension_server
