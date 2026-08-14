# m_extension_server

Flutter plugin for running M-Extension-Server:

- Android: in-process Dalvik extension bridge.
- iOS device: in-process OpenJDK Zero interpreter, loaded lazily without JIT.
- macOS, Linux, Windows: standalone Java process.

On iOS, `pod install` downloads checksum-pinned runtime artifacts and creates
`OpenJDKRuntime.xcframework`. The host must embed that framework without linking
it at launch; see Mangayomi's Podfile integration.

```dart
await MExtensionServer().startServer(port);
await MExtensionServer().stopServer();
```

The iOS runtime supports physical devices only. The server listener pauses in
the background and resumes when the app becomes active.
