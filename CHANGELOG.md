## 0.0.2

* **Windows / Linux / macOS**: added support for starting and stopping the
  extension server by launching a standalone Java JAR. The caller supplies
  the path to the `java` executable via the new `jvmPath` parameter and the
  path to the server JAR via `serverJarPath`.
* `startServer()` Dart API extended with named parameters `jvmPath` and
  `serverJarPath` (both optional on Android, required on desktop).

## 0.0.1

* Initial Android-only release.
## 0.0.4

* Add an embedded, interpreter-only OpenJDK Zero runtime for physical iOS devices.
* Bundle the iOS M-Extension-Server JAR and pause/resume it with app lifecycle.
