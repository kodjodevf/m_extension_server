package m_extension_server.impl

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.edit
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.size
import m_extension_server.model.AnimeData
import m_extension_server.model.AnimeResponse
import m_extension_server.model.ChapterData
import m_extension_server.model.DataBody
import m_extension_server.model.EpisodeData
import m_extension_server.model.JAnime
import m_extension_server.model.JChapter
import m_extension_server.model.JEpisode
import m_extension_server.model.JFilterList
import m_extension_server.model.JManga
import m_extension_server.model.JPage
import m_extension_server.model.MangaData
import m_extension_server.model.MangaResponse
import m_extension_server.model.toJAnime
import m_extension_server.model.toJChapter
import m_extension_server.model.toJEpisode
import m_extension_server.model.toJManga
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.kodjodevf.m_extension_server.MExtensionServerPlugin
import com.kodjodevf.m_extension_server.instance
import com.kodjodevf.m_extension_server.preferenceManager
import m_extension_server.model.BridgeMemo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

object MihonInvoker {
    private const val KL_RAW_SOURCE_ID = "7433897302034602657"
    private const val BRIDGE_CONTEXT_KEY = "__mangayomi_bridge_context__"
    private const val HOSTER_TIMEOUT_MS = 30_000L
    private val sourceHttpServers = ConcurrentHashMap<Long, HttpServer>()

    private fun bridgeContext(data: DataBody): Map<String, Any> =
        data.preferences
            ?.firstOrNull { it["key"] == BRIDGE_CONTEXT_KEY }
            .orEmpty()

    fun selectSource(
        loadedSources: List<Any>,
        data: DataBody,
    ): Any {
        if (loadedSources.isEmpty()) {
            throw IllegalArgumentException("No sources found in extension")
        }
        if (loadedSources.size == 1) {
            return loadedSources.first()
        }

        // 1. Try matching by requested sourceId if provided
        val requestedSourceId =
            data.sourceId
                ?: bridgeContext(data)["sourceId"]?.toString()

        if (!requestedSourceId.isNullOrBlank()) {
            val match =
                loadedSources.firstOrNull {
                    val sId = sourceId(it)
                    sId == requestedSourceId ||
                        "mihon-$sId".hashCode().toString() == requestedSourceId ||
                        sId.hashCode().toString() == requestedSourceId
                }
            if (match != null) return match
        }

        // 2. Try matching by base URL. Clients that rewrite the source id —
        // Mangayomi hashes it, and a Dart hash never equals a JVM one — cannot
        // be matched above, and an extension whose sources share a language
        // would otherwise always resolve to the same one.
        val requestedBaseUrl =
            data.sourceBaseUrl
                ?: bridgeContext(data)["sourceBaseUrl"]?.toString()

        if (!requestedBaseUrl.isNullOrBlank()) {
            val urlMatch =
                loadedSources.firstOrNull {
                    sourceBaseUrl(it)?.trimEnd('/').equals(requestedBaseUrl.trimEnd('/'), ignoreCase = true)
                }
            if (urlMatch != null) return urlMatch
        }

        // 3. Try matching by language if provided (e.g. "fr", "en", "es", "all")
        val requestedLang =
            data.lang
                ?: bridgeContext(data)["lang"]?.toString()

        if (!requestedLang.isNullOrBlank()) {
            val langMatch =
                loadedSources.firstOrNull {
                    sourceLanguage(it).equals(requestedLang, ignoreCase = true)
                }
            if (langMatch != null) return langMatch
        }

        return loadedSources.first()
    }

    private fun sourceBaseUrl(source: Any): String? =
        when (source) {
            is HttpSource -> source.baseUrl
            is AnimeHttpSource -> source.baseUrl
            else -> null
        }

    private fun sourceId(source: Any): String =
        when (source) {
            is Source -> source.id.toString()
            is AnimeSource -> source.id.toString()
            else -> throw IllegalArgumentException("Unknown source type: ${source.javaClass}")
        }

    private fun sourceLanguage(source: Any): String =
        when (source) {
            is Source -> source.lang
            is AnimeSource -> source.lang
            else -> ""
        }

    fun invokeMethod(
        loadedSource: Any?,
        data: DataBody,
    ): Any {
        val source =
            loadedSource
                ?: throw IllegalArgumentException("No source found in extension")

        applyPreferences(data, source)

        return when (data.method) {
            "headersManga" -> invokeHeadersManga(source as CatalogueSource)
            "filtersManga" -> invokeFiltersManga(source as CatalogueSource)
            "supportLatestManga" -> invokeSupportLatestManga(source as CatalogueSource)
            "getPopularManga" -> invokeGetPopularManga(source as CatalogueSource, data.page ?: 1)
            "getLatestManga" -> invokeGetLatestManga(source as CatalogueSource, data.page ?: 1)
            "getSearchManga" -> invokeGetSearchManga(source as CatalogueSource, data.page ?: 1, data.search ?: "", data.filterList)
            "getDetailsManga" -> invokeGetDetailsManga(source as CatalogueSource, data.mangaData)
            "getMangaUrl" -> invokeGetMangaUrl(source as CatalogueSource, data.mangaData)
            "getChapterList" -> invokeGetChapterList(source as CatalogueSource, data.mangaData)
            "getChapterUrl" -> invokeGetChapterUrl(source as CatalogueSource, data.chapterData)
            "getPageList" -> invokeGetPageList(source as CatalogueSource, data.chapterData)
            "preferencesManga" -> invokePreferencesManga(source as CatalogueSource)
            "setPreferenceManga" -> invokeSetPreferenceManga(source as CatalogueSource, data)
            "headersAnime" -> invokeHeadersAnime(source as AnimeCatalogueSource)
            "filtersAnime" -> invokeFiltersAnime(source as AnimeCatalogueSource)
            "supportLatestAnime" -> invokeSupportLatestAnime(source as AnimeCatalogueSource)
            "getPopularAnime" -> invokeGetPopularAnime(source as AnimeCatalogueSource, data.page ?: 1)
            "getLatestAnime" -> invokeGetLatestAnime(source as AnimeCatalogueSource, data.page ?: 1)
            "getSearchAnime" -> invokeGetSearchAnime(source as AnimeCatalogueSource, data.page ?: 1, data.search ?: "", data.filterList)
            "getDetailsAnime" -> invokeGetDetailsAnime(source as AnimeCatalogueSource, data.animeData)
            "getAnimeUrl" -> invokeGetAnimeUrl(source as AnimeCatalogueSource, data.animeData)
            "getEpisodeList" -> invokeGetEpisodeList(source as AnimeCatalogueSource, data.animeData)
            "getEpisodeUrl" -> invokeGetEpisodeUrl(source as AnimeCatalogueSource, data.episodeData)
            "getVideoList" -> invokeGetVideoList(source as AnimeCatalogueSource, data.episodeData)
            "preferencesAnime" -> invokePreferencesAnime(source as AnimeCatalogueSource)
            "setPreferenceAnime" -> invokeSetPreferenceAnime(source as AnimeCatalogueSource, data)
            else -> throw IllegalArgumentException("Unknown method: ${data.method}")
        }
    }

    private fun invokeHeadersManga(source: CatalogueSource): List<String> =
        if (source is HttpSource) {
            val headers =
                source.headers.toMultimap().flatMap { (name, values) ->
                    values.flatMap { value ->
                        listOf(name.replaceFirstChar { it.uppercase() }, value)
                    }
                }
            headers
        } else {
            emptyList()
        }

    private fun invokeFiltersManga(source: CatalogueSource): FilterList {
        val filterList = source.getFilterList()
        return filterList
    }

    private fun invokeSupportLatestManga(source: CatalogueSource): Boolean = source.supportsLatest

    private fun invokeGetPopularManga(
        source: CatalogueSource,
        page: Int,
    ): MangaResponse =
        runBlocking {
            val mangasPage = source.getPopularManga(page)
            MangaResponse(
                mangas =
                    mangasPage.mangas.map { manga ->
                        MihonMetadataCache.remember(source, manga)
                        manga.toJManga()
                    },
                hasNextPage = mangasPage.hasNextPage,
            )
        }

    private fun invokeGetLatestManga(
        source: CatalogueSource,
        page: Int,
    ): MangaResponse =
        runBlocking {
            val mangasPage =
                if (source.supportsLatest) {
                    try {
                        source.getLatestUpdates(page)
                    } catch (_: UnsupportedOperationException) {
                        source.getPopularManga(page)
                    }
                } else {
                    source.getPopularManga(page)
                }
            MangaResponse(
                mangas =
                    mangasPage.mangas.map { manga ->
                        MihonMetadataCache.remember(source, manga)
                        manga.toJManga()
                    },
                hasNextPage = mangasPage.hasNextPage,
            )
        }

    private fun invokeGetSearchManga(
        source: CatalogueSource,
        page: Int,
        search: String,
        filterList: List<JFilterList>?,
    ): MangaResponse =
        runBlocking {
            val convertedFilters = filterList?.let { convertFilterList(source.getFilterList(), it) } ?: source.getFilterList()
            val mangasPage = source.getSearchManga(page, search, convertedFilters)
            MangaResponse(
                mangas =
                    mangasPage.mangas.map { manga ->
                        MihonMetadataCache.remember(source, manga)
                        manga.toJManga()
                    },
                hasNextPage = mangasPage.hasNextPage,
            )
        }

    private fun invokeGetDetailsManga(
        source: CatalogueSource,
        mangaData: MangaData?,
    ): JManga {
        if (mangaData == null) {
            throw IllegalArgumentException("mangaData is required for getDetailsManga")
        }

        return runBlocking {
            val detailedManga =
                source
                    .getMangaUpdate(
                        manga = mangaData.toSManga(source),
                        chapters = emptyList(),
                        fetchDetails = true,
                        fetchChapters = false,
                    ).manga
            MihonMetadataCache.remember(source, detailedManga)
            detailedManga.toJManga()
        }
    }

    private fun invokeGetMangaUrl(
        source: CatalogueSource,
        mangaData: MangaData?,
    ): String {
        if (mangaData == null) {
            throw IllegalArgumentException("mangaData is required for getMangaUrl")
        }
        if (source !is HttpSource) {
            throw IllegalArgumentException("Source must be HttpSource for getMangaUrl")
        }
        return source.getMangaUrl(mangaData.toSManga(source))
    }

    private fun invokeGetChapterList(
        source: CatalogueSource,
        mangaData: MangaData?,
    ): List<JChapter> {
        if (mangaData == null) {
            throw IllegalArgumentException("mangaData is required for getChapterList")
        }

        return runBlocking {
            val chapters =
                source
                    .getMangaUpdate(
                        manga = mangaData.toSManga(source),
                        chapters = emptyList(),
                        fetchDetails = false,
                        fetchChapters = true,
                    ).chapters
            chapters.map { chapter ->
                MihonMetadataCache.remember(source, chapter)
                chapter.toJChapter()
            }
        }
    }

    private fun invokeGetChapterUrl(
        source: CatalogueSource,
        chapterData: ChapterData?,
    ): String {
        if (chapterData == null) {
            throw IllegalArgumentException("chapterData is required for getChapterUrl")
        }
        if (source !is HttpSource) {
            throw IllegalArgumentException("Source must be HttpSource for getChapterUrl")
        }
        return source.getChapterUrl(chapterData.toSChapter(source))
    }

    private fun invokeGetPageList(
        source: CatalogueSource,
        chapterData: ChapterData?,
    ): List<JPage> {
        if (chapterData == null) {
            throw IllegalArgumentException("chapterData is required for getPageList")
        }

        return runBlocking {
            val sChapter = chapterData.toSChapter(source)
            val pages = source.getPageList(sChapter)
            val httpSource = source as? HttpSource

            val overridesFetchImage =
                if (httpSource != null) {
                    try {
                        val method =
                            httpSource.javaClass.getMethod(
                                "fetchImage",
                                Page::class.java,
                            )
                        method.declaringClass != HttpSource::class.java
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    false
                }
            val isSpecialSource = httpSource != null && httpSource.id.toString() == KL_RAW_SOURCE_ID
            val hasCustomInterceptors =
                if (httpSource != null) {
                    hasCustomClientInterceptors(httpSource)
                } else {
                    false
                }

            pages.map { page ->
                if (httpSource != null) {
                    val rawImageUrl = page.imageUrl
                    val isStandardHttpUrl =
                        rawImageUrl != null &&
                            (
                                rawImageUrl.startsWith("http://", ignoreCase = true) ||
                                    rawImageUrl.startsWith("https://", ignoreCase = true)
                            ) &&
                            !rawImageUrl.contains("#")

                    val useProxy =
                        overridesFetchImage ||
                            isSpecialSource ||
                            hasCustomInterceptors ||
                            !isStandardHttpUrl ||
                            hasSolvedClearanceForImage(httpSource, rawImageUrl)

                    if (useProxy) {
                        JPage(
                            index = page.index,
                            url = page.url,
                            imageUrl =
                                MihonImageProxy.register(httpSource, page)
                                    ?: run {
                                        if (page.imageUrl == null) {
                                            runCatching { page.imageUrl = httpSource.getImageUrl(page) }
                                        }
                                        httpSource.imageRequest(page).url.toString()
                                    },
                        )
                    } else {
                        val request = httpSource.imageRequest(page)
                        val directUrl = request.url.toString()
                        val reqHeaders = request.headers
                        val headersMap =
                            (0 until reqHeaders.size).associate {
                                reqHeaders.name(it) to reqHeaders.value(it)
                            }
                        JPage(
                            index = page.index,
                            url = page.url,
                            imageUrl = directUrl,
                            headers = if (headersMap.isNotEmpty()) headersMap else null,
                        )
                    }
                } else {
                    JPage(
                        index = page.index,
                        url = page.url,
                        imageUrl = page.imageUrl ?: page.url,
                    )
                }
            }
        }
    }

    private fun hasCustomClientInterceptors(httpSource: HttpSource): Boolean {
        val overridesClient =
            try {
                httpSource.javaClass.getMethod("getClient").declaringClass != HttpSource::class.java
            } catch (_: Exception) {
                false
            }
        if (!overridesClient) return false

        return runCatching {
            val defaultClient = runCatching { httpSource.network.client }.getOrNull()
            val sourceClient = httpSource.client
            if (defaultClient != null) {
                sourceClient.interceptors != defaultClient.interceptors ||
                    sourceClient.networkInterceptors != defaultClient.networkInterceptors
            } else {
                sourceClient.interceptors.isNotEmpty() ||
                    sourceClient.networkInterceptors.isNotEmpty()
            }
        }.getOrDefault(true)
    }

    private fun hasSolvedClearanceForImage(
        httpSource: HttpSource,
        rawImageUrl: String?,
    ): Boolean =
        runCatching {
            val net = httpSource.network
            val baseHost =
                runCatching {
                    okhttp3.HttpUrl.Companion.run { httpSource.baseUrl.toHttpUrl().host }
                }.getOrNull()
            val imageHost =
                rawImageUrl?.let {
                    runCatching {
                        okhttp3.HttpUrl.Companion.run { it.toHttpUrl().host }
                    }.getOrNull()
                }
            (baseHost != null && net.hasSolvedClearance(baseHost)) ||
                (imageHost != null && net.hasSolvedClearance(imageHost))
        }.getOrDefault(false)

    private fun invokeHeadersAnime(source: AnimeCatalogueSource): List<String> =
        if (source is AnimeHttpSource) {
            val headers =
                source.headers.toMultimap().flatMap { (name, values) ->
                    values.flatMap { value ->
                        listOf(name.replaceFirstChar { it.uppercase() }, value)
                    }
                }
            headers
        } else {
            emptyList()
        }

    private fun invokeFiltersAnime(source: AnimeCatalogueSource): AnimeFilterList {
        val filterList = source.getFilterList()
        return filterList
    }

    private fun invokeSupportLatestAnime(source: AnimeCatalogueSource): Boolean = source.supportsLatest

    private fun invokeGetPopularAnime(
        source: AnimeCatalogueSource,
        page: Int,
    ): AnimeResponse =
        runBlocking {
            val animesPage = source.getPopularAnime(page)
            AnimeResponse(
                animes = animesPage.animes.map { it.toJAnime() },
                hasNextPage = animesPage.hasNextPage,
            )
        }

    private fun invokeGetLatestAnime(
        source: AnimeCatalogueSource,
        page: Int,
    ): AnimeResponse =
        runBlocking {
            val animesPage = source.getLatestUpdates(page)
            AnimeResponse(
                animes = animesPage.animes.map { it.toJAnime() },
                hasNextPage = animesPage.hasNextPage,
            )
        }

    private fun invokeGetSearchAnime(
        source: AnimeCatalogueSource,
        page: Int,
        search: String,
        filterList: List<JFilterList>?,
    ): AnimeResponse =
        runBlocking {
            val convertedFilters = filterList?.let { convertAnimeFilterList(source.getFilterList(), it) } ?: source.getFilterList()
            val animePage = source.getSearchAnime(page, search, convertedFilters)
            AnimeResponse(
                animes = animePage.animes.map { it.toJAnime() },
                hasNextPage = animePage.hasNextPage,
            )
        }

    private fun invokeGetDetailsAnime(
        source: AnimeCatalogueSource,
        animeData: AnimeData?,
    ): JAnime {
        if (animeData == null) {
            throw IllegalArgumentException("animeData is required for getDetailsAnime")
        }

        if (source !is AnimeHttpSource) {
            throw IllegalArgumentException("Source must be AnimeHttpSource for getDetailsAnime")
        }

        val sAnime = animeData.toSAnime()
        return runBlocking {
            val detailedAnime =
                try {
                    source.getAnimeDetails(sAnime)
                } catch (_: UnsupportedOperationException) {
                    source.getAnimeEpisodeUpdate(sAnime, emptyList(), fetchDetails = true, fetchEpisodes = false).anime
                } catch (_: NotImplementedError) {
                    source.getAnimeEpisodeUpdate(sAnime, emptyList(), fetchDetails = true, fetchEpisodes = false).anime
                }
            detailedAnime.toJAnime()
        }
    }

    private fun invokeGetAnimeUrl(
        source: AnimeCatalogueSource,
        animeData: AnimeData?,
    ): String {
        if (animeData == null) {
            throw IllegalArgumentException("animeData is required for getAnimeUrl")
        }
        if (source !is AnimeHttpSource) {
            throw IllegalArgumentException("Source must be AnimeHttpSource for getAnimeUrl")
        }
        return source.getAnimeUrl(animeData.toSAnime())
    }

    private fun invokeGetEpisodeList(
        source: AnimeCatalogueSource,
        animeData: AnimeData?,
    ): List<JEpisode> {
        if (animeData == null) {
            throw IllegalArgumentException("animeData is required for getEpisodeList")
        }

        if (source !is AnimeHttpSource) {
            throw IllegalArgumentException("Source must be AnimeHttpSource for getEpisodeList")
        }

        val sAnime = animeData.toSAnime()
        return runBlocking {
            val episodes = fetchEpisodesWithSeasons(source, sAnime)
            episodes.map { it.toJEpisode() }
        }
    }

    private suspend fun fetchEpisodesWithSeasons(
        source: AnimeHttpSource,
        sAnime: SAnime,
        depth: Int = 0,
    ): List<SEpisode> {
        if (depth < 2 && sAnime.fetch_type == FetchType.Seasons && checkHasSeasons(source)) {
            val seasons =
                runCatching { source.getSeasonList(sAnime) }
                    .getOrDefault(emptyList())
            if (seasons.isNotEmpty()) {
                return flattenSeasonEpisodes(source, seasons, depth)
            }
        }

        return try {
            source.getEpisodeList(sAnime)
        } catch (_: UnsupportedOperationException) {
            if (depth < 2 && checkHasSeasons(source)) {
                val seasons = source.getSeasonList(sAnime)
                flattenSeasonEpisodes(source, seasons, depth)
            } else {
                source.getAnimeEpisodeUpdate(sAnime, emptyList(), fetchDetails = false, fetchEpisodes = true).episodes
            }
        } catch (_: NotImplementedError) {
            if (depth < 2 && checkHasSeasons(source)) {
                val seasons = source.getSeasonList(sAnime)
                flattenSeasonEpisodes(source, seasons, depth)
            } else {
                source.getAnimeEpisodeUpdate(sAnime, emptyList(), fetchDetails = false, fetchEpisodes = true).episodes
            }
        }
    }

    private suspend fun flattenSeasonEpisodes(
        source: AnimeHttpSource,
        seasons: List<SAnime>,
        depth: Int,
    ): List<SEpisode> =
        seasons.flatMap { season ->
            val seasonTitle = runCatching { season.title.trim() }.getOrDefault("")
            val seasonEpisodes =
                runCatching { fetchEpisodesWithSeasons(source, season, depth + 1) }
                    .getOrDefault(emptyList())
            seasonEpisodes.map { episode ->
                if (seasonTitle.isNotEmpty() && !episode.name.contains(seasonTitle, ignoreCase = true)) {
                    episode.apply { name = "$seasonTitle - $name" }
                } else {
                    episode
                }
            }
        }

    private fun invokeGetEpisodeUrl(
        source: AnimeCatalogueSource,
        episodeData: EpisodeData?,
    ): String {
        if (episodeData == null) {
            throw IllegalArgumentException("episodeData is required for getEpisodeUrl")
        }
        if (source !is AnimeHttpSource) {
            throw IllegalArgumentException("Source must be AnimeHttpSource for getEpisodeUrl")
        }
        return source.getEpisodeUrl(episodeData.toSEpisode())
    }

    private fun checkHasHosters(source: AnimeHttpSource): Boolean {
        var current: Class<in AnimeHttpSource> = source.javaClass
        while (true) {
            if (current == ParsedAnimeHttpSource::class.java ||
                current == AnimeHttpSource::class.java ||
                current == AnimeSource::class.java
            ) {
                return false
            }
            if (current.declaredMethods.any {
                    it.name in
                        listOf(
                            "getHosterList",
                            "hosterListRequest",
                            "hosterListParse",
                            "hosterListSelector",
                            "hosterFromElement",
                        )
                }
            ) {
                return true
            }
            current = current.superclass ?: return false
        }
    }

    private fun checkHasSeasons(source: AnimeHttpSource): Boolean {
        var current: Class<in AnimeHttpSource> = source.javaClass
        while (true) {
            if (current == ParsedAnimeHttpSource::class.java ||
                current == AnimeHttpSource::class.java ||
                current == AnimeSource::class.java
            ) {
                return false
            }
            if (current.declaredMethods.any {
                    it.name in
                        listOf(
                            "getSeasonList",
                            "seasonListRequest",
                            "seasonListParse",
                            "seasonListSelector",
                            "seasonFromElement",
                        )
                }
            ) {
                return true
            }
            current = current.superclass ?: return false
        }
    }

    private fun MangaData.toSManga(source: Source): SManga =
        SManga.create().also { manga ->
            val decodedUrl = BridgeMemo.decode(url ?: "")
            manga.url = decodedUrl.url
            manga.memo = decodedUrl.memo
            manga.title = title ?: ""
            manga.artist = artist
            manga.author = author
            manga.description = description
            manga.genre = genre
            manga.status = status ?: 0
            manga.thumbnail_url = thumbnail_url
            manga.initialized = initialized ?: false
            MihonMetadataCache.restore(source, manga)
        }

    private fun ChapterData.toSChapter(source: Source): SChapter =
        SChapter.create().also { chapter ->
            val decodedUrl = BridgeMemo.decode(url ?: "")
            chapter.url = decodedUrl.url
            chapter.memo = decodedUrl.memo
            chapter.name = name ?: ""
            chapter.date_upload = date_upload ?: 0L
            chapter.chapter_number = chapter_number ?: 0f
            chapter.scanlator = scanlator
            MihonMetadataCache.restore(source, chapter)
        }

    private fun AnimeData.toSAnime(): SAnime =
        SAnime.create().also { anime ->
            val decodedUrl = BridgeMemo.decode(url ?: "")
            anime.url = decodedUrl.url
            anime.memo = decodedUrl.memo
            anime.title = title ?: ""
            anime.artist = artist
            anime.author = author
            anime.description = description
            anime.genre = genre
            anime.status = status ?: 0
            anime.thumbnail_url = thumbnail_url
            anime.initialized = initialized ?: false
        }

    private fun EpisodeData.toSEpisode(): SEpisode =
        SEpisode.create().also { episode ->
            val decodedUrl = BridgeMemo.decode(url ?: "")
            episode.url = decodedUrl.url
            episode.memo = decodedUrl.memo
            episode.name = name ?: ""
            episode.date_upload = date_upload ?: 0L
            episode.episode_number = episode_number ?: 0f
            episode.scanlator = scanlator
        }

    private fun invokeGetVideoList(
        source: AnimeCatalogueSource,
        episodeData: EpisodeData?,
    ): List<Video> {
        if (episodeData == null) {
            throw IllegalArgumentException("episodeData is required for getVideoList")
        }

        if (source !is AnimeHttpSource) {
            throw IllegalArgumentException("Source must be AnimeHttpSource for getVideoList")
        }

        val sEpisode = episodeData.toSEpisode()
        return runBlocking {
            val hosters: List<Hoster> =
                if (checkHasHosters(source)) {
                    try {
                        with(source) { source.getHosterList(sEpisode).sortHosters() }
                    } catch (_: UnsupportedOperationException) {
                        with(source) { source.getVideoList(sEpisode).sortVideos().toHosterList() }
                    } catch (_: NotImplementedError) {
                        with(source) { source.getVideoList(sEpisode).sortVideos().toHosterList() }
                    }
                } else {
                    try {
                        with(source) { source.getVideoList(sEpisode).sortVideos().toHosterList() }
                    } catch (_: UnsupportedOperationException) {
                        with(source) { source.getHosterList(sEpisode).sortHosters() }
                    } catch (_: NotImplementedError) {
                        with(source) { source.getHosterList(sEpisode).sortHosters() }
                    }
                }

            val hosterScope = CoroutineScope(Dispatchers.IO)
            val results =
                hosters
                    .map { hoster ->
                        hosterScope.async {
                            runCatching {
                                val rawVideos =
                                    hoster.videoList
                                        ?: source.getVideoList(hoster)
                                val sortedVideos = with(source) { rawVideos.sortVideos() }
                                sortedVideos.mapNotNull { video ->
                                    resolveSingleVideo(source, hoster.hosterName, video)
                                }
                            }
                        }
                    }.map { deferred ->
                        withTimeoutOrNull(HOSTER_TIMEOUT_MS) { deferred.await() }
                            ?: Result.failure(TimeoutException("Hoster timed out after ${HOSTER_TIMEOUT_MS}ms"))
                    }
            hosterScope.cancel()
            val videos =
                results
                    .flatMap { it.getOrDefault(emptyList()) }
                    .sortedByDescending { it.preferred }
            if (videos.isEmpty()) {
                results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
            }
            videos
        }
    }

    private suspend fun resolveSingleVideo(
        source: AnimeHttpSource,
        hosterName: String,
        video: Video,
    ): Video? {
        val resolved =
            if (!video.initialized) {
                try {
                    source.resolveVideo(video) ?: return null
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    video
                }
            } else {
                video
            }

        val currentVideoUrl = resolved.videoUrl
        val withUrl =
            if (currentVideoUrl.isNullOrEmpty() || currentVideoUrl == "null" || resolved.status == Video.LOAD_VIDEO) {
                if (resolved.url.isNotEmpty() && resolved.url != "null") {
                    runCatching {
                        val fetchedUrl = source.getVideoUrl(resolved)
                        resolved.copy(videoUrl = fetchedUrl, initialized = true).also {
                            it.status = Video.READY
                        }
                    }.getOrDefault(resolved)
                } else {
                    resolved
                }
            } else {
                resolved
            }

        val finalUrl = withUrl.videoUrl?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        val withServerPort =
            if (withUrl.usesHttpServer()) {
                val port = ensureHttpServerPort(source)
                if (port > 0) withUrl.copyHttpServer(port) else withUrl
            } else {
                withUrl
            }

        val formattedQuality = formatVideoQuality(hosterName, withServerPort.quality)
        val effectiveVideoUrl = withServerPort.videoUrl ?: finalUrl
        val originalUrl = withServerPort.url.takeIf { it.isNotBlank() && it != "null" } ?: effectiveVideoUrl
        return Video(
            url = originalUrl,
            quality = formattedQuality,
            videoUrl = effectiveVideoUrl,
            headers = withServerPort.headers,
            subtitleTracks = withServerPort.subtitleTracks,
            audioTracks = withServerPort.audioTracks,
            timestamps = withServerPort.timestamps,
        ).also {
            it.resolution = withServerPort.resolution
            it.bitrate = withServerPort.bitrate
            it.preferred = withServerPort.preferred
            it.mpvArgs = withServerPort.mpvArgs
            it.ffmpegStreamArgs = withServerPort.ffmpegStreamArgs
            it.ffmpegVideoArgs = withServerPort.ffmpegVideoArgs
            it.internalData = withServerPort.internalData
            it.initialized = true
            it.memo = withServerPort.memo
            it.status = Video.READY
        }
    }

    private fun formatVideoQuality(
        hosterName: String,
        quality: String,
    ): String {
        val cleanHoster = hosterName.trim()
        val cleanQuality = quality.trim()
        if (cleanHoster.isEmpty() || cleanHoster == Hoster.NO_HOSTER_LIST) {
            return cleanQuality.ifEmpty { "Default" }
        }
        if (cleanQuality.isEmpty()) {
            return cleanHoster
        }
        if (cleanQuality.contains(cleanHoster, ignoreCase = true)) {
            return cleanQuality
        }
        return "$cleanHoster - $cleanQuality"
    }

    private fun ensureHttpServerPort(source: AnimeHttpSource): Int =
        runCatching {
            val server =
                sourceHttpServers.compute(source.id) { _, existing ->
                    if (existing != null && existing.isRunning()) {
                        existing
                    } else {
                        existing?.runCatching { stop() }
                        source.createHttpServer()?.also { it.start() }
                    }
                }
            server?.listeningPort ?: 0
        }.getOrDefault(0)

    private fun invokePreferencesAnime(source: AnimeCatalogueSource): MutableList<Map<String, Any>> {
        val preferences = mutableListOf<Map<String, Any>>()
        if (source !is ConfigurableAnimeSource) {
            return preferences
        }

        val preferenceManager: PreferenceManager? = preferenceManager
        val instance: MExtensionServerPlugin? = instance
        val context = instance?.applicationContext ?: return preferences
        preferenceManager?.sharedPreferencesName = "source_${source.id}"
        val screen = preferenceManager!!.createPreferenceScreen(context)
        (source as ConfigurableAnimeSource).setupPreferenceScreen(screen)
        processPreferences(screen, preferences)

        return preferences
    }

    private fun invokePreferencesManga(source: CatalogueSource): MutableList<Map<String, Any>> {
        val preferences = mutableListOf<Map<String, Any>>()
        if (source !is ConfigurableSource) {
            return preferences
        }
        val preferenceManager: PreferenceManager? = preferenceManager
        val instance: MExtensionServerPlugin? = instance
        val context = instance?.applicationContext ?: return preferences
        preferenceManager?.sharedPreferencesName = "source_${source.id}"
        val screen = preferenceManager!!.createPreferenceScreen(context)
        (source as ConfigurableSource).setupPreferenceScreen(screen)
        processPreferences(screen, preferences)

        return preferences
    }

    private fun invokeSetPreferenceAnime(
        source: AnimeCatalogueSource,
        data: DataBody,
    ): MutableList<Map<String, Any>> {
        if (source !is ConfigurableAnimeSource) return mutableListOf()
        return invokeSetPreference(source.id, data, source::setupPreferenceScreen)
    }

    private fun invokeSetPreferenceManga(
        source: CatalogueSource,
        data: DataBody,
    ): MutableList<Map<String, Any>> {
        if (source !is ConfigurableSource) return mutableListOf()
        return invokeSetPreference(source.id, data, source::setupPreferenceScreen)
    }

    private fun invokeSetPreference(
        sourceId: Long,
        data: DataBody,
        setup: (PreferenceScreen) -> Unit,
    ): MutableList<Map<String, Any>> {
        val preferenceManager: PreferenceManager? = preferenceManager
        val instance: MExtensionServerPlugin? = instance
        val context = instance?.applicationContext ?: return mutableListOf()
        preferenceManager?.sharedPreferencesName = "source_$sourceId"
        val screen = preferenceManager!!.createPreferenceScreen(context)
        setup(screen)

        val preferences = mutableListOf<Map<String, Any>>()
        processPreferences(screen, preferences)
        return preferences
    }

    private fun processPreferences(
        screen: PreferenceScreen,
        preferences: MutableList<Map<String, Any>>,
    ) {
        for (i in 0 until screen.size) {
            val preference = screen.getPreference(i)
            when (preference) {
                is CheckBoxPreference -> {
                    preferences.add(
                        mapOf(
                            "key" to preference.key,
                            "checkBoxPreference" to
                                mapOf(
                                    "title" to preference.title,
                                    "summary" to (preference.summary ?: ""),
                                    "value" to preference.isChecked,
                                ),
                        ),
                    )
                }
                is SwitchPreferenceCompat -> {
                    preferences.add(
                        mapOf(
                            "key" to preference.key,
                            "switchPreferenceCompat" to
                                mapOf(
                                    "title" to preference.title,
                                    "summary" to (preference.summary ?: ""),
                                    "value" to preference.isChecked,
                                ),
                        ),
                    )
                }
                is EditTextPreference -> {
                    preferences.add(
                        mapOf(
                            "key" to preference.key,
                            "editTextPreference" to
                                mapOf(
                                    "title" to preference.title,
                                    "summary" to (preference.summary ?: ""),
                                    "value" to preference.text,
                                    "text" to preference.text,
                                    "dialogMessage" to preference.getDialogMessage(),
                                    "dialogTitle" to preference.getDialogTitle(),
                                ),
                        ),
                    )
                }
                is ListPreference -> {
                    preferences.add(
                        mapOf(
                            "key" to preference.key,
                            "listPreference" to
                                mapOf(
                                    "title" to preference.title,
                                    "summary" to (preference.summary ?: ""),
                                    "valueIndex" to preference.entryValues.indexOf(preference.value),
                                    "entries" to preference.entries,
                                    "entryValues" to preference.entryValues,
                                ),
                        ),
                    )
                }
                is MultiSelectListPreference -> {
                    preferences.add(
                        mapOf(
                            "key" to preference.key,
                            "multiSelectListPreference" to
                                mapOf(
                                    "title" to preference.title,
                                    "summary" to (preference.summary ?: ""),
                                    "values" to preference.values,
                                    "entries" to preference.entries,
                                    "entryValues" to preference.entryValues,
                                ),
                        ),
                    )
                }
            }
        }
    }

    private fun applyPreferences(
        data: DataBody,
        source: Any,
    ) {
        val preferences = data.preferences ?: return
        val sourceId =
            when (source) {
                is CatalogueSource -> source.id
                is AnimeSource -> source.id
                else -> throw IllegalArgumentException("Unknown source type: ${source.javaClass}")
            }
        val context: Context = instance!!.applicationContext!!
        val prefs = context.getSharedPreferences("source_$sourceId", 0x0000)
        prefs.edit {
            for (prefMap in preferences) {
                val key = prefMap["key"] as? String ?: continue
                when {
                    prefMap.containsKey("checkBoxPreference") -> {
                        val checkBox = prefMap["checkBoxPreference"] as? Map<*, *>
                        val value = checkBox?.get("value") as? Boolean
                        if (value != null) {
                            putBoolean(key, value)
                        }
                    }

                    prefMap.containsKey("switchPreferenceCompat") -> {
                        val switch = prefMap["switchPreferenceCompat"] as? Map<*, *>
                        val value = switch?.get("value") as? Boolean
                        if (value != null) {
                            putBoolean(key, value)
                        }
                    }

                    prefMap.containsKey("editTextPreference") -> {
                        val editText = prefMap["editTextPreference"] as? Map<*, *>
                        val value = editText?.get("value") as? String
                        if (value != null) {
                            putString(key, value)
                        }
                    }

                    prefMap.containsKey("listPreference") -> {
                        val list = prefMap["listPreference"] as? Map<*, *>
                        val valueIndex = list?.get("valueIndex") as? Int
                        val entryValues = list?.get("entryValues") as? List<String>
                        if (valueIndex != null && entryValues != null && valueIndex in entryValues.indices) {
                            putString(key, entryValues[valueIndex])
                        }
                    }

                    prefMap.containsKey("multiSelectListPreference") -> {
                        val multiList = prefMap["multiSelectListPreference"] as? Map<*, *>
                        val values = multiList?.get("values") as? List<String>
                        if (values != null) {
                            putStringSet(key, values.toSet())
                        }
                    }
                }
            }
        }
    }
    private fun convertFilterList(
        originalFilters: FilterList,
        jFilters: List<JFilterList>,
    ): FilterList {
        // Apply the filter values from jFilters to the original filters
        val convertedFilters =
            originalFilters.map { filter ->
                when (filter) {
                    is Filter.Select<*> -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null) {
                            val state = jFilter.stateInt
                            if (state != null) {
                                filter.state = state
                            }
                        }
                        filter
                    }
                    is Filter.CheckBox -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null &&
                            jFilter.stateList != null &&
                            jFilter.stateList.isNotEmpty() &&
                            jFilter.stateList[0].stateBoolean is Boolean
                        ) {
                            filter.state = jFilter.stateList[0].stateBoolean as Boolean
                        }
                        filter
                    }
                    is Filter.TriState -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null && jFilter.stateInt is Int) {
                            filter.state = jFilter.stateInt
                        }
                        filter
                    }
                    is Filter.Text -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null && jFilter.stateString is String) {
                            filter.state = jFilter.stateString
                        }
                        filter
                    }
                    is Filter.Group<*> -> {
                        val groupFilter = filter as Filter.Group<Filter<*>>
                        val jGroup = jFilters.find { it.name == groupFilter.name }
                        if (jGroup != null) {
                            val subJFilters = jGroup.stateList
                            for (state in groupFilter.state) {
                                val jSubFilter = subJFilters?.find { it.name == state.name }
                                val dataGroup = jSubFilter
                                if (dataGroup == null) {
                                    continue
                                }
                                if (state is Filter.CheckBox) {
                                    val checkBox = state
                                    checkBox.state = dataGroup.stateBoolean ?: false
                                }
                                if (state is Filter.TriState) {
                                    val checkBox = state
                                    checkBox.state = dataGroup.stateInt ?: 0
                                }
                                if (state is Filter.Select<*>) {
                                    val select = state
                                    select.state = dataGroup.stateInt ?: 0
                                }
                                if (state is Filter.Text) {
                                    val text = state
                                    text.state = dataGroup.name ?: ""
                                }
                            }
                        }
                        filter
                    }
                    is Filter.Sort -> {
                        val jSort = jFilters.find { it.name == filter.name }
                        if (jSort != null) {
                            val dataSort = jSort.stateSort
                            if (dataSort != null && dataSort.index != null && dataSort.ascending != null) {
                                filter.state = Filter.Sort.Selection(dataSort.index, dataSort.ascending)
                            }
                        }
                        filter
                    }
                    else -> filter
                }
            }
        return FilterList(convertedFilters)
    }

    private fun convertAnimeFilterList(
        originalFilters: AnimeFilterList,
        jFilters: List<JFilterList>,
    ): AnimeFilterList {
        // Apply the filter values from jFilters to the original filters
        val convertedFilters =
            originalFilters.map { filter ->
                when (filter) {
                    is AnimeFilter.Select<*> -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null) {
                            val state = jFilter.stateInt as? Int
                            if (state != null) {
                                filter.state = state
                            }
                        }
                        filter
                    }
                    is AnimeFilter.CheckBox -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null &&
                            jFilter.stateList != null &&
                            jFilter.stateList.isNotEmpty() &&
                            jFilter.stateList[0].stateBoolean is Boolean
                        ) {
                            filter.state = jFilter.stateList[0].stateBoolean as Boolean
                        }
                        filter
                    }
                    is AnimeFilter.TriState -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null && jFilter.stateInt is Int) {
                            filter.state = jFilter.stateInt as Int
                        }
                        filter
                    }
                    is AnimeFilter.Text -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null && jFilter.stateString is String) {
                            filter.state = jFilter.stateString
                        }
                        filter
                    }
                    is AnimeFilter.Group<*> -> {
                        val groupFilter = filter as AnimeFilter.Group<AnimeFilter<*>>
                        val jGroup = jFilters.find { it.name == groupFilter.name }
                        if (jGroup != null) {
                            val subJFilters = jGroup.stateList
                            for (state in groupFilter.state) {
                                val jSubFilter = subJFilters?.find { it.name == state.name }
                                val dataGroup = jSubFilter
                                if (dataGroup == null) {
                                    continue
                                }
                                if (state is AnimeFilter.CheckBox) {
                                    val checkBox = state
                                    checkBox.state = dataGroup.stateBoolean ?: false
                                }
                                if (state is AnimeFilter.TriState) {
                                    val checkBox = state
                                    checkBox.state = dataGroup.stateInt ?: 0
                                }
                                if (state is AnimeFilter.Select<*>) {
                                    val select = state
                                    select.state = dataGroup.stateInt ?: 0
                                }
                                if (state is AnimeFilter.Text) {
                                    val text = state
                                    text.state = dataGroup.name ?: ""
                                }
                            }
                        }
                        filter
                    }
                    is AnimeFilter.Sort -> {
                        val jSort = jFilters.find { it.name == filter.name }
                        if (jSort != null) {
                            val dataSort = jSort.stateSort
                            if (dataSort != null && dataSort.index != null && dataSort.ascending != null) {
                                filter.state =
                                    AnimeFilter.Sort
                                        .Selection(dataSort.index, dataSort.ascending)
                            }
                        }
                        filter
                    }
                    else -> filter
                }
            }
        return AnimeFilterList(convertedFilters)
    }
}
