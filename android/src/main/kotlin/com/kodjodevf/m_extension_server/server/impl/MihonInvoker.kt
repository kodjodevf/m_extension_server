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
import m_extension_server.model.JFilterList
import m_extension_server.model.JManga
import m_extension_server.model.JPage
import m_extension_server.model.MangaData
import m_extension_server.model.MangaResponse
import m_extension_server.model.toJAnime
import m_extension_server.model.toJManga
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import com.kodjodevf.m_extension_server.MExtensionServerPlugin
import com.kodjodevf.m_extension_server.instance
import com.kodjodevf.m_extension_server.preferenceManager

object MihonInvoker {

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
            "getChapterList" -> invokeGetChapterList(source as CatalogueSource, data.mangaData)
            "getPageList" -> invokeGetPageList(source as CatalogueSource, data.chapterData)
            "preferencesManga" -> invokePreferencesManga(source as CatalogueSource)
            "headersAnime" -> invokeHeadersAnime(source as AnimeCatalogueSource)
            "filtersAnime" -> invokeFiltersAnime(source as AnimeCatalogueSource)
            "supportLatestAnime" -> invokeSupportLatestAnime(source as AnimeCatalogueSource)
            "getPopularAnime" -> invokeGetPopularAnime(source as AnimeCatalogueSource, data.page ?: 1)
            "getLatestAnime" -> invokeGetLatestAnime(source as AnimeCatalogueSource, data.page ?: 1)
            "getSearchAnime" -> invokeGetSearchAnime(source as AnimeCatalogueSource, data.page ?: 1, data.search ?: "", data.filterList)
            "getDetailsAnime" -> invokeGetDetailsAnime(source as AnimeCatalogueSource, data.animeData)
            "getEpisodeList" -> invokeGetEpisodeList(source as AnimeCatalogueSource, data.animeData)
            "getVideoList" -> invokeGetVideoList(source as AnimeCatalogueSource, data.episodeData)
            "preferencesAnime" -> invokePreferencesAnime(source as AnimeCatalogueSource)
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
                mangas = mangasPage.mangas.map { it.toJManga() },
                hasNextPage = mangasPage.hasNextPage,
            )
        }

    private fun invokeGetLatestManga(
        source: CatalogueSource,
        page: Int,
    ): MangaResponse =
        runBlocking {
            val mangasPage = source.getLatestUpdates(page)
            MangaResponse(
                mangas = mangasPage.mangas.map { it.toJManga() },
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
                mangas = mangasPage.mangas.map { it.toJManga() },
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

        if (source !is HttpSource) {
            throw IllegalArgumentException("Source must be HttpSource for getDetailsManga")
        }

        val sManga =
            SManga.create().apply {
                url = mangaData.url ?: ""
                title = mangaData.title ?: ""
                artist = mangaData.artist
                author = mangaData.author
                description = mangaData.description
                genre = mangaData.genre
                status = mangaData.status ?: 0
                thumbnail_url = mangaData.thumbnail_url
                initialized = mangaData.initialized ?: false
            }

        return runBlocking {
            val detailedManga = source.getMangaDetails(sManga)
            detailedManga.toJManga()
        }
    }

    private fun invokeGetChapterList(
        source: CatalogueSource,
        mangaData: MangaData?,
    ): List<SChapter> {
        if (mangaData == null) {
            throw IllegalArgumentException("mangaData is required for getChapterList")
        }

        if (source !is HttpSource) {
            throw IllegalArgumentException("Source must be HttpSource for getChapterList")
        }

        val sManga =
            SManga.create().apply {
                url = mangaData.url ?: ""
                title = mangaData.title ?: ""
                artist = mangaData.artist
                author = mangaData.author
                description = mangaData.description
                genre = mangaData.genre
                status = mangaData.status ?: 0
                thumbnail_url = mangaData.thumbnail_url
                initialized = mangaData.initialized ?: false
            }

        return runBlocking {
            val chapters = source.getChapterList(sManga)
            chapters
        }
    }

    private fun invokeGetPageList(
        source: CatalogueSource,
        chapterData: ChapterData?,
    ): List<JPage> {
        if (chapterData == null) {
            throw IllegalArgumentException("chapterData is required for getPageList")
        }

        if (source !is HttpSource) {
            throw IllegalArgumentException("Source must be HttpSource for getPageList")
        }

        val sChapter =
            SChapter.create().apply {
                url = chapterData.url ?: ""
                name = chapterData.name ?: ""
                date_upload = chapterData.date_upload ?: 0L
                chapter_number = chapterData.chapter_number ?: 0f
                scanlator = chapterData.scanlator
            }

        return runBlocking {
            val pages = source.getPageList(sChapter)
            pages.map { page ->
                JPage(
                    index = page.index,
                    url = page.url,
                    imageUrl = source.imageRequest(page).url.toString(),
                )
            }
        }
    }

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

        val sAnime =
            SAnime.create().apply {
                url = animeData.url ?: ""
                title = animeData.title ?: ""
                artist = animeData.artist
                author = animeData.author
                description = animeData.description
                genre = animeData.genre
                status = animeData.status ?: 0
                thumbnail_url = animeData.thumbnail_url
                initialized = animeData.initialized ?: false
            }

        return runBlocking {
            val detailedAnime = source.getAnimeDetails(sAnime)
            detailedAnime.toJAnime()
        }
    }

    private fun invokeGetEpisodeList(
        source: AnimeCatalogueSource,
        animeData: AnimeData?,
    ): List<SEpisode> {
        if (animeData == null) {
            throw IllegalArgumentException("animeData is required for getEpisodeList")
        }

        if (source !is AnimeHttpSource) {
            throw IllegalArgumentException("Source must be AnimeHttpSource for getEpisodeList")
        }

        val sAnime =
            SAnime.create().apply {
                url = animeData.url ?: ""
                title = animeData.title ?: ""
                artist = animeData.artist
                author = animeData.author
                description = animeData.description
                genre = animeData.genre
                status = animeData.status ?: 0
                thumbnail_url = animeData.thumbnail_url
                initialized = animeData.initialized ?: false
            }

        return runBlocking {
            val episodes = source.getEpisodeList(sAnime)
            episodes
        }
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

        val sEpisode =
            SEpisode.create().apply {
                url = episodeData.url ?: ""
                name = episodeData.name ?: ""
                date_upload = episodeData.date_upload ?: 0L
                episode_number = episodeData.episode_number ?: 0f
                scanlator = episodeData.scanlator
            }

        return runBlocking {
            val videos = source.getVideoList(sEpisode)
            videos
        }
    }

    private fun invokePreferencesAnime(source: AnimeCatalogueSource): MutableList<Map<String, Any>> {
        val preferences = mutableListOf<Map<String, Any>>()
        if (source !is ConfigurableAnimeSource) {
            return preferences
        }

        val preferenceManager: PreferenceManager? = preferenceManager
        val instance: MExtensionServerPlugin? = instance
        val screen = preferenceManager!!.createPreferenceScreen(instance!!.applicationContext!!)
        (source as ConfigurableSource).setupPreferenceScreen(screen)
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
        val screen = preferenceManager!!.createPreferenceScreen(instance!!.applicationContext!!)
        (source as ConfigurableSource).setupPreferenceScreen(screen)
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
                        val groupFilter = filter as AnimeFilter.Group<Filter<*>>
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
