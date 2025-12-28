package org.koitharu.kotatsu.parsers.site.all

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

internal abstract class WeebDexParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    private val lang: String,
) : PagedMangaParser(context, source, pageSize = 42) {

    override val configKeyDomain = ConfigKey.Domain("weebdex.org")
    private val apiUrl = "https://api.weebdex.org/"
    private val coverCdnUrl = "https://srv.notdelta.xyz/"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.RELEVANCE,
        SortOrder.RATING
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tagsJson = webClient.httpGet("${apiUrl}manga/tag").parseJson()
        val tagsArray = tagsJson.getJSONArray("data")
        val tags = (0 until tagsArray.length()).map { i ->
            val tag = tagsArray.getJSONObject(i)
            val group = tag.optString("group", "")
            val name = tag.getString("name")
            val displayName = if (group.isNotEmpty()) "$name ($group)" else name
            MangaTag(
                key = tag.getString("id"),
                title = displayName,
                source = source
            )
        }.toSet()

        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
                MangaState.ABANDONED
            )
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "${apiUrl}manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("page", page.toString())

        // Sorting
        val isSearch = !filter.query.isNullOrEmpty()
        when (order) {
            SortOrder.UPDATED -> url.addQueryParameter("sort", "updatedAt")
            SortOrder.POPULARITY -> url.addQueryParameter("sort", "views")
            SortOrder.NEWEST -> url.addQueryParameter("sort", "createdAt")
            SortOrder.ALPHABETICAL -> url.addQueryParameter("sort", "title")
            SortOrder.RELEVANCE -> if (isSearch) url.addQueryParameter("sort", "relevance")
            SortOrder.RATING -> url.addQueryParameter("sort", "rating")
            else -> {}
        }

        // Search query
        if (isSearch) {
            url.addQueryParameter("title", filter.query)
        }

        // Tags
        if (filter.tags.isNotEmpty()) {
            filter.tags.forEach { tag ->
                url.addQueryParameter("tag", tag.key)
            }
        }

        // State
        filter.states.oneOrThrowIfMany()?.let { state ->
            when (state) {
                MangaState.ONGOING -> url.addQueryParameter("status", "ongoing")
                MangaState.FINISHED -> url.addQueryParameter("status", "completed")
                MangaState.PAUSED -> url.addQueryParameter("status", "hiatus")
                MangaState.ABANDONED -> url.addQueryParameter("status", "cancelled")
                else -> {}
            }
        }

        val json = webClient.httpGet(url.build().toString()).parseJson()
        val data = json.getJSONArray("data")

        // Filter by language - only show manga with chapters in this language
        val mangas = (0 until data.length()).mapNotNull { i ->
            val item = data.getJSONObject(i)
            parseManga(item)
        }.filter { manga ->
            // Keep all manga for now, language filtering happens in details/chapters
            true
        }

        return mangas
    }

    private fun parseManga(json: JSONObject): Manga? {
        val id = json.getString("id")
        val title = json.getString("title")

        // Get cover from relationships
        val relationships = json.optJSONObject("relationships")
        val coverObj = relationships?.optJSONObject("cover")
        val coverUrl = if (coverObj != null) {
            val coverId = coverObj.getString("id")
            val ext = coverObj.getString("ext")
            "${coverCdnUrl}covers/$id/$coverId$ext"
        } else null

        // Get tags from relationships
        val tagsArray = relationships?.optJSONArray("tags")
        val tags = if (tagsArray != null) {
            (0 until tagsArray.length()).mapNotNull { i ->
                val tagJson = tagsArray.getJSONObject(i)
                MangaTag(
                    key = tagJson.getString("id"),
                    title = tagJson.getString("name"),
                    source = source
                )
            }.toSet()
        } else emptySet()

        val statusStr = json.optString("status", "")
        val state = when (statusStr.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled" -> MangaState.ABANDONED
            else -> null
        }

        val contentRating = when (json.optString("content_rating").lowercase()) {
            "safe" -> ContentRating.SAFE
            "suggestive" -> ContentRating.SUGGESTIVE
            "nsfw", "erotica" -> ContentRating.ADULT
            else -> ContentRating.SAFE
        }

        return Manga(
            id = generateUid(id),
            url = "/manga/$id",
            publicUrl = "https://$domain/manga/$id",
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            tags = tags,
            authors = emptySet(),
            state = state,
            source = source,
            contentRating = contentRating
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val json = webClient.httpGet("${apiUrl}${manga.url}").parseJson()

        val description = json.optString("description")
        val statusStr = json.optString("status")
        val state = when (statusStr.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled" -> MangaState.ABANDONED
            else -> null
        }

        // Get relationships
        val relationships = json.optJSONObject("relationships")

        // Tags from relationships
        val tagsArray = relationships?.optJSONArray("tags")
        val tags = if (tagsArray != null) {
            (0 until tagsArray.length()).mapNotNull { i ->
                val tagJson = tagsArray.getJSONObject(i)
                MangaTag(
                    key = tagJson.getString("id"),
                    title = tagJson.getString("name"),
                    source = source
                )
            }.toSet()
        } else emptySet()

        // Authors - WeebDex might not have this in the response shown
        val authors = emptySet<String>()

        val contentRating = when (json.optString("content_rating").lowercase()) {
            "safe" -> ContentRating.SAFE
            "suggestive" -> ContentRating.SUGGESTIVE
            "nsfw", "erotica" -> ContentRating.ADULT
            else -> ContentRating.SAFE
        }

        // Cover from relationships
        val coverObj = relationships?.optJSONObject("cover")
        val coverUrl = if (coverObj != null) {
            val id = manga.url.substringAfterLast("/")
            val coverId = coverObj.getString("id")
            val ext = coverObj.getString("ext")
            "${coverCdnUrl}covers/$id/$coverId$ext"
        } else manga.coverUrl

        // Get chapters for this language
        val mangaId = manga.url.substringAfterLast("/")
        val chaptersJson = webClient.httpGet("${apiUrl}manga/$mangaId/chapters?lang=$lang&limit=500&order=desc").parseJson()
        val chapters = parseChapterList(chaptersJson, mangaId)

        return manga.copy(
            description = description,
            state = state,
            tags = tags,
            authors = authors,
            contentRating = contentRating,
            coverUrl = coverUrl,
            chapters = chapters
        )
    }

    private fun parseChapterList(json: JSONObject, mangaId: String): List<MangaChapter> {
        val data = json.getJSONArray("data")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        // Group chapters by chapter number to handle duplicates
        val chapterMap = mutableMapOf<String, MutableList<ChapterData>>()

        for (i in 0 until data.length()) {
            val chapter = data.getJSONObject(i)

            // Filter by language - only include chapters in the requested language
            val chapterLang = chapter.optString("language", "")
            if (chapterLang != lang) continue

            val chapterId = chapter.getString("id")
            val chapterNumber = chapter.optString("chapter", "0")
            val volumeNumber = chapter.optInt("volume", 0)
            val title = chapter.optString("title").takeIf { it.isNotEmpty() }
            val createdAt = chapter.optString("created_at")
            val version = chapter.optInt("version", 1)

            // Get scanlator from relationships
            val relationships = chapter.optJSONObject("relationships")
            val groupsArray = relationships?.optJSONArray("groups")
            val scanlator = if (groupsArray != null && groupsArray.length() > 0) {
                (0 until groupsArray.length()).joinToString(", ") { idx ->
                    groupsArray.getJSONObject(idx).optString("name")
                }
            } else null

            val date = try {
                dateFormat.parse(createdAt)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }

            // Create chapter data object
            val chapterData = ChapterData(
                id = chapterId,
                number = chapterNumber,
                volume = volumeNumber,
                title = title,
                uploadDate = date,
                scanlator = scanlator,
                version = version
            )

            // Group by chapter number
            val key = chapterNumber
            if (!chapterMap.containsKey(key)) {
                chapterMap[key] = mutableListOf()
            }
            chapterMap[key]!!.add(chapterData)
        }

        // Process grouped chapters
        val chapters = mutableListOf<MangaChapter>()

        for ((chapterNum, duplicates) in chapterMap) {
            if (duplicates.size == 1) {
                // Single version - just add it
                val ch = duplicates[0]
                chapters.add(
                    MangaChapter(
                        id = generateUid(ch.id),
                        title = ch.title,
                        number = ch.number.toFloatOrNull() ?: 0f,
                        volume = ch.volume,
                        url = "/manga/$mangaId/chapter/${ch.id}",
                        uploadDate = ch.uploadDate,
                        source = source,
                        scanlator = ch.scanlator,
                        branch = null
                    )
                )
            } else {
                // Multiple versions - prefer highest version number, or add as separate branches
                val sorted = duplicates.sortedWith(
                    compareByDescending<ChapterData> { it.version }
                        .thenByDescending { it.uploadDate }
                )

                // Add the best version as the main chapter
                val best = sorted[0]
                chapters.add(
                    MangaChapter(
                        id = generateUid(best.id),
                        title = best.title,
                        number = best.number.toFloatOrNull() ?: 0f,
                        volume = best.volume,
                        url = "/manga/$mangaId/chapter/${best.id}",
                        uploadDate = best.uploadDate,
                        source = source,
                        scanlator = best.scanlator,
                        branch = null
                    )
                )

                // Add other versions as alternate branches if they're from different groups
                for (j in 1 until sorted.size) {
                    val alt = sorted[j]
                    if (alt.scanlator != best.scanlator) {
                        chapters.add(
                            MangaChapter(
                                id = generateUid(alt.id),
                                title = alt.title,
                                number = alt.number.toFloatOrNull() ?: 0f,
                                volume = alt.volume,
                                url = "/manga/$mangaId/chapter/${alt.id}",
                                uploadDate = alt.uploadDate,
                                source = source,
                                scanlator = alt.scanlator,
                                branch = alt.scanlator
                            )
                        )
                    }
                }
            }
        }

        // Sort by chapter number (ascending) - oldest first for reading order
        return chapters.sortedBy { it.number }
    }

    private data class ChapterData(
        val id: String,
        val number: String,
        val volume: Int,
        val title: String?,
        val uploadDate: Long,
        val scanlator: String?,
        val version: Int
    )

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // Extract chapter ID from URL: /manga/{mangaId}/chapter/{chapterId}
        val chapterId = chapter.url.substringAfterLast("/")

        // Fetch chapter data from API
        val json = webClient.httpGet("${apiUrl}chapter/$chapterId").parseJson()

        // Get the CDN node URL
        val node = json.getString("node")

        // Prefer optimized webp images, fallback to original data
        val pagesArray = json.optJSONArray("data_optimized") ?: json.getJSONArray("data")

        return (0 until pagesArray.length()).map { i ->
            val pageObj = pagesArray.getJSONObject(i)
            val filename = pageObj.getString("name")
            val pageUrl = "$node/data/$chapterId/$filename"

            MangaPage(
                id = generateUid(pageUrl),
                url = pageUrl,
                preview = null,
                source = source
            )
        }
    }

    @MangaSourceParser("WEEBDEX_EN", "WeebDex (English)", "en")
    class English(context: MangaLoaderContext) : WeebDexParser(
        context,
        MangaParserSource.WEEBDEX_EN,
        "en"
    )

    @MangaSourceParser("WEEBDEX_ES", "WeebDex (Español)", "es")
    class Spanish(context: MangaLoaderContext) : WeebDexParser(
        context,
        MangaParserSource.WEEBDEX_ES,
        "es"
    )

    @MangaSourceParser("WEEBDEX_FR", "WeebDex (Français)", "fr")
    class French(context: MangaLoaderContext) : WeebDexParser(
        context,
        MangaParserSource.WEEBDEX_FR,
        "fr"
    )

    @MangaSourceParser("WEEBDEX_PT", "WeebDex (Português)", "pt")
    class Portuguese(context: MangaLoaderContext) : WeebDexParser(
        context,
        MangaParserSource.WEEBDEX_PT,
        "pt"
    )

    @MangaSourceParser("WEEBDEX_DE", "WeebDex (Deutsch)", "de")
    class German(context: MangaLoaderContext) : WeebDexParser(
        context,
        MangaParserSource.WEEBDEX_DE,
        "de"
    )

    @MangaSourceParser("WEEBDEX_IT", "WeebDex (Italiano)", "it")
    class Italian(context: MangaLoaderContext) : WeebDexParser(
        context,
        MangaParserSource.WEEBDEX_IT,
        "it"
    )

    @MangaSourceParser("WEEBDEX_RU", "WeebDex (Русский)", "ru")
    class Russian(context: MangaLoaderContext) : WeebDexParser(
        context,
        MangaParserSource.WEEBDEX_RU,
        "ru"
    )

    @MangaSourceParser("WEEBDEX_JA", "WeebDex (日本語)", "ja")
    class Japanese(context: MangaLoaderContext) : WeebDexParser(
        context,
        MangaParserSource.WEEBDEX_JA,
        "ja"
    )

    @MangaSourceParser("WEEBDEX_ZH", "WeebDex (中文)", "zh")
    class Chinese(context: MangaLoaderContext) : WeebDexParser(
        context,
        MangaParserSource.WEEBDEX_ZH,
        "zh"
    )

    @MangaSourceParser("WEEBDEX_KO", "WeebDex (한국어)", "ko")
    class Korean(context: MangaLoaderContext) : WeebDexParser(
        context,
        MangaParserSource.WEEBDEX_KO,
        "ko"
    )
}
