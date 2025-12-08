# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep the plugin class
-keep class com.kodjodevf.m_extension_server.MExtensionServerPlugin {
    public *;
}

# Keep all classes in the m_extension_server package
-keep class com.kodjodevf.m_extension_server.** {
    *;
}

# Keep model classes for Jackson serialization
-keep class m_extension_server.model.** {
    *;
}

# Keep impl classes
-keep class m_extension_server.impl.** {
    *;
}

# Keep controller classes
-keep class m_extension_server.controller.** {
    *;
}

# Keep the server controller
-keep class m_extension_server.controller.MExtensionServerController {
    *;
}

# Keep eu.kanade.tachiyomi classes
-keep class eu.kanade.tachiyomi.** {
    *;
}

# Jackson
-keep class com.fasterxml.jackson.** { *; }
-keep @com.fasterxml.jackson.annotation.* class * { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
}
-dontwarn com.fasterxml.jackson.databind.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep data classes members
-keepclassmembers class * {
    public <init>(...);
}

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class okio.** { *; }
-dontwarn okio.**

# Injekt
-keep class uy.kohesive.injekt.** { *; }

# Kotlin stdlib - IMPORTANT: keep all Kotlin standard library classes
-keep class kotlin.** { *; }
-keep class kotlin.text.** { *; }
-keep class kotlin.collections.** { *; }
-keep class kotlin.sequences.** { *; }
-keep class kotlin.ranges.** { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.coroutines.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# Keep Kotlin Metadata
-keepattributes RuntimeVisibleAnnotations
-keep class kotlin.Metadata { *; }

# RxJava
-keep class rx.** { *; }
-dontwarn rx.**

# Jsoup
-keep class org.jsoup.** { *; }
-keepclassmembers class org.jsoup.nodes.Document { *; }

# AndroidX Preference - IMPORTANT: keep all methods
-keep class androidx.preference.** { *; }
-keep interface androidx.preference.** { *; }
-keepclassmembers class androidx.preference.** { *; }

# AndroidX Core
-keep class androidx.core.** { *; }
-keep class androidx.** { *; }
-dontwarn androidx.**

-keepattributes EnclosingMethod
