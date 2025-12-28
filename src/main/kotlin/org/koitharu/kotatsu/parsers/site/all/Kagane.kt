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
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.webview.InterceptedRequest
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("KAGANE", "Kagane")
internal class Kagane(context: MangaLoaderContext) : 
    PagedMangaParser(context, MangaParserSource.KAGANE, pageSize = 35) {

    override val configKeyDomain = ConfigKey.Domain("kagane.org")
    private val apiUrl = "https://api.kagane.org"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true
        )

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

        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()

        val responseObj = context.httpClient.newCall(
            Request.Builder()
                .url(requestUrl)
                .post(requestBody)
                .headers(headers)
                .build()
        ).await()

        val responseBody = responseObj.body?.string() ?: ""
        if (!responseObj.isSuccessful) {
             throw Exception("Search error ${responseObj.code}: $responseBody")
        }

        val response = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            throw Exception("Invalid JSON search response: $responseBody")
        }

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
        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()
        val resp = webClient.httpGet(url, headers)
        val respBody = resp.body?.string() ?: ""
        if (!resp.isSuccessful) throw Exception("Details error ${resp.code}: $respBody")
        val json = try { JSONObject(respBody) } catch(e: Exception) { throw Exception("Invalid JSON details: $respBody") }

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
        val booksResp = webClient.httpGet(booksUrl, headers)
        val booksBody = booksResp.body?.string() ?: ""
        if (!booksResp.isSuccessful) throw Exception("Chapter list error ${booksResp.code}: $booksBody")
        val booksJson = try { JSONObject(booksBody) } catch(e: Exception) { throw Exception("Invalid JSON chapters: $booksBody") }
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

            chapters.add(
                MangaChapter(
                    id = generateUid(chId),
                    title = chTitle,
                    number = number,
                    volume = 0,
                    // Store URL for WebView
                    url = "/series/$seriesId/$chId?pages=$pageCount",
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
        val uri = URI(chapter.url)
        val pathParts = uri.path.split("/").filter { it.isNotEmpty() }
        if (pathParts.size < 3) throw Exception("Invalid chapter URL format: ${chapter.url}")
        
        val seriesId = pathParts[1]
        val chapterId = pathParts[2]
        val query = uri.query ?: ""
        val pageCount = query.split("&")
            .find { it.startsWith("pages=") }
            ?.substringAfter("=")
            ?.toIntOrNull() ?: throw Exception("Missing page count in URL")

        // 1. Fetch certificate (Widevine)
        val cert = getCertificate()
        
        // 2. Generate PSSH
        val pssh = getPssh(seriesId, chapterId)
        
        // 3. Construct JS
        val script = """
            (async function() {
                try {
                    const certBase64 = "$cert";
                    const psshBase64 = "$pssh";
                    const binaryString = atob(certBase64);
                    const bytes = new Uint8Array(binaryString.length);
                    for (var i = 0; i < binaryString.length; i++) {
                        bytes[i] = binaryString.charCodeAt(i);
                    }
                    const serverCert = bytes.buffer;

                    const config = [{
                        initDataTypes: ["cenc"],
                        audioCapabilities: [],
                        videoCapabilities: [{ contentType: 'video/mp4; codecs="avc1.42E01E"' }]
                    }];
                    
                    let access;
                    try {
                        access = await navigator.requestMediaKeySystemAccess("com.widevine.alpha", config);
                    } catch (e) {
                        access = await navigator.requestMediaKeySystemAccess("com.widevine.alpha", config);
                    }
                    
                    const mediaKeys = await access.createMediaKeys();
                    await mediaKeys.setServerCertificate(serverCert);
                    
                    const session = mediaKeys.createSession();
                    
                    const psshBinary = atob(psshBase64);
                    const psshBytes = new Uint8Array(psshBinary.length);
                    for (var i = 0; i < psshBinary.length; i++) {
                        psshBytes[i] = psshBinary.charCodeAt(i);
                    }
                    
                    const promise = new Promise((resolve, reject) => {
                        session.addEventListener("message", (event) => {
                             resolve(event.message);
                        });
                        session.addEventListener("error", (err) => {
                             reject(err);
                        });
                    });
                    
                    await session.generateRequest("cenc", psshBytes.buffer);
                    const message = await promise;
                    
                    // Convert ArrayBuffer to Base64
                    let binary = '';
                    const bytesMsg = new Uint8Array(message);
                    for (let i = 0; i < bytesMsg.byteLength; i++) {
                        binary += String.fromCharCode(bytesMsg[i]);
                    }
                    const challenge = btoa(binary);
                    
                    // Return challenge
                    window.location.href = "https://kotatsu.intercept/result?challenge=" + encodeURIComponent(challenge);
                    
                } catch (e) {
                    window.location.href = "https://kotatsu.intercept/error?msg=" + encodeURIComponent(e.toString());
                }
            })();
        """.trimIndent()

        // 4. Intercept to get challenge
        val config = InterceptionConfig(
            timeoutMs = 60000,
            urlPattern = Regex("https://kotatsu\\.intercept/.*", RegexOption.IGNORE_CASE),
            pageScript = script
        )
        
        val interceptUrl = "https://$domain/series/$seriesId/$chapterId"
        val requests = context.interceptWebViewRequests(interceptUrl, config)
        
        val resultRequest = requests.firstOrNull() ?: throw Exception("Failed to intercept DRM challenge")
        
        if (resultRequest.url.contains("/error")) {
            val errorMsg = resultRequest.getQueryParameter("msg") ?: "Unknown error"
            throw Exception("DRM JS Error: $errorMsg")
        }
        
        val challengeEncoded = resultRequest.getQueryParameter("challenge") ?: throw Exception("No challenge in interception")
        val challenge = URLDecoder.decode(challengeEncoded, StandardCharsets.UTF_8.name())

        // 5. POST to API to get token
        val challengeUrl = "$apiUrl/api/v1/books/$seriesId/file/$chapterId"
        val jsonBody = JSONObject()
        jsonBody.put("challenge", challenge)
        
        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()
            
        val response = context.httpClient.newCall(
            Request.Builder()
                .url(challengeUrl)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .headers(headers)
                .build()
        ).await()

        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw Exception("Failed to get token: ${response.code} $responseBody")
        }

        val tokenResponse = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            throw Exception("Invalid JSON token response: $responseBody")
        }
        
        val token = tokenResponse.getString("access_token")
        val currentCacheUrl = tokenResponse.getString("cache_url")
        val mappingJson = tokenResponse.optJSONObject("page_mapping")
        
        val mapping = mutableMapOf<Int, String>()
        mappingJson?.keys()?.forEach { key ->
            val idx = key.toIntOrNull()
            if (idx != null) mapping[idx] = mappingJson.getString(key)
        }

        return (0 until pageCount).map { index ->
            val pageIndex = index + 1
            val fileId = mapping[pageIndex] ?: "page_$pageIndex.jpg"
            
            // Embed token and indices in URL for the interceptor
            val imageUrl = "$currentCacheUrl/api/v1/books/$seriesId/file/$chapterId/$fileId?token=$token&index=$pageIndex"
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }

    private var cachedCert: String? = null
    
    private suspend fun getCertificate(): String {
        cachedCert?.let { return it }
        val url = "$apiUrl/api/v1/static/bin.bin"
        val req = Request.Builder().url(url)
            .addHeader("Origin", "https://$domain")
            .addHeader("Referer", "https://$domain/")
            .build()
            
        val bytes = context.httpClient.newCall(req).await().body?.bytes()
            ?: throw Exception("Failed to fetch certificate")
        
        val b64 = Base64.getEncoder().encodeToString(bytes)
        cachedCert = b64
        return b64
    }
    
    private fun getPssh(seriesId: String, chapterId: String): String {
        val hash = "$seriesId:$chapterId".sha256().copyOfRange(0, 16)
        
        // Widevine System ID
        val systemId = Base64.getDecoder().decode("7e+LqXnWSs6jyCfc1R0h7Q==")
        val zeroes = ByteArray(4)
        
        // Header: 18 (byte), hash.size (byte) + hash
        val header = byteArrayOf(18, hash.size.toByte()) + hash
        val headerSize = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(header.size).array()
        
        val innerBox = zeroes + systemId + headerSize + header
        
        val outerSize = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(innerBox.size + 8).array()
        val psshTag = "pssh".toByteArray(StandardCharsets.UTF_8)
        
        val fullBox = outerSize + psshTag + innerBox
        return Base64.getEncoder().encodeToString(fullBox)
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(this.size + other.size)
        System.arraycopy(this, 0, result, 0, this.size)
        System.arraycopy(other, 0, result, this.size, other.size)
        return result
    }

    // Interceptor logic for image decryption
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (url.queryParameterNames.contains("token") && url.encodedPath.contains("/file/")) {
            val pathSegments = url.pathSegments
            // Expected path: .../books/{seriesId}/file/{chapterId}/
            // But we need seriesId and chapterId.
            // Let's find "books" and take next segments.
            val booksIndex = pathSegments.indexOf("books")
            if (booksIndex != -1 && booksIndex + 3 < pathSegments.size) {
                val seriesId = pathSegments[booksIndex + 1]
                val chapterId = pathSegments[booksIndex + 3]
                val indexStr = url.queryParameter("index")
                
                if (indexStr != null) {
                    val index = indexStr.toInt()
                    val imageResp = chain.proceed(chain.request())
                    if (!imageResp.isSuccessful) return imageResp
                    
                    val imageBytes = imageResp.body?.bytes() ?: return imageResp
                    
                    try {
                        val decrypted = decryptImage(imageBytes, seriesId, chapterId)
                            ?: throw Exception("Unable to decrypt data")
                        val unscrambled = processData(decrypted, index, seriesId, chapterId)
                            ?: throw Exception("Unable to unscramble data")
                        
                        return imageResp.newBuilder()
                            .body(unscrambled.toResponseBody(imageResp.body?.contentType()))
                            .build()
                    } catch (e: Exception) {
                        // Return original if decryption fails (or throw?)
                        // e.printStackTrace() 
                        // Often better to return error so it can be debugged, 
                        // but sometimes original content might be unencrypted (unlikely here)
                        return imageResp
                    }
                }
            }
        }
        return chain.proceed(chain.request())
    }
    
    // Decryption helpers
    
    private data class WordArray(val words: IntArray, val sigBytes: Int)

    private fun wordArrayToBytes(e: WordArray): ByteArray {
        val result = ByteArray(e.sigBytes)
        for (i in 0 until e.sigBytes) {
            val word = e.words[i ushr 2]
            val shift = 24 - (i % 4) * 8
            result[i] = ((word ushr shift) and 0xFF).toByte()
        }
        return result
    }

    private fun aesGcmDecrypt(keyWordArray: WordArray, ivWordArray: WordArray, cipherWordArray: WordArray): ByteArray? {
        return try {
            val keyBytes = wordArrayToBytes(keyWordArray)
            val iv = wordArrayToBytes(ivWordArray)
            val cipherBytes = wordArrayToBytes(cipherWordArray)

            val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)

            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            cipher.doFinal(cipherBytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun toWordArray(bytes: ByteArray): WordArray {
        val words = IntArray((bytes.size + 3) / 4)
        for (i in bytes.indices) {
            val wordIndex = i / 4
            val shift = 24 - (i % 4) * 8
            words[wordIndex] = words[wordIndex] or ((bytes[i].toInt() and 0xFF) shl shift)
        }
        return WordArray(words, bytes.size)
    }

    private fun decryptImage(payload: ByteArray, keyPart1: String, keyPart2: String): ByteArray? {
        return try {
            if (payload.size < 140) return null

            val iv = payload.sliceArray(128 until 140)
            val ciphertext = payload.sliceArray(140 until payload.size)

            val keyHash = "$keyPart1:$keyPart2".sha256()

            val keyWA = toWordArray(keyHash)
            val ivWA = toWordArray(iv)
            val cipherWA = toWordArray(ciphertext)

            aesGcmDecrypt(keyWA, ivWA, cipherWA)
        } catch (_: Exception) {
            null
        }
    }

    private fun processData(input: ByteArray, index: Int, seriesId: String, chapterId: String): ByteArray? {
        fun isValidImage(data: ByteArray): Boolean {
            return when {
                // JPEG
                data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> true
                // GIF
                data.size >= 6 && (
                    data.copyOfRange(0, 6).contentEquals("GIF87a".toByteArray()) ||
                        data.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray())
                    ) -> true
                // PNG
                data.size >= 8 && data.copyOfRange(0, 8).contentEquals(
                    byteArrayOf(
                        0x89.toByte(),
                        'P'.code.toByte(),
                        'N'.code.toByte(),
                        'G'.code.toByte(),
                        0x0D, 0x0A, 0x1A, 0x0A,
                    )
                ) -> true
                // WEBP
                data.size >= 12 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
                    data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
                    data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() &&
                    data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte() -> true
                // HEIF
                data.size >= 12 && data.copyOfRange(4, 8).contentEquals("ftyp".toByteArray()) -> {
                    val type = data.copyOfRange(8, 11)
                    type.contentEquals("hei".toByteArray()) ||
                        type.contentEquals("hev".toByteArray()) ||
                        type.contentEquals("avi".toByteArray())
                }
                // JXL
                data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0x0A.toByte() -> true
                data.size >= 12 && data.copyOfRange(0, 8).contentEquals(
                    byteArrayOf(
                        0,
                        0,
                        0,
                        12,
                        'J'.code.toByte(),
                        'X'.code.toByte(),
                        'L'.code.toByte(),
                        ' '.code.toByte(),
                    ),
                ) -> true
                else -> false
            }
        }

        try {
            var processed: ByteArray = input

            if (!isValidImage(processed)) {
                val seed = generateSeed(seriesId, chapterId, "%04d.jpg".format(index))
                val scrambler = Scrambler(seed, 10)
                val scrambleMapping = scrambler.getScrambleMapping()
                processed = unscramble(processed, scrambleMapping, true)
                if (!isValidImage(processed)) return null
            }

            return processed
        } catch (_: Exception) {
            return null
        }
    }

    private fun generateSeed(t: String, n: String, e: String): BigInteger {
        val sha256 = "$t:$n:$e".sha256()
        var a = BigInteger.ZERO
        for (i in 0 until 8) {
            a = a.shiftLeft(8).or(BigInteger.valueOf((sha256[i].toInt() and 0xFF).toLong()))
        }
        return a
    }

    private fun unscramble(data: ByteArray, mapping: List<Pair<Int, Int>>, n: Boolean): ByteArray {
        val s = mapping.size
        val a = data.size
        val l = a / s
        val o = a % s

        val (r, i) = if (n) {
            if (o > 0) {
                Pair(data.copyOfRange(0, o), data.copyOfRange(o, a))
            } else {
                Pair(ByteArray(0), data)
            }
        } else {
            if (o > 0) {
                Pair(data.copyOfRange(a - o, a), data.copyOfRange(0, a - o))
            } else {
                Pair(ByteArray(0), data)
            }
        }

        val chunks = (0 until s).map {
            val start = it * l
            val end = (it + 1) * l
            i.copyOfRange(start, end)
        }.toMutableList()

        val u = Array(s) { ByteArray(0) }

        if (n) {
            for ((e, m) in mapping) {
                if (e < s && m < s) {
                    u[e] = chunks[m]
                }
            }
        } else {
            for ((e, m) in mapping) {
                if (e < s && m < s) {
                    u[m] = chunks[e]
                }
            }
        }

        val h = u.fold(ByteArray(0)) { acc, chunk -> acc + chunk }

        return if (n) {
            h + r
        } else {
            r + h
        }
    }
    
    private class Scrambler(private val seed: BigInteger, private val gridSize: Int) {
        private val totalPieces: Int = gridSize * gridSize
        private val randomizer: Randomizer = Randomizer(seed, gridSize)
        private val dependencyGraph: DependencyGraph
        private val scramblePath: List<Int>

        init {
            dependencyGraph = buildDependencyGraph()
            scramblePath = generateScramblePath()
        }

        private data class DependencyGraph(
            val graph: MutableMap<Int, MutableList<Int>>,
            val inDegree: MutableMap<Int, Int>,
        )

        private fun buildDependencyGraph(): DependencyGraph {
            val graph = mutableMapOf<Int, MutableList<Int>>()
            val inDegree = mutableMapOf<Int, Int>()

            for (n in 0 until totalPieces) {
                inDegree[n] = 0
                graph[n] = mutableListOf()
            }

            val rng = Randomizer(seed, gridSize)

            for (r in 0 until totalPieces) {
                val i = (rng.prng() % BigInteger.valueOf(3) + BigInteger.valueOf(2)).toInt()
                repeat(i) {
                    val j = (rng.prng() % BigInteger.valueOf(totalPieces.toLong())).toInt()
                    if (j != r && !wouldCreateCycle(graph, j, r)) {
                        graph[j]!!.add(r)
                        inDegree[r] = inDegree[r]!! + 1
                    }
                }
            }

            for (r in 0 until totalPieces) {
                if (inDegree[r] == 0) {
                    var tries = 0
                    while (tries < 10) {
                        val s = (rng.prng() % BigInteger.valueOf(totalPieces.toLong())).toInt()
                        if (s != r && !wouldCreateCycle(graph, s, r)) {
                            graph[s]!!.add(r)
                            inDegree[r] = inDegree[r]!! + 1
                            break
                        }
                        tries++
                    }
                }
            }

            return DependencyGraph(graph, inDegree)
        }

        private fun wouldCreateCycle(graph: Map<Int, List<Int>>, target: Int, start: Int): Boolean {
            val visited = mutableSetOf<Int>()
            val stack = ArrayDeque<Int>()
            stack.add(start)

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
            for (n in 0 until totalPieces) {
                if (inDegreeCopy[n] == 0) {
                    queue.add(n)
                }
            }

            val order = mutableListOf<Int>()
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                order.add(i)
                val neighbors = graphCopy[i]
                if (neighbors != null) {
                    for (e in neighbors) {
                        inDegreeCopy[e] = inDegreeCopy[e]!! - 1
                        if (inDegreeCopy[e] == 0) {
                            queue.add(e)
                        }
                    }
                }
            }
            return order
        }

        fun getScrambleMapping(): List<Pair<Int, Int>> {
            var e = randomizer.order.toMutableList()

            if (scramblePath.size == totalPieces) {
                val t = Array(totalPieces) { 0 }
                for (i in scramblePath.indices) {
                    t[i] = scramblePath[i]
                }
                val n = Array(totalPieces) { 0 }
                for (r in 0 until totalPieces) {
                    n[r] = e[t[r]]
                }
                e = n.toMutableList()
            }

            val result = mutableListOf<Pair<Int, Int>>()
            for (n in 0 until totalPieces) {
                result.add(n to e[n])
            }
            return result
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
            order = MutableList(size) { it }
            permute()
        }

        private fun hashSeed(e: BigInteger): BigInteger {
            val md = e.toString().sha256()
            return readBigUInt64BE(md, 0).xor(readBigUInt64BE(md, 8))
        }

        private fun readBigUInt64BE(bytes: ByteArray, offset: Int): BigInteger {
            var n = BigInteger.ZERO
            for (i in 0 until 8) {
                n = n.shiftLeft(8).or(BigInteger.valueOf((bytes[offset + i].toInt() and 0xFF).toLong()))
            }
            return n
        }

        private fun expandEntropy(e: BigInteger): ByteArray =
            MessageDigest.getInstance("SHA-512").digest(e.toString().toByteArray(StandardCharsets.UTF_8))

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
            n = n.xor(BigInteger.valueOf(sboxVal.toLong()))

            n = n.xor(n.shiftRight(13))
            return n
        }

        private fun feistelMix(e: Int, t: Int, rounds: Int): Pair<BigInteger, BigInteger> {
            var r = BigInteger.valueOf(e.toLong())
            var i = BigInteger.valueOf(t.toLong())
            for (round in 0 until rounds) {
                val ent = entropyPool[round % entropyPool.size].toInt() and 0xFF
                r = r.xor(roundFunc(i, ent))
                val secondArg = ent xor (round * 31 and 255)
                i = i.xor(roundFunc(r, secondArg))
            }
            return Pair(r, i)
        }

        private fun permute() {
            val half = size / 2
            val sizeBig = BigInteger.valueOf(size.toLong())

            for (t in 0 until half) {
                val n = t + half
                val (rBig, iBig) = feistelMix(t, n, 4)
                val s = rBig.mod(sizeBig).toInt()
                val a = iBig.mod(sizeBig).toInt()
                val tmp = order[s]
                order[s] = order[a]
                order[a] = tmp
            }

            for (e in size - 1 downTo 1) {
                val ent = entropyPool[e % entropyPool.size].toInt() and 0xFF
                val idxBig = prng().add(BigInteger.valueOf(ent.toLong())).mod(BigInteger.valueOf((e + 1).toLong()))
                val n = idxBig.toInt()
                val tmp = order[e]
                order[e] = order[n]
                order[n] = tmp
            }
        }
    }
}

private fun String.sha256(): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(this.toByteArray(StandardCharsets.UTF_8))

private class Scrambler(private val seed: BigInteger, private val gridSize: Int) {
    private val totalPieces: Int = gridSize * gridSize
    private val randomizer: Randomizer = Randomizer(seed, gridSize)
    private val dependencyGraph: DependencyGraph
    private val scramblePath: List<Int>

    init {
        dependencyGraph = buildDependencyGraph()
        scramblePath = generateScramblePath()
    }

    private data class DependencyGraph(
        val graph: MutableMap<Int, MutableList<Int>>,
        val inDegree: MutableMap<Int, Int>,
    )

    private fun buildDependencyGraph(): DependencyGraph {
        val graph = mutableMapOf<Int, MutableList<Int>>()
        val inDegree = mutableMapOf<Int, Int>()

        for (n in 0 until totalPieces) {
            inDegree[n] = 0
            graph[n] = mutableListOf()
        }

        val rng = Randomizer(seed, gridSize)

        for (r in 0 until totalPieces) {
            val i = (rng.prng() % BigInteger.valueOf(3) + BigInteger.valueOf(2)).toInt()
            repeat(i) {
                val j = (rng.prng() % BigInteger.valueOf(totalPieces.toLong())).toInt()
                if (j != r && !wouldCreateCycle(graph, j, r)) {
                    graph[j]!!.add(r)
                    inDegree[r] = inDegree[r]!! + 1
                }
            }
        }

        for (r in 0 until totalPieces) {
            if (inDegree[r] == 0) {
                var tries = 0
                while (tries < 10) {
                    val s = (rng.prng() % BigInteger.valueOf(totalPieces.toLong())).toInt()
                    if (s != r && !wouldCreateCycle(graph, s, r)) {
                        graph[s]!!.add(r)
                        inDegree[r] = inDegree[r]!! + 1
                        break
                    }
                    tries++
                }
            }
        }

        return DependencyGraph(graph, inDegree)
    }

    private fun wouldCreateCycle(graph: Map<Int, List<Int>>, target: Int, start: Int): Boolean {
        val visited = mutableSetOf<Int>()
        val stack = ArrayDeque<Int>()
        stack.add(start)

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
        for (n in 0 until totalPieces) {
            if (inDegreeCopy[n] == 0) {
                queue.add(n)
            }
        }

        val order = mutableListOf<Int>()
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            order.add(i)
            val neighbors = graphCopy[i]
            if (neighbors != null) {
                for (e in neighbors) {
                    inDegreeCopy[e] = inDegreeCopy[e]!! - 1
                    if (inDegreeCopy[e] == 0) {
                        queue.add(e)
                    }
                }
            }
        }
        return order
    }

    fun getScrambleMapping(): List<Pair<Int, Int>> {
        var e = randomizer.order.toMutableList()

        if (scramblePath.size == totalPieces) {
            val t = Array(totalPieces) { 0 }
            for (i in scramblePath.indices) {
                t[i] = scramblePath[i]
            }
            val n = Array(totalPieces) { 0 }
            for (r in 0 until totalPieces) {
                n[r] = e[t[r]]
            }
            e = n.toMutableList()
        }

        val result = mutableListOf<Pair<Int, Int>>()
        for (n in 0 until totalPieces) {
            result.add(n to e[n])
        }
        return result
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
        order = MutableList(size) { it }
        permute()
    }

    private fun hashSeed(e: BigInteger): BigInteger {
        val md = e.toString().sha256()
        return readBigUInt64BE(md, 0).xor(readBigUInt64BE(md, 8))
    }

    private fun readBigUInt64BE(bytes: ByteArray, offset: Int): BigInteger {
        var n = BigInteger.ZERO
        for (i in 0 until 8) {
            n = n.shiftLeft(8).or(BigInteger.valueOf((bytes[offset + i].toInt() and 0xFF).toLong()))
        }
        return n
    }

    private fun expandEntropy(e: BigInteger): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(e.toString().toByteArray(StandardCharsets.UTF_8))

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
        n = n.xor(BigInteger.valueOf(sboxVal.toLong()))

        n = n.xor(n.shiftRight(13))
        return n
    }

    private fun feistelMix(e: Int, t: Int, rounds: Int): Pair<BigInteger, BigInteger> {
        var r = BigInteger.valueOf(e.toLong())
        var i = BigInteger.valueOf(t.toLong())
        for (round in 0 until rounds) {
            val ent = entropyPool[round % entropyPool.size].toInt() and 0xFF
            r = r.xor(roundFunc(i, ent))
            val secondArg = ent xor (round * 31 and 255)
            i = i.xor(roundFunc(r, secondArg))
        }
        return Pair(r, i)
    }

    private fun permute() {
        val half = size / 2
        val sizeBig = BigInteger.valueOf(size.toLong())

        for (t in 0 until half) {
            val n = t + half
            val (rBig, iBig) = feistelMix(t, n, 4)
            val s = rBig.mod(sizeBig).toInt()
            val a = iBig.mod(sizeBig).toInt()
            val tmp = order[s]
            order[s] = order[a]
            order[a] = tmp
        }

        for (e in size - 1 downTo 1) {
            val ent = entropyPool[e % entropyPool.size].toInt() and 0xFF
            val idxBig = prng().add(BigInteger.valueOf(ent.toLong())).mod(BigInteger.valueOf((e + 1).toLong()))
            val n = idxBig.toInt()
            val tmp = order[e]
            order[e] = order[n]
            order[n] = tmp
        }
    }
}
