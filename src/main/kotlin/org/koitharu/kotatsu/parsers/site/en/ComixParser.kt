package org.koitharu.kotatsu.parsers.site.en

import androidx.collection.arraySetOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMIX, 24) {

    override val configKeyDomain = ConfigKey.Domain("comix.to")
    private val domain = "comix.to"
    private val apiBase = "api/v2"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    // Helper to build API URLs dynamically using the configured domain
    private fun buildApiUrl(path: String) = "https://$domain/$apiBase/$path".toHttpUrl().newBuilder()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildApiUrl("manga").apply {
            if (!filter.query.isNullOrEmpty()) addQueryParameter("keyword", filter.query)

            val orderParam = when (order) {
                SortOrder.RELEVANCE -> "relevance"
                SortOrder.UPDATED -> "chapter_updated_at"
                SortOrder.POPULARITY -> "views_30d"
                SortOrder.NEWEST -> "created_at"
                SortOrder.ALPHABETICAL -> "title"
                else -> "chapter_updated_at"
            }
            val direction = if (order == SortOrder.ALPHABETICAL) "asc" else "desc"
            addQueryParameter("order[$orderParam]", direction)

            if (filter.tags.isNotEmpty()) {
                filter.tags.forEach { addQueryParameter("genres[]", it.key) }
            }
            if (filter.tagsExclude.isNotEmpty()) {
                filter.tagsExclude.forEach { addQueryParameter("genres[]", "-${it.key}") }
            }

            // Default exclusions if no filters are active (matches Lua/Web behavior)
            if (filter.tags.isEmpty() && filter.tagsExclude.isEmpty()) {
                listOf("87264", "87266", "87268", "87265").forEach { addQueryParameter("genres[]", "-$it") }
            }

            addQueryParameter("limit", pageSize.toString())
            addQueryParameter("page", page.toString())
        }.build()

        val response = webClient.httpGet(url).parseJson()
        val result = response.optJSONObject("result") ?: return emptyList()
        val items = result.optJSONArray("items") ?: return emptyList()

        return (0 until items.length()).map { i ->
            parseMangaFromJson(items.getJSONObject(i))
        }
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val hashId = json.optString("hash_id", "").nullIfEmpty() ?: ""
        val title = json.optString("title", "Unknown")
        val description = json.optString("synopsis", "").nullIfEmpty()
        val poster = json.optJSONObject("poster")
        // Ensure coverUrl is non-null (consistent model expectation)
        val coverUrl = poster?.optString("medium", "")?.nullIfEmpty()
            ?: poster?.optString("large", "")?.nullIfEmpty()
            ?: ""

        val status = json.optString("status", "")
        val ratedAvg = json.optDouble("rated_avg", 0.0)

        // NOTE: API 'rated_avg' appears to be 0..100. Normalize to Kotatsu's expected scale.
        // If Kotatsu expects 0..10, convert by dividing by 10. Adjust if project expects another range.
        val rating = if (ratedAvg > 0.0) (ratedAvg / 10.0f).toFloat() else RATING_UNKNOWN

        val state = when (status.lowercase()) {
            "finished" -> MangaState.FINISHED
            "releasing" -> MangaState.ONGOING
            "on_hiatus" -> MangaState.PAUSED
            "discontinued" -> MangaState.ABANDONED
            else -> null
        }

        return Manga(
            id = generateUid(hashId.ifEmpty { UUID.randomUUID().toString() }),
            url = "/title/$hashId", // Storing HashID in URL for getDetails
            publicUrl = "https://$domain/title/$hashId",
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(),
            description = description,
            rating = rating,
            tags = emptySet(),
            authors = emptySet(),
            state = state,
            source = source,
            contentRating = if (json.optBoolean("is_nsfw", false)) ContentRating.ADULT else ContentRating.SAFE,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        // Extract hash safely (fallback to generated id digits if malformed)
        val hashIdCandidate = manga.url.substringAfter("/title/").takeIf { it.isNotBlank() }
            ?: extractAlphaNumeric(manga.id) ?: throw ParseException("Unable to extract hash id for ${manga.title}", manga.url)

        val detailsUrl = buildApiUrl("manga/$hashIdCandidate").apply {
            addQueryParameter("includes[]", "author")
            addQueryParameter("includes[]", "artist")
            addQueryParameter("includes[]", "genre")
            addQueryParameter("includes[]", "theme")
            addQueryParameter("includes[]", "demographic")
        }.build()

        val detailsDeferred = async { webClient.httpGet(detailsUrl).parseJson() }
        val chaptersDeferred = async { getChapters(hashIdCandidate) }

        val response = detailsDeferred.await()
        val chapters = chaptersDeferred.await()

        if (response.has("result")) {
            val result = response.getJSONObject("result")
            val updatedManga = parseMangaFromJson(result)

            val authors = result.optJSONArray("author")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("title")?.nullIfEmpty() }
            }?.toSet() ?: emptySet()

            // Merge Genres, Themes, Demographics into Tags
            val mappedTags = mutableSetOf<MangaTag>()
            fun addTags(jsonArrayName: String) {
                result.optJSONArray(jsonArrayName)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i)
                        val name = obj?.optString("title")?.nullIfEmpty()
                        val id = obj?.optInt("term_id", 0)?.toString()
                        if (name != null && id != null && id != "0") {
                            mappedTags.add(MangaTag(name, id, source))
                        }
                    }
                }
            }
            addTags("genre")
            addTags("theme")
            addTags("demographic")

            val type = result.optString("type", "").nullIfEmpty()
            if (type != null) {
                mappedTags.add(MangaTag(type.replaceFirstChar { it.titlecase() }, type, source))
            }

            return@coroutineScope updatedManga.copy(
                chapters = chapters,
                authors = authors,
                tags = mappedTags
            )
        }

        return@coroutineScope manga.copy(chapters = chapters)
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    private suspend fun getChapters(hashId: String): List<MangaChapter> {
        val allChapters = ArrayList<JSONObject>()
        var page = 1
        var lastPage = 1
        val maxPages = 200 // defensive cap to avoid potential infinite/persistent loops

        do {
            val chaptersUrl = buildApiUrl("manga/$hashId/chapters").apply {
                addQueryParameter("order[number]", "asc") // Lua uses ASC, we fetch ASC and reverse later
                addQueryParameter("limit", "100")
                addQueryParameter("page", page.toString())
            }.build()

            val response = webClient.httpGet(chaptersUrl).parseJson()
            val result = response.optJSONObject("result") ?: JSONObject()
            val items = result.optJSONArray("items")

            if (items != null) {
                for (i in 0 until items.length()) {
                    allChapters.add(items.getJSONObject(i))
                }
            }

            val pagination = result.optJSONObject("pagination")
            if (pagination != null) {
                lastPage = pagination.optInt("last_page", 1)
            }
            page++
            if (page > maxPages) break
        } while (page <= lastPage)

        // Map directly to MangaChapter (NO DEDUPLICATION, SHOW ALL SCANLATORS)
        val finalList = allChapters.mapNotNull { item ->
            val chapterId = item.optLong("chapter_id") // API uses numeric ID
            if (chapterId == 0L) return@mapNotNull null

            val number = item.optDouble("number", 0.0).toFloat()
            val volume = item.optString("volume", "0")
            val name = item.optString("name", "").nullIfEmpty()
            val createdAt = item.optLong("created_at")
            val scanlationGroup = item.optJSONObject("scanlation_group")
            val scanlatorName = scanlationGroup?.optString("name", null)?.nullIfEmpty()
            val groupId = item.optInt("scanlation_group_id", 0)

            val volStr = if (volume != "0") "Vol. $volume " else ""
            val chStr = "Ch. ${number.niceString()}"
            val titleStr = if (name != null) " - $name" else ""
            val scanStr = if (scanlatorName != null) " [$scanlatorName]" else ""
            val fullTitle = "$volStr$chStr$titleStr$scanStr".trim()

            // IMPORTANT: Use chapterId + groupId in UID to distinguish same chapter across groups.
            // Store groupId related info in 'branch'. 9275 historically represents "Official" on site.
            val branchValue = if (groupId != 0) groupId.toString() else null
            val branchNote = if (groupId == 9275) "Official" else null

            MangaChapter(
                id = generateUid("$chapterId-$groupId"),
                title = fullTitle.ifEmpty { "Chapter ${number.niceString()}" },
                number = number,
                volume = volume.toIntOrNull() ?: 0,
                url = "/chapters/$chapterId",
                uploadDate = createdAt * 1000L,
                source = source,
                scanlator = scanlatorName,
                branch = branchValue ?: branchNote // preserve numeric groupId when available; else mark Official if 9275
            )
        }

        // Return reversed (Newest First) as Kotatsu expects
        return finalList.reversed()
    }

    private fun Float.niceString(): String {
        return if (this == this.toLong().toFloat()) {
            this.toLong().toString()
        } else {
            this.toString()
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // Robust extraction: ensure we don't double slash or miss path
        val chapterId = chapter.url.substringAfterLast("/").nullIfEmpty()
            ?: throw ParseException("Invalid chapter URL for $domain: ${chapter.url}", chapter.url)

        val apiChapterUrl = buildApiUrl("chapters/$chapterId").build()

        val response = webClient.httpGet(apiChapterUrl).parseJson()
        val result = response.optJSONObject("result")
            ?: throw ParseException("No result in chapter response for $domain", apiChapterUrl.toString())

        val images = result.optJSONArray("images")
            ?: throw ParseException("No images found for chapter on $domain", apiChapterUrl.toString())

        return (0 until images.length()).map { i ->
            val imageUrl = images.getString(i)
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }

    private fun fetchAvailableTags() = arraySetOf(
        MangaTag("Action", "6", source),
        MangaTag("Adult", "87264", source),
        MangaTag("Adventure", "7", source),
        MangaTag("Boys Love", "8", source),
        MangaTag("Comedy", "9", source),
        MangaTag("Crime", "10", source),
        MangaTag("Drama", "11", source),
        MangaTag("Ecchi", "87265", source),
        MangaTag("Fantasy", "12", source),
        MangaTag("Girls Love", "13", source),
        MangaTag("Hentai", "87266", source),
        MangaTag("Historical", "14", source),
        MangaTag("Horror", "15", source),
        MangaTag("Isekai", "16", source),
        MangaTag("Magical Girls", "17", source),
        MangaTag("Mature", "87267", source),
        MangaTag("Mecha", "18", source),
        MangaTag("Medical", "19", source),
        MangaTag("Mystery", "20", source),
        MangaTag("Philosophical", "21", source),
        MangaTag("Psychological", "22", source),
        MangaTag("Romance", "23", source),
        MangaTag("Sci-Fi", "24", source),
        MangaTag("Slice of Life", "25", source),
        MangaTag("Smut", "87268", source),
        MangaTag("Sports", "26", source),
        MangaTag("Superhero", "27", source),
        MangaTag("Thriller", "28", source),
        MangaTag("Tragedy", "29", source),
        MangaTag("Wuxia", "30", source),
        MangaTag("Aliens", "31", source),
        MangaTag("Animals", "32", source),
        MangaTag("Cooking", "33", source),
        MangaTag("Crossdressing", "34", source),
        MangaTag("Delinquents", "35", source),
        MangaTag("Demons", "36", source),
        MangaTag("Genderswap", "37", source),
        MangaTag("Ghosts", "38", source),
        MangaTag("Gyaru", "39", source),
        MangaTag("Harem", "40", source),
        MangaTag("Incest", "41", source),
        MangaTag("Loli", "42", source),
        MangaTag("Mafia", "43", source),
        MangaTag("Magic", "44", source),
        MangaTag("Martial Arts", "45", source),
        MangaTag("Military", "46", source),
        MangaTag("Monster Girls", "47", source),
        MangaTag("Monsters", "48", source),
        MangaTag("Music", "49", source),
        MangaTag("Ninja", "50", source),
        MangaTag("Office Workers", "51", source),
        MangaTag("Police", "52", source),
        MangaTag("Post-Apocalyptic", "53", source),
        MangaTag("Reincarnation", "54", source),
        MangaTag("Reverse Harem", "55", source),
        MangaTag("Samurai", "56", source),
        MangaTag("School Life", "57", source),
        MangaTag("Shota", "58", source),
        MangaTag("Supernatural", "59", source),
        MangaTag("Survival", "60", source),
        MangaTag("Time Travel", "61", source),
        MangaTag("Traditional Games", "62", source),
        MangaTag("Vampires", "63", source),
        MangaTag("Video Games", "64", source),
        MangaTag("Villainess", "65", source),
        MangaTag("Virtual Reality", "66", source),
        MangaTag("Zombies", "67", source),
    )

    // Extract only alphanumeric/dash characters from id-like strings
    private fun extractAlphaNumeric(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val match = Regex("([A-Za-z0-9-_]+)").find(input)
        return match?.groupValues?.get(1)
    }
}
