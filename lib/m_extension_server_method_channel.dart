import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'm_extension_server_platform_interface.dart';

/// An implementation of [MExtensionServerPlatform] that uses method channels.
class MethodChannelMExtensionServer extends MExtensionServerPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('m_extension_server');

  @override
  Future<String?> startServer(int port) async {
    final result = await methodChannel.invokeMethod<String>('startServer', {
      'port': port,
    });
    return result;
  }

  @override
  Future<String?> stopServer() async {
    final result = await methodChannel.invokeMethod<String>('stopServer');
    return result;
  }
}
