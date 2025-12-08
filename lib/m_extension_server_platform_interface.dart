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

  Future<String?> startServer(int port) {
    throw UnimplementedError('startServer() has not been implemented.');
  }

  Future<String?> stopServer() {
    throw UnimplementedError('stopServer() has not been implemented.');
  }
}
