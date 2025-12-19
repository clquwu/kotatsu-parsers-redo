package org.koitharu.kotatsu.parsers.site.all

import androidx.collection.ArrayMap
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.*

@MangaSourceParser("MANGAPARK", "MangaPark")
internal class MangaPark(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAPARK, pageSize = 36) {

    override val configKeyDomain = ConfigKey.Domain(
        "mangapark.net",
        "mangapark.com",
        "mangapark.org",
        "mangapark.me",
        "mangapark.io",
        "mangapark.to",
        "comicpark.org",
        "comicpark.to",
        "readpark.org",
        "readpark.net",
        "parkmanga.com",
        "parkmanga.net",
        "parkmanga.org",
        "mpark.to",
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.ALPHABETICAL, SortOrder.RATING)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isOriginalLocaleSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = tagsMap.get().values.toSet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.ABANDONED,
            MangaState.PAUSED,
            MangaState.UPCOMING,
        ),
        availableContentRating = EnumSet.of(ContentRating.SAFE),
        availableLocales = emptySet() // Simplified for brevity, can be expanded if needed
    )

    init {
        context.cookieJar.insertCookies(domain, "nsfw", "2")
    }

    // ---------------------------------------------------------------
    // 1. List / Search (Kept as HTML Scraping)
    // The API is complex for search, and HTML is stable enough for lists.
    // ---------------------------------------------------------------
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/search?page=")
            append(page.toString())
            filter.query?.let {
                append("&word=")
                append(filter.query.urlEncoded())
            }
            append("&genres=")
            filter.tags.joinTo(this, ",") { it.key }
            append("|")
            filter.tagsExclude.joinTo(this, ",") { it.key }
            
            append("&sortby=")
            append(when (order) {
                SortOrder.POPULARITY -> "views_d000"
                SortOrder.UPDATED -> "field_update"
                SortOrder.NEWEST -> "field_create"
                SortOrder.ALPHABETICAL -> "field_name"
                SortOrder.RATING -> "field_score"
                else -> ""
            })
        }

        val doc = webClient.httpGet(url).parseHtml()
        return doc.select("div.grid.gap-5 div.flex.border-b").map { div ->
            val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(div.host ?: domain),
                coverUrl = div.selectFirst("img")?.src(),
                title = div.selectFirst("h3")?.text().orEmpty(),
                altTitles = emptySet(),
                rating = div.selectFirst("span.text-yellow-500")?.text()?.toFloatOrNull()?.div(10F) ?: RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }
    }

    // ---------------------------------------------------------------
    // 2. Details (Now using GraphQL API)
    // ---------------------------------------------------------------
    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        // Extract numeric ID from URL (e.g., /title/75536-en-solo-leveling -> 75536)
        val comicId = manga.url.substringAfter("/title/").substringBefore("-").toLongOrNull()
            ?: throw Exception("Could not parse Manga ID from URL")

        // GRAPHQL QUERY 1: Details
        val queryDetails = """
            { get_comicNode(id: $comicId) { data { name altNames urlCoverOri authors artists genres originalStatus uploadStatus summary } } }
        """.trimIndent()
        
        val detailsJson = fetchGraphQL(queryDetails).optJSONObject("data")?.optJSONObject("get_comicNode")?.optJSONObject("data")
            ?: return@coroutineScope manga

        // GRAPHQL QUERY 2: Chapters
        val queryChapters = """
            { get_comicChapterList(comicId: $comicId) { data { id dname title dateCreate } } }
        """.trimIndent()
        
        val chaptersJson = fetchGraphQL(queryChapters).optJSONObject("data")?.optJSONObject("get_comicChapterList")?.optJSONArray("data")

        // Parse Details
        val authors = detailsJson.optJSONArray("authors")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }?.toSet() ?: emptySet()

        val genres = detailsJson.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).map { 
                val name = arr.getString(it)
                MangaTag(name, name, source)
            }
        }?.toSet() ?: emptySet()

        val status = when (detailsJson.optString("uploadStatus")) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            else -> when(detailsJson.optString("originalStatus")) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                else -> null
            }
        }

        // Parse Chapters
        val chapters = ArrayList<MangaChapter>()
        if (chaptersJson != null) {
            for (i in 0 until chaptersJson.length()) {
                val ch = chaptersJson.getJSONObject(i)
                val chId = ch.optString("id")
                val dname = ch.optString("dname")
                val title = ch.optString("title").let { if (it != "null" && it.isNotBlank()) " - $it" else "" }
                
                // Parse "dname" for number (e.g. "Chapter 10")
                val number = Regex("([0-9.]+)").find(dname)?.value?.toFloatOrNull() ?: 0f

                chapters.add(MangaChapter(
                    id = generateUid(chId),
                    title = "$dname$title",
                    number = number,
                    volume = 0,
                    url = "/title/$comicId/chapter/$chId", // Constructed URL
                    uploadDate = ch.optLong("dateCreate", 0L),
                    source = source,
                    scanlator = null,
                    branch = null
                ))
            }
        }

        return@coroutineScope manga.copy(
            title = detailsJson.optString("name"),
            description = detailsJson.optString("summary"),
            coverUrl = detailsJson.optString("urlCoverOri"),
            authors = authors,
            tags = genres,
            state = status,
            chapters = chapters
        )
    }

    // ---------------------------------------------------------------
    // 3. Pages (Now using GraphQL API)
    // ---------------------------------------------------------------
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chId = chapter.url.substringAfterLast("/")
        
        // GRAPHQL QUERY: Pages
        val query = """
            { get_chapterNode(id: $chId) { data { imageFile { urlList } } } }
        """.trimIndent()

        val json = fetchGraphQL(query)
        val urlList = json.optJSONObject("data")
            ?.optJSONObject("get_chapterNode")
            ?.optJSONObject("data")
            ?.optJSONObject("imageFile")
            ?.optJSONArray("urlList") 
            ?: throw Exception("No pages found")

        val pages = ArrayList<MangaPage>()
        for (i in 0 until urlList.length()) {
            var url = urlList.getString(i)
            // Lua script does this replacement, presumably for quality/server fix
            url = url.replace(Regex("s[0-9]{2}"), "s00")
            
            pages.add(MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            ))
        }
        return pages
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------
    private suspend fun fetchGraphQL(query: String): JSONObject {
        val payload = JSONObject().put("query", query).toString()
        val requestBody = payload.toRequestBody("application/json".toMediaType())
        
        return webClient.httpPost(
            url = "https://$domain/apo/".toHttpUrl(),
            requestBody = requestBody
        ).parseJson()
    }

    private val tagsMap = suspendLazy(initializer = ::parseTags)

    private suspend fun parseTags(): Map<String, MangaTag> {
        // Minimal HTML scraping just for tags map used in filtering
        val doc = webClient.httpGet("https://$domain/search").parseHtml()
        val tagElements = doc.select("div.flex-col:contains(Genres) div.whitespace-nowrap")
        val tagMap = ArrayMap<String, MangaTag>(tagElements.size)
        for (el in tagElements) {
            val name = el.selectFirst("span.whitespace-nowrap")?.text() ?: continue
            if (name.isEmpty()) continue
            tagMap[name] = MangaTag(
                title = name,
                key = el.attr("q:key"),
                source = source,
            )
        }
        return tagMap
    }
}
