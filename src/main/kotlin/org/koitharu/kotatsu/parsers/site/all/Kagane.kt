package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.webview.InterceptedRequest
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import org.koitharu.kotatsu.parsers.webview.WebViewRequestInterceptor
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("KAGANE", "Kagane")
internal class Kagane(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KAGANE, pageSize = 35),
    Interceptor,
    WebViewRequestInterceptor {

    override val configKeyDomain = ConfigKey.Domain("kagane.org")
    private val apiUrl = "https://api.kagane.org"

    // Thread-safe token storage
    @Volatile private var accessToken: String? = null

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true
        )

    override val client = webClient.newBuilder()
        .addInterceptor(this)
        .build()

    // ================= WEBVIEW INTERCEPTION =================
    // Configured to match the definition in org.koitharu.kotatsu.parsers.webview.InterceptionConfig
    override val webViewInterceptionConfig = InterceptionConfig(
        timeoutMs = 20000,
        // Match the API image endpoint which contains the token
        urlPattern = Regex(""".*/api/v1/books/.*/file/.*\?token=.*"""),
        // Scroll down to ensure images (and their requests) are triggered
        pageScript = "window.scrollTo(0, document.body.scrollHeight);"
    )

    override fun shouldCaptureRequest(request: InterceptedRequest): Boolean {
        // Double check: Capture if it matches the pattern and has a token
        return request.url.contains("token=") && request.url.contains("/file/")
    }

    override fun onInterceptionComplete(capturedRequests: List<InterceptedRequest>) {
        // Extract the token from the first valid intercepted request
        val validRequest = capturedRequests.firstOrNull() ?: return
        val token = validRequest.getQueryParameter("token")
        if (!token.isNullOrEmpty()) {
            accessToken = token
        }
    }

    override fun onInterceptionError(error: Throwable) {
        error.printStackTrace()
    }
    // ========================================================

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = listOf(
            "Romance", "Drama", "Manhwa", "Fantasy", "Manga", "Comedy", "Action", "Mature",
            "Shoujo", "Josei", "Shounen", "Slice of Life", "Seinen", "Adventure", "Manhua",
            "School Life", "Smut", "Yaoi", "Hentai", "Historical", "Isekai", "Mystery",
            "Psychological", "Tragedy", "Harem", "Martial Arts", "Sci-Fi", "Ecchi", "Horror"
        ).map { MangaTag(it, it, source) }.toSet()

        return MangaListFilterOptions(
            availableTags = genres,
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT)
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sortParam = when (order) {
            SortOrder.UPDATED -> "updated_at,desc"
            SortOrder.POPULARITY -> "total_views,desc"
            SortOrder.NEWEST -> "created_at,desc"
            SortOrder.ALPHABETICAL -> "series_name,asc"
            else -> "updated_at,desc"
        }

        val url = "$apiUrl/api/v1/search?page=${page - 1}&size=$pageSize&sort=$sortParam"
        val jsonBody = JSONObject()

        if (filter.tags.isNotEmpty()) {
            val genresArr = JSONArray()
            filter.tags.forEach { genresArr.put(it.key) }
            val inclusiveObj = JSONObject()
            inclusiveObj.put("values", genresArr)
            inclusiveObj.put("match_all", false)
            jsonBody.put("inclusive_genres", inclusiveObj)
        }

        val requestUrl = if (!filter.query.isNullOrEmpty()) "$url&name=${filter.query.urlEncoded()}" else url
        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())

        val response = client.newCall(
            Request.Builder()
                .url(requestUrl)
                .post(requestBody)
                .addHeader("Origin", "https://$domain")
                .addHeader("Referer", "https://$domain/")
                .build()
        ).await().parseJson()

        val content = response.optJSONArray("content") ?: return emptyList()

        return (0 until content.length()).map { i ->
            val item = content.getJSONObject(i)
            val id = item.getString("id")
            val name = item.getString("name")
            val src = item.optString("source")
            val title = if (src.isNotEmpty()) "$name [$src]" else name

            Manga(
                id = generateUid(id),
                url = id,
                publicUrl = "https://$domain/series/$id",
                coverUrl = "$apiUrl/api/v1/series/$id/thumbnail",
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.SAFE
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val seriesId = manga.url
        val url = "$apiUrl/api/v1/series/$seriesId"
        val json = webClient.httpGet(url).parseJson()

        val desc = StringBuilder()
        json.optString("summary").takeIf { it.isNotEmpty() }?.let { desc.append(it).append("\n\n") }
        val sourceStr = json.optString("source")
        if (sourceStr.isNotEmpty()) desc.append("Source: $sourceStr\n")

        val statusStr = json.optString("status")
        val state = when (statusStr.uppercase()) {
            "ONGOING" -> MangaState.ONGOING
            "ENDED" -> MangaState.FINISHED
            "HIATUS" -> MangaState.PAUSED
            else -> null
        }

        val genres = json.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        val authors = json.optJSONArray("authors")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } ?: emptySet()

        val booksUrl = "$apiUrl/api/v1/books/$seriesId"
        val booksJson = webClient.httpGet(booksUrl).parseJson()
        val content = booksJson.optJSONArray("content") ?: JSONArray()

        val chapters = ArrayList<MangaChapter>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

        for (i in 0 until content.length()) {
            val ch = content.getJSONObject(i)
            val chId = ch.getString("id")
            val chTitle = ch.optString("title")
            val number = ch.optDouble("number_sort", 0.0).toFloat()
            val dateStr = ch.optString("release_date")
            val pageCount = ch.optInt("pages_count", 0)

            // Pack metadata: "seriesId;chapterId;pageCount"
            val packedUrl = "$seriesId;$chId;$pageCount"

            chapters.add(
                MangaChapter(
                    id = generateUid(chId),
                    title = chTitle,
                    number = number,
                    volume = 0,
                    url = packedUrl,
                    uploadDate = try { dateFormat.parse(dateStr)?.time ?: 0L } catch (e: Exception) { 0L },
                    source = source,
                    scanlator = null,
                    branch = null
                )
            )
        }

        return manga.copy(
            description = desc.toString(),
            state = state,
            authors = authors,
            tags = genres.map { MangaTag(it, it, source) }.toSet(),
            chapters = chapters.reversed()
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split(";")
        if (parts.size != 3) throw Exception("Invalid chapter URL format")
        val seriesId = parts[0]
        val chapterId = parts[1]
        val pageCount = parts[2].toInt()

        val cacheUrl = "https://yukine.$domain"
        
        // Use the intercepted token if available
        val token = accessToken ?: ""

        return (1..pageCount).map { index ->
            val imageUrl = "$cacheUrl/api/v1/books/$seriesId/file/$chapterId/page_$index.jpg?token=$token&index=$index"
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }

    // ================= IMAGE INTERCEPTOR =================
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (url.pathSegments.contains("file")) {
            val seriesId = url.pathSegments[3]
            val chapterId = url.pathSegments[5]
            val indexStr = url.queryParameter("index") ?: "0"
            val index = indexStr.toInt()

            val response = chain.proceed(request)
            
            // If request fails (401/403) and we have a new token, try one retry
            if (!response.isSuccessful && response.code in 401..403) {
                val currentToken = accessToken
                if (!currentToken.isNullOrEmpty() && request.url.queryParameter("token") != currentToken) {
                    response.close()
                    val newUrl = request.url.newBuilder().setQueryParameter("token", currentToken).build()
                    val newRequest = request.newBuilder().url(newUrl).build()
                    return intercept(chain.withConnectTimeout(chain.connectTimeoutMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                                        .withReadTimeout(chain.readTimeoutMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                                        .withWriteTimeout(chain.writeTimeoutMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                }
                return response
            }

            if (!response.isSuccessful) return response

            val encryptedBytes = response.body?.bytes() ?: return response

            val decrypted = decryptImage(encryptedBytes, seriesId, chapterId)
                ?: throw Exception("Failed to decrypt image")

            val finalBytes = processData(decrypted, index, seriesId, chapterId)
                ?: throw Exception("Failed to unscramble image")

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(finalBytes.toResponseBody("image/jpeg".toMediaType()))
                .build()
        }
        return chain.proceed(request)
    }

    private fun decryptImage(payload: ByteArray, keyPart1: String, keyPart2: String): ByteArray? {
        return try {
            if (payload.size < 140) return null
            val iv = payload.copyOfRange(128, 140)
            val ciphertext = payload.copyOfRange(140, payload.size)
            val keyHash = sha256("$keyPart1:$keyPart2")
            val keySpec = SecretKeySpec(keyHash, "AES")
            val spec = GCMParameterSpec(128, iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) { null }
    }

    private fun sha256(str: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(str.toByteArray(StandardCharsets.UTF_8))

    private fun processData(input: ByteArray, index: Int, seriesId: String, chapterId: String): ByteArray? {
        if (isValidImage(input)) return input
        try {
            val filename = "%04d.jpg".format(index)
            val seed = generateSeed(seriesId, chapterId, filename)
            val scrambler = Scrambler(seed, 10)
            val mapping = scrambler.getScrambleMapping()
            val unscrambled = unscramble(input, mapping, true)
            return if (isValidImage(unscrambled)) unscrambled else null
        } catch (e: Exception) { return null }
    }

    private fun isValidImage(data: ByteArray): Boolean {
        if (data.size < 12) return false
        if (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) return true
        if (data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() && data[2] == 'N'.code.toByte()) return true
        if (data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() && data[10] == 'B'.code.toByte()) return true
        return false
    }

    private fun generateSeed(t: String, n: String, e: String): BigInteger {
        val hash = sha256("$t:$n:$e")
        var a = BigInteger.ZERO
        for (i in 0 until 8) {
            val byteVal = (hash[i].toInt() and 0xFF).toLong()
            a = a.shiftLeft(8).or(BigInteger.valueOf(byteVal))
        }
        return a
    }

    private fun unscramble(data: ByteArray, mapping: List<Pair<Int, Int>>, n: Boolean): ByteArray {
        val s = mapping.size
        val a = data.size
        val l = a / s
        val o = a % s
        val (r, i) = if (n) {
            if (o > 0) Pair(data.copyOfRange(0, o), data.copyOfRange(o, a)) else Pair(ByteArray(0), data)
        } else {
            if (o > 0) Pair(data.copyOfRange(a - o, a), data.copyOfRange(0, a - o)) else Pair(ByteArray(0), data)
        }
        val chunks = (0 until s).map { idx ->
            val start = idx * l
            val end = (idx + 1) * l
            i.copyOfRange(start, end)
        }
        val u = Array(s) { ByteArray(0) }
        for ((e, m) in mapping) {
            if (e < s && m < s) {
                if (n) u[e] = chunks[m] else u[m] = chunks[e]
            }
        }
        return u.fold(ByteArray(0)) { acc, chunk -> acc + chunk } + r
    }

    // --- Scrambler Classes ---
    private class Scrambler(private val seed: BigInteger, private val gridSize: Int) {
        private val totalPieces: Int = gridSize * gridSize
        private val randomizer: Randomizer = Randomizer(seed, gridSize)
        private val dependencyGraph: DependencyGraph
        private val scramblePath: List<Int>

        init {
            dependencyGraph = buildDependencyGraph()
            scramblePath = generateScramblePath()
        }

        private data class DependencyGraph(val graph: MutableMap<Int, MutableList<Int>>, val inDegree: MutableMap<Int, Int>)

        private fun buildDependencyGraph(): DependencyGraph {
            val graph = mutableMapOf<Int, MutableList<Int>>()
            val inDegree = mutableMapOf<Int, Int>()
            for (n in 0 until totalPieces) { inDegree[n] = 0; graph[n] = mutableListOf() }
            val rng = Randomizer(seed, gridSize)
            for (r in 0 until totalPieces) {
                val i = (rng.prng() % BigInteger.valueOf(3) + BigInteger.valueOf(2)).toInt()
                repeat(i) {
                    val j = (rng.prng() % BigInteger.valueOf(totalPieces.toLong())).toInt()
                    if (j != r && !wouldCreateCycle(graph, j, r)) {
                        graph[j]!!.add(r); inDegree[r] = inDegree[r]!! + 1
                    }
                }
            }
            for (r in 0 until totalPieces) {
                if (inDegree[r] == 0) {
                    var tries = 0
                    while (tries < 10) {
                        val s = (rng.prng() % BigInteger.valueOf(totalPieces.toLong())).toInt()
                        if (s != r && !wouldCreateCycle(graph, s, r)) {
                            graph[s]!!.add(r); inDegree[r] = inDegree[r]!! + 1; break
                        }
                        tries++
                    }
                }
            }
            return DependencyGraph(graph, inDegree)
        }

        private fun wouldCreateCycle(graph: Map<Int, List<Int>>, target: Int, start: Int): Boolean {
            val visited = mutableSetOf<Int>()
            val stack = ArrayDeque<Int>(); stack.add(start)
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (n == target) return true
                if (!visited.add(n)) continue
                graph[n]?.let { stack.addAll(it) }
            }
            return false
        }

        private fun generateScramblePath(): List<Int> {
            val graphCopy = dependencyGraph.graph.mapValues { it.value.toMutableList() }.toMutableMap()
            val inDegreeCopy = dependencyGraph.inDegree.toMutableMap()
            val queue = ArrayDeque<Int>()
            for (n in 0 until totalPieces) { if (inDegreeCopy[n] == 0) queue.add(n) }
            val order = mutableListOf<Int>()
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst(); order.add(i)
                graphCopy[i]?.forEach { e -> inDegreeCopy[e] = inDegreeCopy[e]!! - 1; if (inDegreeCopy[e] == 0) queue.add(e) }
            }
            return order
        }

        fun getScrambleMapping(): List<Pair<Int, Int>> {
            var e = randomizer.order.toMutableList()
            if (scramblePath.size == totalPieces) {
                val t = IntArray(totalPieces); for (i in scramblePath.indices) t[i] = scramblePath[i]
                val n = IntArray(totalPieces); for (r in 0 until totalPieces) n[r] = e[t[r]]
                e = n.toMutableList()
            }
            return (0 until totalPieces).map { it to e[it] }
        }
    }

    private class Randomizer(seedInput: BigInteger, t: Int) {
        val size: Int = t * t
        val seed: BigInteger
        private var state: BigInteger
        private val entropyPool: ByteArray
        val order: MutableList<Int>
        companion object {
            private val MASK64 = BigInteger("FFFFFFFFFFFFFFFF", 16)
            private val MASK32 = BigInteger("FFFFFFFF", 16)
            private val MASK8 = BigInteger("FF", 16)
            private val PRNG_MULT = BigInteger("27BB2EE687B0B0FD", 16)
            private val RND_MULT_32 = BigInteger("45d9f3b", 16)
        }
        init {
            val seedMask = BigInteger("FFFFFFFFFFFFFFFF", 16)
            seed = seedInput.and(seedMask)
            state = hashSeed(seed)
            entropyPool = expandEntropy(seed)
            order = MutableList(size) { it }; permute()
        }
        private fun hashSeed(e: BigInteger): BigInteger {
            val md = sha256(e.toString())
            return readBigUInt64BE(md, 0).xor(readBigUInt64BE(md, 8))
        }
        private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8))
        private fun readBigUInt64BE(bytes: ByteArray, offset: Int): BigInteger {
            var n = BigInteger.ZERO
            for (i in 0 until 8) n = n.shiftLeft(8).or(BigInteger.valueOf((bytes[offset + i].toInt() and 0xFF).toLong()))
            return n
        }
        private fun expandEntropy(e: BigInteger): ByteArray = MessageDigest.getInstance("SHA-512").digest(e.toString().toByteArray(StandardCharsets.UTF_8))
        private fun sbox(e: Int): Int {
            val t = intArrayOf(163, 95, 137, 13, 55, 193, 107, 228, 114, 185, 22, 243, 68, 218, 158, 40)
            return t[e and 15] xor t[e shr 4 and 15]
        }
        fun prng(): BigInteger {
            state = state.xor(state.shiftLeft(11).and(MASK64))
            state = state.xor(state.shiftRight(19))
            state = state.xor(state.shiftLeft(7).and(MASK64))
            state = state.multiply(PRNG_MULT).and(MASK64)
            return state
        }
        private fun roundFunc(e: BigInteger, t: Int): BigInteger {
            var n = e.xor(prng()).xor(BigInteger.valueOf(t.toLong()))
            val rot = n.shiftLeft(5).or(n.shiftRight(3)).and(MASK32)
            n = rot.multiply(RND_MULT_32).and(MASK32)
            val sboxVal = sbox(n.and(MASK8).toInt())
            n = n.xor(BigInteger.valueOf(sboxVal.toLong())).xor(n.shiftRight(13))
            return n
        }
        private fun feistelMix(e: Int, t: Int, rounds: Int): Pair<BigInteger, BigInteger> {
            var r = BigInteger.valueOf(e.toLong()); var i = BigInteger.valueOf(t.toLong())
            for (round in 0 until rounds) {
                val ent = entropyPool[round % entropyPool.size].toInt() and 0xFF
                r = r.xor(roundFunc(i, ent))
                val secondArg = ent xor (round * 31 and 255)
                i = i.xor(roundFunc(r, secondArg))
            }
            return Pair(r, i)
        }
        private fun permute() {
            val half = size / 2; val sizeBig = BigInteger.valueOf(size.toLong())
            for (t in 0 until half) {
                val n = t + half
                val (rBig, iBig) = feistelMix(t, n, 4)
                val s = rBig.mod(sizeBig).toInt(); val a = iBig.mod(sizeBig).toInt()
                val tmp = order[s]; order[s] = order[a]; order[a] = tmp
            }
            for (e in size - 1 downTo 1) {
                val ent = entropyPool[e % entropyPool.size].toInt() and 0xFF
                val idxBig = prng().add(BigInteger.valueOf(ent.toLong())).mod(BigInteger.valueOf((e + 1).toLong()))
                val n = idxBig.toInt(); val tmp = order[e]; order[e] = order[n]; order[n] = tmp
            }
        }
    }
}