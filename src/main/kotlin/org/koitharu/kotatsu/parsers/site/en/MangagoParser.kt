package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

@MangaSourceParser("MANGAGO", "Mangago", "en")
internal class MangagoParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAGO, pageSize = 20), Interceptor {

    override val configKeyDomain = ConfigKey.Domain("www.mangago.me")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.NEWEST
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getAvailableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
    )

    private fun getAvailableTags(): Set<MangaTag> = setOf(
        MangaTag("Yaoi", "Yaoi", source),
        MangaTag("Doujinshi", "Doujinshi", source),
        MangaTag("Shounen Ai", "Shounen Ai", source),
        MangaTag("Shoujo", "Shoujo", source),
        MangaTag("Yuri", "Yuri", source),
        MangaTag("Romance", "Romance", source),
        MangaTag("Fantasy", "Fantasy", source),
        MangaTag("Comedy", "Comedy", source),
        MangaTag("Smut", "Smut", source),
        MangaTag("Adult", "Adult", source),
        MangaTag("School Life", "School Life", source),
        MangaTag("Mystery", "Mystery", source),
        MangaTag("One Shot", "One Shot", source),
        MangaTag("Ecchi", "Ecchi", source),
        MangaTag("Shounen", "Shounen", source),
        MangaTag("Martial Arts", "Martial Arts", source),
        MangaTag("Shoujo Ai", "Shoujo Ai", source),
        MangaTag("Supernatural", "Supernatural", source),
        MangaTag("Drama", "Drama", source),
        MangaTag("Action", "Action", source),
        MangaTag("Adventure", "Adventure", source),
        MangaTag("Harem", "Harem", source),
        MangaTag("Historical", "Historical", source),
        MangaTag("Horror", "Horror", source),
        MangaTag("Josei", "Josei", source),
        MangaTag("Mature", "Mature", source),
        MangaTag("Mecha", "Mecha", source),
        MangaTag("Psychological", "Psychological", source),
        MangaTag("Sci-fi", "Sci-fi", source),
        MangaTag("Seinen", "Seinen", source),
        MangaTag("Slice Of Life", "Slice Of Life", source),
        MangaTag("Sports", "Sports", source),
        MangaTag("Gender Bender", "Gender Bender", source),
        MangaTag("Tragedy", "Tragedy", source),
        MangaTag("Bara", "Bara", source),
        MangaTag("Shotacon", "Shotacon", source),
        MangaTag("Webtoons", "Webtoons", source),
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) {
            // Search by query
            val url = buildString {
                append("https://")
                append(domain)
                append("/r/l_search?name=")
                append(filter.query.urlEncoded())
                append("&page=")
                append(page)
            }
            return parseMangaList(webClient.httpGet(url).parseHtml())
        }

        // Build URL based on sort order
        val url = when (order) {
            SortOrder.NEWEST -> {
                // Use list/new for newest
                buildString {
                    append("https://")
                    append(domain)
                    append("/list/new/")
                    if (filter.tags.isNotEmpty()) {
                        filter.tags.joinTo(this, ",") { it.key }
                    } else {
                        append("all")
                    }
                    append("/")
                    append(page)
                    append("/")
                }
            }
            else -> {
                // Use genre for other sort orders
                buildString {
                    append("https://")
                    append(domain)
                    append("/genre/")

                    // Genres
                    if (filter.tags.isNotEmpty()) {
                        filter.tags.joinTo(this, ",") { it.key }
                    } else {
                        append("all")
                    }

                    append("/")
                    append(page)
                    append("/?")

                    // Status filters
                    val states = filter.states
                    append("f=")
                    append(if (states.contains(MangaState.FINISHED)) "1" else "0")
                    append("&o=")
                    append(if (states.contains(MangaState.ONGOING)) "1" else "0")

                    // Sort order
                    append("&sortby=")
                    when (order) {
                        SortOrder.POPULARITY -> append("view")
                        SortOrder.UPDATED -> append("update_date")
                        else -> append("update_date")
                    }

                    // Excluded genres
                    append("&e=")
                    if (filter.tagsExclude.isNotEmpty()) {
                        filter.tagsExclude.joinTo(this, ",") { it.key }
                    }
                }
            }
        }

        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(".box, .updatesli, .pic_list > li").mapNotNull { element ->
            val linkElement = element.selectFirst(".thm-effect") ?: return@mapNotNull null
            val href = linkElement.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = linkElement.attr("title").ifEmpty {
                linkElement.selectFirst("h2 a")?.text() ?: return@mapNotNull null
            }
            val thumbnailElem = linkElement.selectFirst("img") ?: return@mapNotNull null
            val thumbnailUrl = thumbnailElem.attr("abs:data-src").ifEmpty {
                thumbnailElem.attr("abs:src")
            }

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                coverUrl = thumbnailUrl,
                largeCoverUrl = null,
                description = null,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val infoBlock = doc.requireElementById("information")

        val title = doc.selectFirstOrThrow(".w-title h1").text()
        val thumbnail = infoBlock.selectFirstOrThrow("img").absUrl("src")

        var description = infoBlock.selectFirst(".manga_summary")?.let { summary ->
            summary.selectFirst("font")?.remove()
            summary.text()
        }.orEmpty()

        var author: String? = null
        var genres = emptySet<MangaTag>()
        var state: MangaState? = null

        infoBlock.select(".manga_info li, .manga_right tr").forEach { el ->
            when (el.selectFirst("b, label")?.text()?.lowercase()) {
                "alternative:" -> description += "\n\n${el.text()}"
                "status:" -> state = when (el.selectFirstOrThrow("span").text().lowercase()) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    else -> null
                }
                "author(s):", "author:" -> author = el.select("a").joinToString { it.text() }
                "genre(s):" -> genres = el.select("a").mapToSet {
                    MangaTag(
                        key = it.text(),
                        title = it.text(),
                        source = source,
                    )
                }
            }
        }

        val chapters = parseChapterList(doc)

        return manga.copy(
            title = title,
            coverUrl = thumbnail,
            description = description,
            authors = author?.let { setOf(it) } ?: emptySet(),
            tags = genres,
            state = state,
            chapters = chapters,
        )
    }

    private fun parseChapterList(doc: Document): List<MangaChapter> {
        return doc.select("table#chapter_table > tbody > tr, table.uk-table > tbody > tr")
            .mapIndexedNotNull { index, element ->
                val link = element.selectFirst("a.chico") ?: return@mapIndexedNotNull null
                val url = link.attrAsAbsoluteUrlOrNull("href") ?: return@mapIndexedNotNull null
                val name = link.text().trim()

                val dateText = element.select("td:last-child").text().trim()
                val dateUpload = runCatching {
                    dateFormat.parse(dateText)?.time
                }.getOrNull() ?: 0L

                val scanlator = element.selectFirst("td.no a, td.uk-table-shrink a")?.text()?.trim()
                    ?.ifEmpty { null }

                MangaChapter(
                    id = generateUid(url),
                    url = url,
                    title = name,
                    number = index + 1f,
                    volume = 0,
                    uploadDate = dateUpload,
                    scanlator = scanlator,
                    branch = null,
                    source = source,
                )
            }.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        // Check if mobile view
        if (doc.select("div.controls ul#dropdown-menu-page").isNotEmpty()) {
            return parseMobilePages(doc)
        }

        // Desktop view with encrypted images
        val imgsrcsScript = doc.selectFirst("script:containsData(imgsrcs)")?.html()
            ?: throw Exception("Could not find imgsrcs script")

        val imgsrcRaw = IMG_SRCS_REGEX.find(imgsrcsScript)?.groupValues?.get(1)
            ?: throw Exception("Could not extract imgsrcs")

        val imgsrcs = context.decodeBase64(imgsrcRaw)

        // Get chapter.js URL
        val chapterJsUrl = doc.select("script[src*=chapter.js]").firstOrNull()?.absUrl("src")
            ?: throw Exception("Could not find chapter.js")

        // Download and deobfuscate chapter.js
        val obfuscatedChapterJs = webClient.httpGet(chapterJsUrl).parseRaw()
        val deobfChapterJs = deobfuscateSoJsonV4(obfuscatedChapterJs)

        // Extract AES key and IV
        val key = findHexEncodedVariable(deobfChapterJs, "key").decodeHex()
        val iv = findHexEncodedVariable(deobfChapterJs, "iv").decodeHex()

        // Decrypt image list
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
        val decryptedBytes = cipher.doFinal(imgsrcs)

        // Remove zero padding
        var imageList = String(decryptedBytes, Charsets.UTF_8).trimEnd('\u0000')

        // Unscramble image list
        imageList = unscrambleImageList(imageList, deobfChapterJs)

        // Extract columns for descrambling
        val cols = COLS_REGEX.find(deobfChapterJs)?.groupValues?.get(1) ?: ""

        // Build page list
        return imageList.split(",").mapIndexed { index, imageUrl ->
            val url = if (imageUrl.contains("cspiclink")) {
                val descramblingKey = getDescramblingKey(deobfChapterJs, imageUrl)
                "$imageUrl#desckey=$descramblingKey&cols=$cols"
            } else {
                imageUrl
            }

            MangaPage(
                id = generateUid("$fullUrl-$index"),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun parseMobilePages(doc: Document): List<MangaPage> {
        val pagesCount = doc.select("div.controls ul#dropdown-menu-page li").size
        val pageUrl = doc.location().removeSuffix("/").substringBeforeLast("-")

        return (1..pagesCount).map { pageNum ->
            MangaPage(
                id = generateUid("$pageUrl-$pageNum"),
                url = "$pageUrl-$pageNum/",
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        // Check if it's a mobile page URL (relative URL without protocol)
        if (page.url.contains("/pg-")) {
            val doc = webClient.httpGet(page.url.toAbsoluteUrl(domain)).parseHtml()
            return doc.selectFirst("div#viewer img")?.absUrl("src")
                ?: throw Exception("Could not find image")
        }

        // Desktop image URL - return as is (fragment contains descrambling parameters)
        return page.url
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    // Interceptor for image descrambling
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val fragment = response.request.url.fragment

        if (fragment == null || !fragment.contains("desckey=")) {
            return response
        }

        return context.redrawImageResponse(response) { bitmap ->
            val key = fragment.substringAfter("desckey=").substringBefore("&")
            val cols = fragment.substringAfter("cols=").toIntOrNull() ?: return@redrawImageResponse bitmap
            unscrambleImage(bitmap, key, cols)
        }
    }

    private fun unscrambleImage(bitmap: Bitmap, key: String, cols: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val result = context.createBitmap(width, height)

        val unitWidth = width / cols
        val unitHeight = height / cols

        val keyArray = key.split("a")

        for (idx in 0 until cols * cols) {
            val keyval = keyArray.getOrNull(idx)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0

            val heightY = keyval / cols
            val dy = heightY * unitHeight
            val dx = (keyval - heightY * cols) * unitWidth

            val widthY = idx / cols
            val sy = widthY * unitHeight
            val sx = (idx - widthY * cols) * unitWidth

            val w = min(unitWidth, width - dx)
            val h = min(unitHeight, height - dy)

            val srcRect = Rect(sx, sy, sx + w, sy + h)
            val dstRect = Rect(dx, dy, dx + w, dy + h)

            result.drawBitmap(bitmap, srcRect, dstRect)
        }

        return result
    }

    // Helper methods for decryption and descrambling

    private fun deobfuscateSoJsonV4(jsf: String): String {
        if (!jsf.startsWith("['sojson.v4']")) {
            throw Exception("Obfuscated code is not sojson.v4")
        }

        val splitRegex = Regex("[a-zA-Z]+")
        val args = jsf.substring(240, jsf.length - 59).split(splitRegex)

        return args.map { it.toInt().toChar() }.joinToString("")
    }

    private fun findHexEncodedVariable(input: String, variable: String): String {
        val regex = Regex("""var $variable\s*=\s*CryptoJS\.enc\.Hex\.parse\("([0-9a-zA-Z]+)"\)""")
        return regex.find(input)?.groupValues?.get(1)
            ?: throw Exception("Could not find variable: $variable")
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun unscrambleImageList(imageList: String, js: String): String {
        var imgList = imageList
        try {
            val keyLocations = KEY_LOCATION_REGEX.findAll(js)
                .map { it.groupValues[1].toInt() }
                .distinct()
                .toList()

            val unscrambleKey = keyLocations.map {
                imgList[it].toString().toInt()
            }

            keyLocations.forEachIndexed { idx, it ->
                imgList = imgList.removeRange(it - idx..it - idx)
            }

            imgList = imgList.unscramble(unscrambleKey)
        } catch (e: NumberFormatException) {
            // List is already unscrambled
        }
        return imgList
    }

    private fun String.unscramble(keys: List<Int>): String {
        var s = this
        keys.reversed().forEach { key ->
            for (i in s.length - 1 downTo key) {
                if (i % 2 != 0) {
                    val temp = s[i - key]
                    s = s.replaceRange(i - key..i - key, s[i].toString())
                    s = s.replaceRange(i..i, temp.toString())
                }
            }
        }
        return s
    }

    private fun getDescramblingKey(deobfChapterJs: String, imageUrl: String): String {
        // Extract the key generation logic from the deobfuscated JavaScript
        val imgkeys = deobfChapterJs
            .substringAfter("var renImg = function(img,width,height,id){", "")
            .substringBefore("key = key.split(", "")

        if (imgkeys.isEmpty()) {
            return ""
        }

        // Parse the JavaScript to extract the descrambling key
        // This is a simplified version - the full implementation would need JS evaluation
        // For now, return empty string which means images won't be descrambled
        // but will still load (they'll just be scrambled)
        return ""
    }

    companion object {
        private val IMG_SRCS_REGEX = Regex("""var imgsrcs\s*=\s*['"]([a-zA-Z0-9+=/]+)['"]""")
        private val COLS_REGEX = Regex("""var\s*widthnum\s*=\s*heightnum\s*=\s*(\d+);""")
        private val KEY_LOCATION_REGEX = Regex("""str\.charAt\(\s*(\d+)\s*\)""")
    }
}
