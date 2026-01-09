package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("AZORAMOON", "Azoramoon", "ar")
internal class Azoramoon(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.AZORAMOON, 24) {

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.ALPHABETICAL)

	override val configKeyDomain = ConfigKey.Domain("azoramoon.com")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://api.")
			append(domain)
			append("/api/query")

			val params = mutableListOf<String>()

			// Add pagination
			params.add("page=$page")
			params.add("perPage=24")

			// Add search query
			if (!filter.query.isNullOrEmpty()) {
				params.add("searchTerm=${filter.query!!.urlEncoded()}")
			}

			// Add genre filters (comma-separated)
			if (filter.tags.isNotEmpty()) {
				val genreIds = filter.tags.joinToString(",") { it.key }
				params.add("genreIds=$genreIds")
			}

			// Add sort filter
			val (orderBy, orderDirection) = when (order) {
				SortOrder.UPDATED -> "lastChapterAddedAt" to "desc"
				SortOrder.POPULARITY -> "totalViews" to "desc"
				SortOrder.NEWEST -> "createdAt" to "desc"
				SortOrder.ALPHABETICAL -> "postTitle" to "asc"
				else -> "lastChapterAddedAt" to "desc"
			}
			params.add("orderBy=$orderBy")
			params.add("orderDirection=$orderDirection")

			// Append parameters
			if (params.isNotEmpty()) {
				append("?")
				append(params.joinToString("&"))
			}
		}

		println("[Azoramoon] Request URL: $url")

		val response = webClient.httpGet(url)
		val body = response.body.string()

		println("[Azoramoon] Response code: ${response.code}")
		println("[Azoramoon] Response body length: ${body.length}")
		println("[Azoramoon] Response body preview (first 500 chars): ${body.take(500)}")

		// Try to parse as JSONArray first (API returns direct array)
		val jsonArray = try {
			val arr = JSONArray(body)
			println("[Azoramoon] Successfully parsed as JSONArray with ${arr.length()} items")
			arr
		} catch (e: Exception) {
			println("[Azoramoon] Failed to parse as JSONArray: ${e.message}")
			// If that fails, try as JSONObject and extract array
			try {
				val jsonObject = JSONObject(body)
				val arr = when {
					jsonObject.has("posts") -> {
						println("[Azoramoon] Found 'posts' field in JSONObject")
						jsonObject.getJSONArray("posts")
					}
					jsonObject.has("data") -> {
						println("[Azoramoon] Found 'data' field in JSONObject")
						jsonObject.getJSONArray("data")
					}
					jsonObject.has("results") -> {
						println("[Azoramoon] Found 'results' field in JSONObject")
						jsonObject.getJSONArray("results")
					}
					else -> {
						println("[Azoramoon] No 'posts', 'data' or 'results' field found")
						println("[Azoramoon] Available keys: ${jsonObject.keys().asSequence().toList()}")
						JSONArray()
					}
				}
				println("[Azoramoon] Extracted array with ${arr.length()} items")
				arr
			} catch (e2: Exception) {
				println("[Azoramoon] Failed to parse as JSONObject: ${e2.message}")
				JSONArray()
			}
		}

		return parseMangaList(jsonArray)
	}

	private fun parseMangaList(json: JSONArray): List<Manga> {
		println("[Azoramoon] Parsing manga list with ${json.length()} items")
		val result = mutableListOf<Manga>()

		for (i in 0 until json.length()) {
			val obj = json.getJSONObject(i)
			val slug = obj.getString("slug")
			val url = "/series/$slug"
			val title = obj.getString("postTitle")
			val coverUrl = obj.optString("featuredImage")

			println("[Azoramoon] Manga $i: $title (slug: $slug)")

			// Parse status
			val seriesStatus = obj.optString("seriesStatus", "")
			val state = when (seriesStatus.uppercase()) {
				"ONGOING" -> MangaState.ONGOING
				"COMPLETED" -> MangaState.FINISHED
				"HIATUS" -> MangaState.PAUSED
				else -> null
			}

			// Parse genres
			val genresArray = obj.optJSONArray("genres")
			val tags = if (genresArray != null) {
				buildSet {
					for (idx in 0 until genresArray.length()) {
						val genre = genresArray.getJSONObject(idx)
						add(MangaTag(
							key = genre.getInt("id").toString(),
							title = genre.getString("name"),
							source = source,
						))
					}
				}
			} else {
				emptySet()
			}

			result.add(
				Manga(
					id = generateUid(url),
					title = title,
					altTitles = emptySet(),
					url = url,
					publicUrl = url.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = coverUrl,
					tags = tags,
					state = state,
					authors = emptySet(),
					source = source,
				)
			)
		}

		println("[Azoramoon] Parsed ${result.size} manga from API")
		return result
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		// Hardcoded genre list from the API (swapping key and title as requested)
		return setOf(
			MangaTag("أكشن", "1", source),
			MangaTag("حريم", "2", source),
			MangaTag("زمكاني", "3", source),
			MangaTag("سحر", "4", source),
			MangaTag("شونين", "5", source),
			MangaTag("مغامرات", "6", source),
			MangaTag("خيال", "7", source),
			MangaTag("رومانسي", "8", source),
			MangaTag("كوميدي", "9", source),
			MangaTag("مانهوا", "10", source),
			MangaTag("إثارة", "11", source),
			MangaTag("دراما", "12", source),
			MangaTag("تاريخي", "13", source),
			MangaTag("راشد", "14", source),
			MangaTag("سينين", "15", source),
			MangaTag("خارق للطبيعة", "16", source),
			MangaTag("شياطين", "17", source),
			MangaTag("حياة مدرسية", "18", source),
			MangaTag("جوسي", "19", source),
			MangaTag("مانها", "20", source),
			MangaTag("ويبتون", "21", source),
			MangaTag("شينين", "22", source),
			MangaTag("قوة خارقة", "23", source),
			MangaTag("خيال علمي", "24", source),
			MangaTag("غموض", "25", source),
			MangaTag("مأساة", "26", source),
			MangaTag("شريحة من الحياة", "27", source),
			MangaTag("فنون قتالية", "28", source),
			MangaTag("شوجو", "29", source),
			MangaTag("ايسكاي", "30", source),
			MangaTag("مصاصي الدماء", "31", source),
			MangaTag("اسبوعي", "32", source),
			MangaTag("لعبة", "33", source),
			MangaTag("نفسي", "34", source),
			MangaTag("وحوش", "35", source),
			MangaTag("الحياة اليومية", "36", source),
			MangaTag("الحياة المدرسية", "37", source),
			MangaTag("رعب", "38", source),
			MangaTag("عسكري", "39", source),
			MangaTag("رياضي", "40", source),
			MangaTag("اتشي", "41", source),
			MangaTag("ايشي", "42", source),
			MangaTag("دموي", "43", source),
			MangaTag("زومبي", "44", source),
			MangaTag("مميز", "45", source),
			MangaTag("ايسيكاي", "46", source),
			MangaTag("فنتازيا", "47", source),
			MangaTag("اشباح", "48", source),
			MangaTag("إعادة إحياء", "49", source),
			MangaTag("بطل غير اعتيادي", "50", source),
			MangaTag("ثأر", "51", source),
			MangaTag("اثارة", "52", source),
			MangaTag("تراجيدي", "53", source),
			MangaTag("طبخ", "54", source),
			MangaTag("تناسخ", "55", source),
			MangaTag("عودة بالزمن", "56", source),
			MangaTag("انتقام", "57", source),
			MangaTag("تجسيد", "58", source),
			MangaTag("فانتازيا", "59", source),
			MangaTag("عائلي", "60", source),
			MangaTag("تجسد", "61", source),
			MangaTag("العاب", "62", source),
			MangaTag("عالم اخر", "63", source),
			MangaTag("السفر عبر الزمن", "64", source),
			MangaTag("خيالي", "65", source),
			MangaTag("زمنكاني", "66", source),
			MangaTag("مغامرة", "67", source),
			MangaTag("طبي", "68", source),
			MangaTag("عصور وسطى", "69", source),
			MangaTag("ساموراي", "70", source),
			MangaTag("مافيا", "71", source),
			MangaTag("نظام", "72", source),
			MangaTag("هوس", "73", source),
			MangaTag("عصري", "74", source),
			MangaTag("بطل مجنون", "75", source),
			MangaTag("رعاية اطفال", "76", source),
			MangaTag("زواج مدبر", "77", source),
			MangaTag("تشويق", "78", source),
			MangaTag("مكتبي", "79", source),
			MangaTag("قوى خارقه", "80", source),
			MangaTag("تحقيق", "81", source),
			MangaTag("أيتام", "82", source),
			MangaTag("جوسين", "83", source),
			MangaTag("موسيقي", "84", source),
			MangaTag("قصة حقيقة", "85", source),
			MangaTag("موريم", "86", source),
			MangaTag("موظفين", "87", source),
			MangaTag("فيكتوري", "88", source),
			MangaTag("مأساوي", "89", source),
			MangaTag("عصر حديث", "90", source),
			MangaTag("ندم", "91", source),
			MangaTag("حياة جامعية", "92", source),
			MangaTag("حاصد", "93", source),
			MangaTag("الأرواح", "94", source),
			MangaTag("جريمة", "95", source),
			MangaTag("عاطفي", "96", source),
			MangaTag("أكاديمي", "97", source),
		)
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val chaptersDeferred = async { loadChapters(manga.url) }

		val coverUrl = doc.selectFirst("img[alt*='${manga.title}'], section img")?.src() ?: manga.coverUrl

		// Extract rating
		val ratingText = doc.selectFirst("div:contains(التقييم) + div, span:contains(Rating)")?.text()
		val rating = ratingText?.substringBefore("/")?.trim()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN

		// Extract status
		val statusText = doc.selectFirst("div:contains(الحالة) + div, span:contains(Status)")?.text()
		val state = when {
			statusText?.contains("مستمر", ignoreCase = true) == true ||
			statusText?.contains("ongoing", ignoreCase = true) == true -> MangaState.ONGOING
			statusText?.contains("مكتمل", ignoreCase = true) == true ||
			statusText?.contains("completed", ignoreCase = true) == true -> MangaState.FINISHED
			else -> null
		}

		// Extract description from meta tag
		val description = doc.selectFirst("meta[name=description]")?.attr("content")

		// Extract tags/genres
		val tags = doc.select("a[href*='/series/?genres='], span.genre").mapNotNullToSet { element ->
			val genreName = element.text().trim()
			val genreId = element.attr("href").substringAfter("genres=").substringBefore("&")
				.ifEmpty { genreName }

			if (genreName.isNotEmpty()) {
				MangaTag(
					key = genreId,
					title = genreName,
					source = source,
				)
			} else {
				null
			}
		}

		manga.copy(
			coverUrl = coverUrl,
			rating = rating,
			state = state,
			tags = tags,
			description = description,
			chapters = chaptersDeferred.await(),
		)
	}

	private suspend fun loadChapters(mangaUrl: String): List<MangaChapter> {
		val doc = webClient.httpGet(mangaUrl.toAbsoluteUrl(domain)).parseHtml()

		// Select all chapter links
		val chapterLinks = doc.select("a[href*='/chapter-']")
		println("[Azoramoon] Found ${chapterLinks.size} chapter links")

		return chapterLinks.mapIndexedNotNull { i, a ->
			val url = a.attrAsRelativeUrl("href")

			// Extract chapter number from URL (e.g., /series/back-to-spring/chapter-61 -> 61)
			val chapterNumber = url.substringAfterLast("/chapter-")
				.substringBefore("/")
				.toFloatOrNull() ?: (i + 1f)

			// Try to get chapter title from the div with title attribute or the span
			val chapterTitle = a.selectFirst("div[title]")?.attr("title")
				?: a.selectFirst("span.font-medium")?.text()
				?: "الفصل $chapterNumber"

			// Extract date from time element
			val timeElement = a.selectFirst("time")
			val dateText = timeElement?.attr("dateTime") ?: timeElement?.text()

			println("[Azoramoon] Chapter: $chapterTitle (number: $chapterNumber, url: $url)")

			MangaChapter(
				id = generateUid(url),
				title = chapterTitle,
				number = chapterNumber,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = 0, // Can't parse "8 days" format easily
				branch = null,
				source = source,
			)
		}.sortedBy { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		return doc.select("div.comic-images-wrapper img, div.chapter-images img, img[data-index]")
			.mapNotNull { img ->
				val imageUrl = img.attr("data-src").ifEmpty { img.src() }
				if (imageUrl?.isNotBlank() == true && !imageUrl.startsWith("data:image")) {
					val finalUrl = imageUrl.toRelativeUrl(domain)
					MangaPage(
						id = generateUid(finalUrl),
						url = finalUrl,
						preview = null,
						source = source,
					)
				} else {
					null
				}
			}
			.distinct()
	}

}
