import Flutter
import UIKit

public final class MExtensionServerPlugin: NSObject, FlutterPlugin {
  private var requestedPort: Int32?

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(
      name: "m_extension_server",
      binaryMessenger: registrar.messenger())
    let instance = MExtensionServerPlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)
    instance.observeApplicationLifecycle()
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "startServer":
      guard
        let arguments = call.arguments as? [String: Any],
        let port = arguments["port"] as? Int,
        (1...65535).contains(port)
      else {
        result(FlutterError(
          code: "INVALID_ARGS",
          message: "Missing or invalid 'port' argument",
          details: nil))
        return
      }
      start(port: Int32(port), result: result)

    case "stopServer":
      requestedPort = nil
      MExtensionServerEmbeddedMihonStop { error in
        if let error {
          result(FlutterError(
            code: "STOP_ERROR",
            message: error.localizedDescription,
            details: nil))
        } else {
          result("Server stopped")
        }
      }

    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private func start(port: Int32, result: @escaping FlutterResult) {
    MExtensionServerEmbeddedMihonStart(port) { [weak self] startedPort, error in
      if let error {
        result(FlutterError(
          code: "START_ERROR",
          message: error.localizedDescription,
          details: nil))
      } else {
        self?.requestedPort = startedPort
        result("Server started on port \(startedPort)")
      }
    }
  }

  private func observeApplicationLifecycle() {
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(applicationDidEnterBackground),
      name: UIApplication.didEnterBackgroundNotification,
      object: nil)
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(applicationDidBecomeActive),
      name: UIApplication.didBecomeActiveNotification,
      object: nil)
  }

  @objc private func applicationDidEnterBackground() {
    guard requestedPort != nil else { return }
    MExtensionServerEmbeddedMihonPause { _ in }
  }

  @objc private func applicationDidBecomeActive() {
    guard let port = requestedPort else { return }
    MExtensionServerEmbeddedMihonStart(port) { _, _ in }
  }

  deinit {
    NotificationCenter.default.removeObserver(self)
  }
}
