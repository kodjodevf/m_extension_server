package m_extension_server.impl

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dalvik.system.DexClassLoader
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Arrays
import java.util.Base64
import java.util.UUID
import kotlin.jvm.optionals.toList
import com.kodjodevf.m_extension_server.pm

object MExtensionServerLoader {
    private var pmm: PackageManager? = null
    private const val MANGA_PACKAGE = "tachiyomi.extension"
    private const val ANIME_PACKAGE = "tachiyomi.animeextension"
    private const val METADATA_SOURCE_CLASS_SUFFIX = ".class"

    init {
        pmm = pm;
    }
    data class LoadedExtension(
        val source: Any?,
        val tempApkFile: File,
    )

    fun getSource(
        classLoader: DexClassLoader?,
        file: File,
    ): List<Any>? {
        if (pmm == null) {
            return emptyList<Any>();
        }
        val info: PackageInfo? = pmm!!.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA
        )
        if (info != null && info.applicationInfo != null) {
            println(info.applicationInfo!!.metaData.toString())
            val metaData = info.applicationInfo!!.metaData
            var metaSourceClass = metaData.getString(MANGA_PACKAGE + METADATA_SOURCE_CLASS_SUFFIX)
            if (metaSourceClass == null) {
                metaSourceClass = metaData.getString(ANIME_PACKAGE + METADATA_SOURCE_CLASS_SUFFIX)
            }
            if (metaSourceClass == null) {
                throw IllegalArgumentException("No source class found in extension metadata")
            }

            return Arrays.stream<String>(
                metaSourceClass.split(";".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            )
                .map<String?> { s: String? ->
                    val sourceClass = s!!.trim { it <= ' ' }
                    if (sourceClass.startsWith(".")) {
                        return@map info.packageName + sourceClass
                    }
                    sourceClass
                }.findFirst().map<List<Any>> { sourceClass->
                    val obj: Any =
                        Class.forName(sourceClass!!, false, classLoader).getDeclaredConstructor()
                            .newInstance()
                    val sources: List<Any> =
                        when (obj) {
                            is Source -> listOf(obj)
                            is SourceFactory -> obj.createSources()
                            is AnimeSource -> listOf(obj)
                            is AnimeSourceFactory -> obj.createSources()
                            else -> throw RuntimeException("Unknown source class type! ${obj.javaClass}")
                        }
                    sources;
                }.toList().firstOrNull()
        }
        return emptyList<Any>();
    }
    @SuppressLint("SuspiciousIndentation")
    fun loadSourceFromBase64(base64Data: String): LoadedExtension {
        val apkData = Base64.getDecoder().decode(base64Data)
        val tempApkFile = File.createTempFile("ext", ".apk")
            tempApkFile.setWritable(true)
            // Write APK data to temp file
            tempApkFile.writeBytes(apkData)
            tempApkFile.setReadOnly()
            val loader: DexClassLoader = load(tempApkFile)
            val sources = getSource(loader,tempApkFile)
            return LoadedExtension(sources?.firstOrNull(),tempApkFile)
        
    }

    private fun load(file: File): DexClassLoader {
        return DexClassLoader(file.getAbsolutePath(), null, null, this.javaClass.getClassLoader())
    }

}
