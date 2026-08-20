package m_extension_server.impl

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.Base64

object MExtensionServerLoader {
    private const val TAG = "MExtensionServerLoader"
    private const val MANGA_PACKAGE = "tachiyomi.extension"
    private const val ANIME_PACKAGE = "tachiyomi.animeextension"
    private const val MANGA_PACKAGE_X = "tachiyomix.extension"
    private const val ANIME_PACKAGE_X = "tachiyomix.animeextension"
    private const val METADATA_SOURCE_CLASS_SUFFIX = ".class"

    private val loadedExtensions =
        ExtensionInstanceCache(
            keyOf = ::sha256,
            load = ::loadExtension,
            dispose = LoadedExtension::close,
        )

    private fun getPackageManager(): PackageManager? =
        com.kodjodevf.m_extension_server.pm
            ?: com.kodjodevf.m_extension_server.instance?.applicationContext?.packageManager
            ?: runCatching { Injekt.get<Application>().packageManager }.getOrNull()

    class LoadedExtension(
        initialSources: List<Any>,
        val tempApkFile: File,
        val packageInfo: PackageInfo?,
    ) : AutoCloseable {
        val source: Any? = initialSources.firstOrNull()
        val sources: List<Any> = initialSources

        override fun close() {
            try {
                sources
                    .filterIsInstance<Source>()
                    .forEach(MihonMetadataCache::remove)
            } finally {
                if (tempApkFile.exists()) {
                    tempApkFile.delete()
                }
            }
        }
    }

    fun <T> invokeWithExtension(
        base64Data: String,
        block: (LoadedExtension) -> T,
    ): T {
        val apkData = Base64.getDecoder().decode(base64Data)
        return loadedExtensions.use(apkData, block)
    }

    private fun loadExtension(apkData: ByteArray): LoadedExtension {
        val tempApkFile = File.createTempFile("ext-", ".apk")
        tempApkFile.setWritable(true)
        tempApkFile.writeBytes(apkData)
        tempApkFile.setReadOnly()

        val loader = load(tempApkFile)
        val packageManager = getPackageManager()
        val info: PackageInfo? =
            packageManager?.getPackageArchiveInfo(
                tempApkFile.absolutePath,
                PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA,
            )?.apply {
                applicationInfo?.fixBasePaths(tempApkFile.absolutePath)
            }

        val sources = getSource(loader, tempApkFile, info)
        return LoadedExtension(sources, tempApkFile, info)
    }

    private fun getSource(
        classLoader: ClassLoader,
        file: File,
        info: PackageInfo?,
    ): List<Any> {
        if (info == null || info.applicationInfo == null) {
            Log.e(TAG, "Failed to parse APK package info from ${file.absolutePath}")
            return emptyList()
        }

        val appInfo = info.applicationInfo
        val metaData = appInfo?.metaData
        if (metaData == null) {
            Log.e(TAG, "MetaData is null for package ${info.packageName}")
            return emptyList()
        }

        val metaSourceClass =
            metaData.getString(MANGA_PACKAGE + METADATA_SOURCE_CLASS_SUFFIX)
                ?: metaData.getString(ANIME_PACKAGE + METADATA_SOURCE_CLASS_SUFFIX)
                ?: metaData.getString(MANGA_PACKAGE_X + METADATA_SOURCE_CLASS_SUFFIX)
                ?: metaData.getString(ANIME_PACKAGE_X + METADATA_SOURCE_CLASS_SUFFIX)

        if (metaSourceClass == null) {
            Log.e(TAG, "No source class found in metadata: ${metaData.keySet().map { "$it=${metaData.get(it)}" }}")
            throw IllegalArgumentException("No source class found in extension metadata")
        }

        val classNames =
            metaSourceClass
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { sourceClass ->
                    if (sourceClass.startsWith(".")) {
                        info.packageName + sourceClass
                    } else {
                        sourceClass
                    }
                }

        Log.d(TAG, "Loading extension source classes: $classNames")

        val sources =
            classNames.flatMap { className ->
                try {
                    val obj = instantiateSource(className, classLoader, file.absolutePath)
                    when (obj) {
                        is Source -> listOf(obj)
                        is SourceFactory -> obj.createSources()
                        is AnimeSource -> listOf(obj)
                        is AnimeSourceFactory -> obj.createSources()
                        else -> throw RuntimeException("Unknown source class type: ${obj.javaClass}")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error instantiating class $className", t)
                    throw t
                }
            }

        return sources
    }

    private fun load(file: File): ClassLoader =
        ChildFirstPathClassLoader(file.absolutePath, null, this.javaClass.classLoader!!)

    private fun instantiateSource(
        sourceClass: String,
        classLoader: ClassLoader,
        sourcePath: String,
    ): Any =
        try {
            Class.forName(sourceClass, false, classLoader).getDeclaredConstructor().newInstance()
        } catch (error: Throwable) {
            Log.w(TAG, "ChildFirstPathClassLoader failed to load $sourceClass, falling back to PathClassLoader: ${error.message}")
            val fallbackClassLoader = PathClassLoader(sourcePath, null, this.javaClass.classLoader)
            Class.forName(sourceClass, false, fallbackClassLoader).getDeclaredConstructor().newInstance()
        }

    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) {
            sourceDir = apkPath
        }
        if (publicSourceDir == null) {
            publicSourceDir = apkPath
        }
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(data)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
