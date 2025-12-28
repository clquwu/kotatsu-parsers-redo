package org.koitharu.kotatsu.parsers.site.en

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

@MangaSourceParser("ATSUMOE", "Atsu.moe", "en")
internal class AtsuMoe(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ATSUMOE, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("atsu.moe")
    private val apiUrl = "https://$domain/api/"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val endpoint = when (order) {
            SortOrder.POPULARITY -> "infinite/trending"
            SortOrder.UPDATED -> "infinite/recentlyUpdated"
            else -> "infinite/trending"
        }

        val url = "${apiUrl}$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("types", "Manga,Manwha,Manhua,OEL")
            .build()

        if (!filter.query.isNullOrEmpty()) {
            return getSearchPage(page, filter.query)
        }

        val json = webClient.httpGet(url.toString()).parseJson()
        val items = json.getJSONArray("items")

        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            parseManga(item)
        }
    }

    private suspend fun getSearchPage(page: Int, query: String): List<Manga> {
        val url = "https://$domain/collections/manga/documents/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("query_by", "title,englishTitle,otherNames")
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("query_by_weights", "3,2,1")
            .addQueryParameter("include_fields", "id,title,englishTitle,poster")
            .addQueryParameter("num_typos", "4,3,2")
            .build()

        val json = webClient.httpGet(url.toString()).parseJson()
        val hits = json.getJSONArray("hits")

        return (0 until hits.length()).map { i ->
            val hit = hits.getJSONObject(i)
            val document = hit.getJSONObject("document")
            parseManga(document)
        }
    }

    private fun parseManga(json: JSONObject): Manga {
        val id = json.getString("id")
        val title = json.optString("title").ifEmpty {
            json.optString("englishTitle", "Unknown")
        }
        val image = json.optString("image")
        val coverUrl = if (image.isNotEmpty()) "https://$domain/static/$image" else null

        return Manga(
            id = generateUid(id),
            title = title,
            altTitles = emptySet(),
            url = "/manga/$id",
            publicUrl = "https://$domain/manga/$id",
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.SAFE,
            coverUrl = coverUrl,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaId = manga.url.substringAfterLast("/")
        val json = webClient.httpGet("${apiUrl}manga/page?id=$mangaId").parseJson()
        val mangaPage = json.getJSONObject("mangaPage")

        val title = mangaPage.optString("title").ifEmpty {
            mangaPage.optString("englishTitle", manga.title)
        }
        val description = mangaPage.optString("synopsis")

        // Parse poster object
        val posterObj = mangaPage.optJSONObject("poster")
        val posterImage = posterObj?.optString("image")
        val coverUrl = if (!posterImage.isNullOrEmpty()) {
            "https://$domain/static/$posterImage"
        } else manga.coverUrl

        // Parse tags
        val tagsArray = mangaPage.optJSONArray("tags")
        val tags = if (tagsArray != null) {
            (0 until tagsArray.length()).map { i ->
                val tag = tagsArray.getJSONObject(i)
                MangaTag(
                    key = tag.getString("id"),
                    title = tag.getString("name"),
                    source = source
                )
            }.toSet()
        } else emptySet<MangaTag>()

        // Parse authors
        val authorsArray = mangaPage.optJSONArray("authors")
        val authors = if (authorsArray != null) {
            (0 until authorsArray.length()).mapNotNull { i ->
                val author = authorsArray.getJSONObject(i)
                author.optString("name").takeIf { it.isNotEmpty() }
            }.toSet()
        } else emptySet<String>()

        // Parse status
        val status = mangaPage.optString("status")
        val state = when (status.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled" -> MangaState.ABANDONED
            else -> null
        }

        // Parse chapters from mangaPage
        val chaptersArray = mangaPage.optJSONArray("chapters")
        val chapters = if (chaptersArray != null) {
            (0 until chaptersArray.length()).map { i ->
                val chapter = chaptersArray.getJSONObject(i)
                parseChapter(chapter, mangaId)
            }
        } else emptyList()

        return manga.copy(
            title = title,
            description = description,
            coverUrl = coverUrl,
            tags = tags,
            authors = authors,
            state = state,
            chapters = chapters
        )
    }

    private fun parseChapter(json: JSONObject, mangaId: String): MangaChapter {
        val chapterId = json.getString("id")
        val title = json.optString("title").takeIf { it.isNotEmpty() }
        val number = json.optInt("number", 0).toFloat()

        // Parse ISO date string to timestamp
        val createdAtStr = json.optString("createdAt")
        val uploadDate = if (createdAtStr.isNotEmpty()) {
            try {
                dateFormat.parse(createdAtStr)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        } else 0L

        return MangaChapter(
            id = generateUid("$mangaId/$chapterId"),
            title = title,
            number = number,
            volume = 0,
            url = "$mangaId/$chapterId",
            uploadDate = uploadDate,
            source = source,
            scanlator = null,
            branch = null
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val (mangaId, chapterId) = chapter.url.split("/")
        val url = "${apiUrl}read/chapter".toHttpUrl().newBuilder()
            .addQueryParameter("mangaId", mangaId)
            .addQueryParameter("chapterId", chapterId)
            .build()

        val json = webClient.httpGet(url.toString()).parseJson()
        val readChapter = json.getJSONObject("readChapter")
        val pages = readChapter.getJSONArray("pages")

        return (0 until pages.length()).map { i ->
            val page = pages.getJSONObject(i)
            val pageUrl = page.getString("url")
            val fullUrl = if (pageUrl.startsWith("http")) {
                pageUrl
            } else {
                "https://$domain$pageUrl"
            }

            MangaPage(
                id = generateUid(fullUrl),
                url = fullUrl,
                preview = null,
                source = source
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        return emptyList()
    }
}
