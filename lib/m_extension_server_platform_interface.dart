import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'm_extension_server_method_channel.dart';

abstract class MExtensionServerPlatform extends PlatformInterface {
  /// Constructs a MExtensionServerPlatform.
  MExtensionServerPlatform() : super(token: _token);

  static final Object _token = Object();

  static MExtensionServerPlatform _instance = MethodChannelMExtensionServer();

  /// The default instance of [MExtensionServerPlatform] to use.
  ///
  /// Defaults to [MethodChannelMExtensionServer].
  static MExtensionServerPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [MExtensionServerPlatform] when
  /// they register themselves.
  static set instance(MExtensionServerPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  /// Starts the HTTP extension server on the given [port].
  ///
  /// Android does not need any extra parameters.
  ///
  /// Windows, Linux, and macOS start the server by spawning a Java
  /// subprocess:
  ///
  /// * [jvmPath]       – absolute path to the `java` / `java.exe` executable,
  ///                     or a Java home directory whose `bin/java` should be
  ///                     used. When omitted the plugin resolves `java` via
  ///                     `PATH` (or `JAVA_HOME` on macOS).
  /// * [serverJarPath] – absolute path to the fat-JAR that implements the
  ///                     extension server. Required on these platforms.
  Future<String?> startServer(
    int port, {
    String? jvmPath,
    String? serverJarPath,
  }) {
    throw UnimplementedError('startServer() has not been implemented.');
  }

  Future<String?> stopServer() {
    throw UnimplementedError('stopServer() has not been implemented.');
  }
}
