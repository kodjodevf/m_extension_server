import 'm_extension_server_platform_interface.dart';

class MExtensionServer {
  /// Starts the HTTP extension server on [port].
  ///
  /// See [MExtensionServerPlatform.startServer] for full per-platform
  /// documentation of every parameter.
  ///
  /// Quick reference:
  /// * **Android** — no extra parameters needed.
  /// * **Windows / Linux / macOS** — provide [serverJarPath] (and optionally
  ///   [jvmPath]).
  Future<String?> startServer(
    int port, {
    String? jvmPath, // desktop only
    String? serverJarPath, // desktop only
  }) {
    return MExtensionServerPlatform.instance.startServer(
      port,
      jvmPath: jvmPath,
      serverJarPath: serverJarPath,
    );
  }

  Future<String?> stopServer() {
    return MExtensionServerPlatform.instance.stopServer();
  }
}
