import 'm_extension_server_platform_interface.dart';

class MExtensionServer {
  Future<String?> startServer(int port) {
    return MExtensionServerPlatform.instance.startServer(port);
  }

  Future<String?> stopServer() {
    return MExtensionServerPlatform.instance.stopServer();
  }
}
