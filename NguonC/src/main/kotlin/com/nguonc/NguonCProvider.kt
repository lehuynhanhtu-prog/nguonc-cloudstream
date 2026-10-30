package com.nguonc

import android.util.Base64
import android.webkit.WebSettings
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override var lang = "vi"
    override val hasMainPage = true
    override val usesWebView = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val loadLinksTimeoutMs: Long? = 90_000L

    private val mapper = jacksonObjectMapper()
    private val apiBases = listOf(
        "$mainUrl/api",
        "https://nguonc.com/api",
    )

    private val browserUserAgent: String by lazy {
        runCatching {
            val context = com.lagradost.cloudstream3.CloudStreamApp.context
            WebSettings.getDefaultUserAgent(context)
                ?.replace("; wv", "")
                ?.replace(" wv)", ")")
                ?.replace(Regex(""" Build/[^);]+"""), "")
                ?.replace(Regex("""Version/\d+\.\d+ """), "")
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
        }.getOrNull()?.takeIf { it.contains("Chrome/") && it.contains("Android") }
            ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    private val chromeMajor: String by lazy {
        Regex("""Chrome/(\d+)""").find(browserUserAgent)?.groupValues?.getOrNull(1) ?: "131"
    }

    private val cronetEngine: Any? by lazy {
        runCatching {
            val context = com.lagradost.cloudstream3.CloudStreamApp.context
            val builderClass = Class.forName("org.chromium.net.CronetEngine\$Builder")
            val builder = builderClass
                .getConstructor(android.content.Context::class.java)
                .newInstance(context)
            for (method in listOf("enableHttp2", "enableQuic", "enableBrotli")) {
                runCatching {
                    builderClass.getMethod(method, Boolean::class.javaPrimitiveType)
                        .invoke(builder, true)
                }
            }
            runCatching {
                builderClass.getMethod("setUserAgent", String::class.java)
                    .invoke(builder, browserUserAgent)
            }
            builderClass.getMethod("build").invoke(builder)
        }.getOrNull()
    }

    private val cronetOpen by lazy {
        runCatching {
            Class.forName("org.chromium.net.CronetEngine")
                .getMethod("openConnection", URL::class.java)
        }.getOrNull()
    }

    private data class CachedManifest(
        val playlist: String,
        val referer: String,
        val createdAt: Long,
    )

    private val manifestCache = ConcurrentHashMap<String, CachedManifest>()
    private val manifestTtlMs = 30L * 60L * 1000L

    override val mainPage = mainPageOf(
        "films/phim-moi-cap-nhat" to "Mới cập nhật",
        "films/danh-sach/phim-bo" to "Phim bộ",
        "films/danh-sach/phim-le" to "Phim lẻ",
        "films/danh-sach/tv-shows" to "TV Shows",
        "films/the-loai/hoat-hinh" to "Hoạt hình",
    )

    private fun requestHeaders() = mapOf(
        "User-Agent" to browserUserAgent,
        "Accept" to "application/json,text/plain,*/*",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.7",
    )

    private fun playerHeaders(referer: String) = mapOf(
        "User-Agent" to browserUserAgent,
        "Accept" to "*/*",
        "Referer" to referer,
    )


    private data class BrowserResponse(
        val code: Int,
        val text: String,
        val finalUrl: String,
    )

    private fun chromeRequestHeaders(
        referer: String,
        origin: String? = null,
        json: Boolean = false,
        navigation: Boolean = false,
    ): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to browserUserAgent,
            "Accept" to if (navigation) {
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            } else {
                "application/json,text/plain,*/*"
            },
            "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
            "Sec-CH-UA" to "\"Chromium\";v=\"$chromeMajor\", \"Google Chrome\";v=\"$chromeMajor\", \"Not_A Brand\";v=\"99\"",
            "Sec-CH-UA-Mobile" to "?1",
            "Sec-CH-UA-Platform" to "\"Android\"",
            "Sec-Fetch-Site" to if (origin == null) "cross-site" else "same-origin",
            "Sec-Fetch-Mode" to if (navigation) "navigate" else "cors",
            "Sec-Fetch-Dest" to if (navigation) "iframe" else "empty",
            "Referer" to referer,
        )
        if (navigation) headers["Upgrade-Insecure-Requests"] = "1"
        if (json) headers["Content-Type"] = "application/json"
        if (!origin.isNullOrBlank()) headers["Origin"] = origin
        return headers
    }

    private suspend fun cronetRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String? = null,
    ): BrowserResponse? = withContext(Dispatchers.IO) {
        val engine = cronetEngine ?: return@withContext null
        val open = cronetOpen ?: return@withContext null

        runCatching {
            val connection = open.invoke(engine, URL(url)) as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                for ((key, value) in headers) {
                    if (!key.equals("Accept-Encoding", true) && !key.equals("Content-Length", true)) {
                        connection.setRequestProperty(key, value)
                    }
                }
                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }

                val code = connection.responseCode
                val stream = if (code >= 400) connection.errorStream else connection.inputStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                BrowserResponse(code, text, connection.url.toString())
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private suspend fun browserGet(
        url: String,
        referer: String,
        navigation: Boolean = false,
    ): BrowserResponse {
        val headers = chromeRequestHeaders(
            referer = referer,
            navigation = navigation,
        )

        // CloudStream's NiceHttp/OkHttp stack already negotiates HTTP/2 and
        // follows the same system network/proxy settings as the rest of the app.
        // Prefer it so StreamC does not bypass Android's active network route.
        val primary = withTimeoutOrNull(18_000L) {
            runCatching {
                val response = app.get(
                    url,
                    headers = headers,
                    referer = referer,
                )
                BrowserResponse(response.code, response.text, url)
            }.getOrNull()
        }
        if (primary != null) return primary

        // Cronet is only a fallback. It can ignore some Android proxy routes,
        // which previously caused loadLinks() to hang forever on the embed URL.
        return withTimeoutOrNull(18_000L) {
            cronetRequest(url, "GET", headers)
        } ?: BrowserResponse(599, "", url)
    }

    private suspend fun browserPostJson(
        url: String,
        referer: String,
        origin: String,
        json: Map<String, Any>,
    ): BrowserResponse {
        val headers = chromeRequestHeaders(
            referer = referer,
            origin = origin,
            json = true,
        )
        val body = mapper.writeValueAsString(json)

        val primary = withTimeoutOrNull(18_000L) {
            runCatching {
                val response = app.post(
                    url,
                    requestBody = body.toRequestBody("application/json".toMediaType()),
                    headers = headers + ("Referer" to referer),
                )
                BrowserResponse(response.code, response.text, url)
            }.getOrNull()
        }
        if (primary != null) return primary

        return withTimeoutOrNull(18_000L) {
            cronetRequest(url, "POST", headers, body)
        } ?: BrowserResponse(599, "", url)
    }

    private fun cacheManifest(playlist: String, referer: String): String {
        val now = System.currentTimeMillis()
        manifestCache.entries.removeIf { now - it.value.createdAt > manifestTtlMs }
        while (manifestCache.size >= 32) {
            manifestCache.entries.minByOrNull { it.value.createdAt }?.let {
                manifestCache.remove(it.key)
            } ?: break
        }
        val key = UUID.randomUUID().toString()
        manifestCache[key] = CachedManifest(playlist, referer, now)
        return key
    }

    private suspend fun api(path: String): JsonNode? {
        val cleanPath = path.trimStart('/')
        for (base in apiBases) {
            val result = runCatching {
                val text = app.get(
                    "$base/$cleanPath",
                    headers = requestHeaders(),
                    referer = "$mainUrl/",
                ).text
                mapper.readTree(text)
            }.getOrNull()

            if (result != null && !result.isNull && result.path("status").asText("success") != "error") {
                return result
            }
        }
        return null
    }

    private fun JsonNode.value(key: String): String = path(key).asText("").trim()

    private fun poster(raw: String): String? {
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> "$mainUrl/${raw.trimStart('/')}"
        }
    }

    private fun itemType(node: JsonNode): TvType {
        val total = node.path("total_episodes").asInt(0)
        val current = node.value("current_episode")
        return when {
            total > 1 -> TvType.TvSeries
            current.contains("tập", ignoreCase = true) -> TvType.TvSeries
            current.contains("episode", ignoreCase = true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun toSearchResponse(node: JsonNode): SearchResponse? {
        val slug = node.value("slug").ifBlank { return null }
        val title = node.value("name").ifBlank { slug }
        val type = itemType(node)

        return newMovieSearchResponse(title, "$mainUrl/phim/$slug", type) {
            posterUrl = poster(node.value("poster_url").ifBlank { node.value("thumb_url") })
            year = node.value("year").toIntOrNull()
            quality = getQualityFromString(node.value("quality"))
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = api("${request.data}?page=$page")
        val entries = response?.path("items")?.mapNotNull(::toSearchResponse).orEmpty()
        return newHomePageResponse(request.name, entries)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = api("films/search?keyword=$encoded&page=1")
        return response?.path("items")?.mapNotNull(::toSearchResponse).orEmpty()
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return null

        val response = api("film/${URLEncoder.encode(slug, "UTF-8")}") ?: return null
        val movie = response.path("movie")
        if (movie.isMissingNode || movie.isNull) return null

        val resolvedSlug = movie.value("slug").ifBlank { slug }
        val detailUrl = "$mainUrl/phim/$resolvedSlug"
        val sources = readEpisodeSources(movie, response, detailUrl)

        val grouped = sources
            .groupBy { it.key }
            .values
            .sortedBy { group -> episodeNumber(group.firstOrNull()?.label.orEmpty()) ?: Int.MAX_VALUE }

        val title = movie.value("name").ifBlank {
            movie.value("original_name").ifBlank { resolvedSlug }
        }
        val cover = poster(movie.value("poster_url").ifBlank { movie.value("thumb_url") })
        val backdrop = poster(movie.value("thumb_url").ifBlank { movie.value("poster_url") })
        val plotText = Jsoup.parse(movie.value("description").ifBlank { movie.value("content") }).text()
        val year = movieYear(movie)
        val tags = movieTags(movie)
        val totalEpisodes = movie.path("total_episodes").asInt(0)
        val isSeries = totalEpisodes > 1 || grouped.size > 1 || isSeriesCategory(movie)

        if (isSeries) {
            val episodes = grouped.map { group ->
                val first = group.first()
                val payload = linkPayload(detailUrl, group)
                newEpisode(payload) {
                    name = first.label.ifBlank { "Tập" }
                    episode = episodeNumber(first.label)
                }
            }

            return newTvSeriesLoadResponse(title, detailUrl, TvType.TvSeries, episodes) {
                posterUrl = cover
                backgroundPosterUrl = backdrop
                plot = plotText
                this.year = year
                this.tags = tags
            }
        }

        val firstGroup = grouped.firstOrNull().orEmpty()
        if (firstGroup.isEmpty()) return null

        return newMovieLoadResponse(
            title,
            detailUrl,
            TvType.Movie,
            linkPayload(detailUrl, firstGroup),
        ) {
            posterUrl = cover
            backgroundPosterUrl = backdrop
            plot = plotText
            this.year = year
            this.tags = tags
        }
    }

    private suspend fun readEpisodeSources(
        movie: JsonNode,
        response: JsonNode,
        detailUrl: String,
    ): List<EpisodeSource> {
        val fromApi = parseServers(
            when {
                movie.path("episodes").isArray -> movie.path("episodes")
                response.path("episodes").isArray -> response.path("episodes")
                else -> mapper.createArrayNode()
            }
        )
        if (fromApi.isNotEmpty()) return fromApi

        val episodeEndpoint = "$detailUrl/episodes"
        val fallback = runCatching {
            val text = app.get(
                episodeEndpoint,
                headers = playerHeaders(detailUrl),
                referer = detailUrl,
            ).text
            mapper.readTree(text)
        }.getOrNull() ?: return emptyList()

        return parseServers(fallback.path("servers"))
    }

    private fun parseServers(servers: JsonNode): List<EpisodeSource> {
        if (!servers.isArray) return emptyList()

        return servers.flatMap { server ->
            val serverName = server.value("server_name").ifBlank {
                server.value("name").ifBlank { "NguonC" }
            }
            val items = when {
                server.path("items").isArray -> server.path("items")
                server.path("server_data").isArray -> server.path("server_data")
                server.path("list").isArray -> server.path("list")
                else -> mapper.createArrayNode()
            }

            items.mapNotNull { episode ->
                val direct = episode.value("link_m3u8").ifBlank {
                    episode.value("m3u8")
                }
                val embed = episode.value("embed").ifBlank {
                    episode.value("link_embed")
                }
                if (direct.isBlank() && embed.isBlank()) return@mapNotNull null

                val label = episode.value("name").ifBlank {
                    episode.value("slug").ifBlank { "Tập" }
                }
                EpisodeSource(
                    key = episode.value("slug").ifBlank { label },
                    label = label,
                    server = serverName,
                    embed = embed,
                    direct = direct,
                )
            }
        }
    }

    private fun linkPayload(referer: String, sources: List<EpisodeSource>): String {
        val root = mapper.createObjectNode()
        root.put("referer", referer)
        val list = root.putArray("sources")
        for (source in sources) {
            val item = list.addObject()
            item.put("name", source.server)
            item.put("key", source.key)
            item.put("label", source.label)
            item.put("embed", source.embed)
            item.put("direct", source.direct)
        }
        return mapper.writeValueAsString(root)
    }

    private suspend fun refreshEpisodeSources(
        referer: String,
        wantedKey: String?,
        wantedLabel: String?,
    ): List<EpisodeSource> {
        val endpoint = "${referer.trimEnd('/')}/episodes"
        val response = runCatching {
            browserGet(
                url = endpoint,
                referer = referer,
            )
        }.getOrNull() ?: return emptyList()

        if (response.code !in 200..299 || response.text.isBlank()) {
            Log.w("NguonC", "refreshEpisodeSources HTTP=${response.code}")
            return emptyList()
        }

        val json = runCatching { mapper.readTree(response.text) }.getOrNull()
            ?: return emptyList()
        val all = parseServers(json.path("servers"))
        if (all.isEmpty()) return emptyList()

        val exactKey = wantedKey?.takeIf(String::isNotBlank)
        val exactLabel = wantedLabel?.takeIf(String::isNotBlank)

        return when {
            exactKey != null -> all.filter { it.key == exactKey }.ifEmpty {
                all.filter { it.label.equals(exactLabel, ignoreCase = true) }
            }
            exactLabel != null -> all.filter { it.label.equals(exactLabel, ignoreCase = true) }
            else -> all
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = runCatching { mapper.readTree(data) }.getOrNull()
            ?: run {
                Log.e("NguonC", "loadLinks: invalid payload")
                return false
            }
        val referer = payload.value("referer").ifBlank { "$mainUrl/" }
        val original = payload.path("sources")
        if (!original.isArray) {
            Log.e("NguonC", "loadLinks: no source array")
            return false
        }

        val sources = original.map { source ->
            EpisodeSource(
                key = source.value("key"),
                label = source.value("label"),
                server = source.value("name").ifBlank { name },
                embed = source.value("embed"),
                direct = source.value("direct"),
            )
        }

        suspend fun emitSource(source: EpisodeSource): Boolean {
            Log.i("NguonC", "emitSource server=${source.server} direct=${source.direct.isNotBlank()} embed=${source.embed.take(120)}")
            if (source.direct.isNotBlank()) {
                callback(
                    newExtractorLink(
                        name,
                        "$name - ${source.server}",
                        source.direct,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = referer
                        quality = Qualities.Unknown.value
                        headers = playerHeaders(referer)
                    }
                )
                return true
            }

            if (source.embed.isBlank()) return false

            val stream = resolveStreamC(source.embed, referer)
                ?: resolveStreamCWithWebView(source.embed, referer)

            stream?.let { stream ->
                Log.i("NguonC", "resolved stream url=${stream.url} manifestKey=${stream.manifestKey}")
                callback(
                    newExtractorLink(
                        name,
                        "$name - ${source.server}",
                        stream.url,
                        type = stream.type,
                    ) {
                        this.referer = stream.referer
                        quality = Qualities.Unknown.value
                        headers = stream.headers
                        when {
                            !stream.manifestKey.isNullOrBlank() -> {
                                extractorData = mapper.writeValueAsString(
                                    mapOf(
                                        "kind" to "local_manifest",
                                        "key" to stream.manifestKey,
                                    )
                                )
                            }
                            !stream.videoHash.isNullOrBlank() -> {
                                extractorData = mapper.writeValueAsString(
                                    mapOf(
                                        "kind" to "streamc_remote",
                                        "videoHash" to stream.videoHash,
                                        "playlistUrl" to stream.url,
                                    )
                                )
                            }
                        }
                    }
                )
                Log.i("NguonC", "callback emitted custom stream")
                return true
            }

            Log.w("NguonC", "custom StreamC resolver returned null; trying generic extractor")
            var extracted = false
            runCatching {
                loadExtractor(source.embed, referer, subtitleCallback) { link ->
                    extracted = true
                    callback(link)
                }
            }.onFailure {
                Log.w("NguonC", "Generic extractor failed: ${it.javaClass.simpleName}: ${it.message}")
            }
            Log.i("NguonC", "generic extractor emitted=$extracted")
            return extracted
        }

        // First attempt the source received with the detail response.
        for (source in sources) {
            if (emitSource(source)) return true
        }

        // Current NguonC website refreshes episode/embed URLs from /episodes.
        // If every cached/detail source failed, request a fresh source at play time.
        val refreshed = refreshEpisodeSources(
            referer = referer,
            wantedKey = sources.firstOrNull()?.key,
            wantedLabel = sources.firstOrNull()?.label,
        )
        for (source in refreshed) {
            if (emitSource(source)) return true
        }

        Log.e(
            "NguonC",
            "loadLinks: no playable links original=${sources.size} refreshed=${refreshed.size}",
        )
        return false
    }

    private suspend fun resolveStreamCWithWebView(
        embedUrl: String,
        movieUrl: String,
    ): ResolvedStream? {
        return runCatching {
            Log.i("NguonC", "trying WebView resolver for $embedUrl")
            val resolver = WebViewResolver(
                interceptUrl = Regex(
                    """(?i)(?:\\.m3u8(?:[?#]|$)|/[^?#]*playlist[^?#]*(?:[?#]|$))"""
                ),
                userAgent = null,
                useOkhttp = false,
                timeout = 30_000L,
            )

            val response = app.get(
                url = embedUrl,
                referer = movieUrl,
                interceptor = resolver,
                headers = mapOf("Referer" to movieUrl),
            )

            val interceptedUrl = response.url
            var playlist = response.text
            Log.i(
                "NguonC",
                "WebView result url=$interceptedUrl bytes=${playlist.length}",
            )

            if (playlist.contains("#ENC-AESGCM")) {
                val videoHash = Regex("""[?&]hash=([0-9a-fA-F]+)""")
                    .find(embedUrl)?.groupValues?.getOrNull(1).orEmpty()
                if (videoHash.isBlank()) return@runCatching null
                playlist = decryptPlaylist(playlist, videoHash)
            }

            if (!playlist.contains("#EXTM3U")) {
                return@runCatching null
            }

            val absolute = absolutizePlaylist(playlist, interceptedUrl)
            val key = cacheManifest(absolute, embedUrl)

            ResolvedStream(
                url = "https://nguonc.local/hls/$key/master.m3u8",
                referer = embedUrl,
                type = ExtractorLinkType.M3U8,
                headers = playerHeaders(embedUrl),
                manifestKey = key,
            )
        }.onFailure {
            Log.e(
                "NguonC",
                "WebView resolver: ${it.javaClass.simpleName}: ${it.message}",
                it,
            )
        }.getOrNull()
    }

    private suspend fun resolveStreamC(embedUrl: String, movieUrl: String): ResolvedStream? {
        return runCatching {
            val embedResponse = browserGet(
                url = embedUrl,
                referer = movieUrl,
                navigation = true,
            )
            Log.i("NguonC", "embed HTTP=${embedResponse.code} bytes=${embedResponse.text.length} final=${embedResponse.finalUrl}")
            if (embedResponse.code !in 200..299 || embedResponse.text.isBlank()) {
                Log.w("NguonC", "embed fetch failed")
                return@runCatching null
            }
            val embedHtml = embedResponse.text

            findDirectMedia(embedHtml)?.let { direct ->
                val directUrl = URI(embedUrl).resolve(direct).toString()
                if (directUrl.contains(".m3u8", true)) {
                    val directPlaylist = browserGet(directUrl, embedUrl)
                    if (directPlaylist.code in 200..299 && directPlaylist.text.contains("#EXTM3U")) {
                        val absolute = absolutizePlaylist(directPlaylist.text, directPlaylist.finalUrl)
                        val key = cacheManifest(absolute, embedUrl)
                        return@runCatching ResolvedStream(
                            url = "https://nguonc.local/hls/$key/master.m3u8",
                            referer = embedUrl,
                            type = ExtractorLinkType.M3U8,
                            headers = playerHeaders(embedUrl),
                            manifestKey = key,
                        )
                    }
                }
                return@runCatching ResolvedStream(
                    url = directUrl,
                    referer = embedUrl,
                    type = if (directUrl.contains(".m3u8", true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    },
                    headers = playerHeaders(embedUrl),
                    manifestKey = null,
                )
            }

            val bootstrapBlock = Regex(
                """<script[^>]*id=["']stream-bootstrap["'][^>]*>(.*?)</script>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(embedHtml)?.groupValues?.getOrNull(1)

            if (!bootstrapBlock.isNullOrBlank()) {
                val meta = mapper.readTree(bootstrapBlock)
                val rawApi = meta.value("api")
                if (rawApi.isBlank()) return@runCatching null

                val bootstrapApi = URI(embedUrl).resolve(rawApi).toString()
                val embedOrigin = originOf(embedUrl)
                val movieOrigin = originOf(movieUrl)
                val bootstrapBody = mapOf<String, Any>(
                    "action" to "bootstrap",
                    "referrer" to movieUrl.take(4096),
                    "frame_origins" to listOf(movieOrigin),
                    "request_grant" to true,
                    "playlist_format" to "aesgcm-v2",
                    "pretty_url" to true,
                    "path_chunks" to true,
                    "bootstrap_format" to "aesgcm-v1",
                )

                val bootstrapResponse = browserPostJson(
                    url = bootstrapApi,
                    referer = embedUrl,
                    origin = embedOrigin,
                    json = bootstrapBody,
                )
                Log.i("NguonC", "bootstrap HTTP=${bootstrapResponse.code} bytes=${bootstrapResponse.text.length}")
                if (bootstrapResponse.code !in 200..299 || bootstrapResponse.text.isBlank()) {
                    Log.w("NguonC", "bootstrap failed body=${bootstrapResponse.text.take(300)}")
                    return@runCatching null
                }

                val envelope = mapper.readTree(bootstrapResponse.text)
                val openedText = if (envelope.value("format") == "aesgcm-v1") {
                    decryptBootstrap(
                        envelope.value("iv"),
                        envelope.value("data"),
                        bootstrapApi,
                    )
                } else {
                    bootstrapResponse.text
                }

                val opened = mapper.readTree(openedText)
                val playlistUrl = opened.path("preissued").value("playlist")
                Log.i("NguonC", "bootstrap opened playlist=${playlistUrl.take(180)} turnstile=${opened.path("turnstileEnabled").asBoolean(false)}")
                if (playlistUrl.isBlank()) {
                    Log.w("NguonC", "bootstrap has no playlist")
                    return@runCatching null
                }

                val videoHash = opened.value("video").ifBlank {
                    Regex("""[?&]hash=([0-9a-fA-F]+)""")
                        .find(embedUrl)?.groupValues?.getOrNull(1).orEmpty()
                }
                if (videoHash.isBlank()) return@runCatching null

                val embedHost = runCatching { URI(embedUrl).host }.getOrNull()
                val playlistHost = runCatching { URI(playlistUrl).host }.getOrNull()
                if (embedHost.isNullOrBlank() || playlistHost != embedHost) {
                    Log.w("NguonC", "bootstrap playlist host mismatch")
                    return@runCatching null
                }

                // Do not fetch the playlist inside loadLinks(). StreamC may stall
                // or challenge this second request, which previously made CloudStream
                // report "No links found" even though bootstrap already returned a
                // valid playlist URL. Emit it immediately and decrypt in the player's
                // OkHttp interceptor when Media3 actually requests the manifest.
                return@runCatching ResolvedStream(
                    url = playlistUrl,
                    referer = embedUrl,
                    type = ExtractorLinkType.M3U8,
                    headers = playerHeaders(embedUrl),
                    manifestKey = null,
                    videoHash = videoHash,
                )
            }

            val legacyValue = Regex("""data-obf=["']([^"']+)["']""")
                .find(embedHtml)?.groupValues?.getOrNull(1)
                ?: return@runCatching null
            val legacyJson = String(Base64.decode(legacyValue, Base64.DEFAULT), Charsets.UTF_8)
            val legacy = mapper.readTree(legacyJson)
            val path = legacy.value("sUb")
            if (path.isBlank()) return@runCatching null

            val origin = originOf(embedUrl)
            val legacyUrl = when {
                path.startsWith("http://") || path.startsWith("https://") -> path
                path.endsWith(".m3u8", true) -> "$origin/${path.trimStart('/')}"
                else -> "$origin/${path.trimStart('/')}.m3u8"
            }

            val legacyResponse = browserGet(
                url = legacyUrl,
                referer = embedUrl,
            )
            if (legacyResponse.code !in 200..299 || legacyResponse.text.isBlank()) {
                return@runCatching null
            }

            var legacyPlaylist = legacyResponse.text
            if (legacyPlaylist.contains("#ENC-AESGCM")) {
                val videoHash = legacy.value("hD")
                if (videoHash.isBlank()) return@runCatching null
                legacyPlaylist = decryptPlaylist(legacyPlaylist, videoHash)
            }
            if (!legacyPlaylist.contains("#EXTM3U")) {
                return@runCatching null
            }

            val absolute = absolutizePlaylist(legacyPlaylist, legacyResponse.finalUrl)
            val key = cacheManifest(absolute, embedUrl)
            ResolvedStream(
                url = "https://nguonc.local/hls/$key/master.m3u8",
                referer = embedUrl,
                type = ExtractorLinkType.M3U8,
                headers = playerHeaders(embedUrl),
                manifestKey = key,
            )
        }.onFailure {
            Log.e("NguonC", "StreamC resolver: ${it.javaClass.simpleName}: ${it.message}", it)
        }.getOrNull()
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val raw = extractorLink.extractorData ?: return null
        val data = runCatching { mapper.readTree(raw) }.getOrNull() ?: return null

        return when (data.value("kind")) {
            "local_manifest" -> {
                val key = data.value("key")
                val cached = manifestCache[key] ?: return null
                if (System.currentTimeMillis() - cached.createdAt > manifestTtlMs) {
                    manifestCache.remove(key)
                    return null
                }

                Interceptor { chain ->
                    val original = chain.request()

                    if (original.url.host.equals("nguonc.local", ignoreCase = true)) {
                        return@Interceptor okhttp3.Response.Builder()
                            .request(original)
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", "application/vnd.apple.mpegurl")
                            .body(
                                cached.playlist.toByteArray(Charsets.UTF_8).toResponseBody(
                                    "application/vnd.apple.mpegurl".toMediaTypeOrNull()
                                )
                            )
                            .build()
                    }

                    val request = original.newBuilder()
                        .header("User-Agent", browserUserAgent)
                        .apply {
                            if (original.header("Referer").isNullOrBlank()) {
                                header("Referer", cached.referer)
                            }
                        }
                        .build()

                    chain.proceed(request)
                }
            }

            "streamc_remote" -> {
                val videoHash = data.value("videoHash")
                val playlistUrl = data.value("playlistUrl")
                if (videoHash.isBlank() || playlistUrl.isBlank()) return null

                Interceptor { chain ->
                    val original = chain.request()
                    val request = original.newBuilder()
                        .header("User-Agent", browserUserAgent)
                        .header("Referer", extractorLink.referer)
                        .build()

                    val response = chain.proceed(request)
                    val isManifestRequest =
                        request.url.toString().substringBefore('#') ==
                            playlistUrl.substringBefore('#')

                    if (!isManifestRequest) {
                        return@Interceptor response
                    }

                    val body = response.body ?: return@Interceptor response
                    val bytes = body.bytes()
                    var text = runCatching {
                        String(bytes, Charsets.UTF_8)
                    }.getOrNull() ?: return@Interceptor response.newBuilder()
                        .body(bytes.toResponseBody(body.contentType()))
                        .build()

                    if (text.contains("#ENC-AESGCM")) {
                        text = runCatching {
                            decryptPlaylist(text, videoHash)
                        }.getOrElse {
                            Log.e(
                                "NguonC",
                                "player playlist decrypt failed: ${it.message}",
                                it,
                            )
                            return@Interceptor response.newBuilder()
                                .body(bytes.toResponseBody(body.contentType()))
                                .build()
                        }
                    }

                    if (!text.contains("#EXTM3U")) {
                        return@Interceptor response.newBuilder()
                            .body(bytes.toResponseBody(body.contentType()))
                            .build()
                    }

                    val absolute = absolutizePlaylist(
                        text,
                        response.request.url.toString(),
                    )
                    Log.i("NguonC", "player interceptor delivered decrypted HLS")

                    response.newBuilder()
                        .removeHeader("Content-Length")
                        .removeHeader("Content-Encoding")
                        .header("Content-Type", "application/vnd.apple.mpegurl")
                        .body(
                            absolute.toByteArray(Charsets.UTF_8).toResponseBody(
                                "application/vnd.apple.mpegurl".toMediaTypeOrNull()
                            )
                        )
                        .build()
                }
            }

            else -> null
        }
    }

    private fun decryptBootstrap(ivHex: String, dataBase64: String, apiUrl: String): String {
        require(ivHex.isNotBlank() && dataBase64.isNotBlank())
        val aad = "stream-bootstrap-v1\n$apiUrl".toByteArray(Charsets.UTF_8)
        val key = MessageDigest.getInstance("SHA-256").digest(aad)
        val clear = aesGcmDecrypt(
            key = key,
            iv = hexBytes(ivHex),
            encrypted = Base64.decode(dataBase64, Base64.DEFAULT),
            aad = aad,
        )
        return String(clear, Charsets.UTF_8)
    }

    private fun decryptPlaylist(encryptedPlaylist: String, videoHash: String): String {
        val iv = Regex("""#ENC-AESGCM[^\n]*iv=([0-9a-fA-F]+)""")
            .find(encryptedPlaylist)?.groupValues?.getOrNull(1)
            ?: error("Missing playlist IV")

        val payload = encryptedPlaylist
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?: error("Missing encrypted playlist payload")

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("stream-derive-v1".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val key = mac.doFinal(videoHash.toByteArray(Charsets.UTF_8))

        return String(
            aesGcmDecrypt(
                key = key,
                iv = hexBytes(iv),
                encrypted = Base64.decode(payload, Base64.DEFAULT),
            ),
            Charsets.UTF_8,
        )
    }

    private fun aesGcmDecrypt(
        key: ByteArray,
        iv: ByteArray,
        encrypted: ByteArray,
        aad: ByteArray? = null,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv),
        )
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(encrypted)
    }

    private fun absolutizePlaylist(playlist: String, baseUrl: String): String {
        val base = URI(baseUrl)
        val uriPattern = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)

        return playlist.lineSequence().joinToString("\n") { raw ->
            val line = raw.trim()
            when {
                line.isBlank() -> line
                line.startsWith("#") -> uriPattern.replace(line) { match ->
                    "URI=\"${base.resolve(match.groupValues[1])}\""
                }
                else -> base.resolve(line).toString()
            }
        }
    }

    private fun findDirectMedia(text: String): String? {
        return Regex(
            """https?:\\?/\\?/[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.value?.replace("\\/", "/")
    }

    private fun originOf(url: String): String {
        val uri = URI(url)
        return "${uri.scheme}://${uri.authority}"
    }

    private fun hexBytes(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun episodeNumber(label: String): Int? =
        Regex("""\d+""").find(label)?.value?.toIntOrNull()

    private fun movieYear(movie: JsonNode): Int? {
        movie.value("year").toIntOrNull()?.let { return it }
        val categories = movie.path("category")
        if (!categories.isObject) return null

        val iterator = categories.fields()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val group = entry.value.path("group").value("name")
            if (group.equals("Năm", ignoreCase = true)) {
                return entry.value.path("list").firstOrNull()?.value("name")?.toIntOrNull()
            }
        }
        return null
    }

    private fun movieTags(movie: JsonNode): List<String> {
        val categories = movie.path("category")
        if (!categories.isObject) return emptyList()

        val output = mutableListOf<String>()
        categories.fields().forEachRemaining { entry ->
            val groupName = entry.value.path("group").value("name")
            if (groupName.equals("Năm", true)) return@forEachRemaining
            entry.value.path("list").forEach { item ->
                item.value("name").takeIf(String::isNotBlank)?.let(output::add)
            }
        }
        return output.distinct()
    }

    private fun isSeriesCategory(movie: JsonNode): Boolean {
        val categories = movie.path("category")
        if (!categories.isObject) return false

        val text = buildString {
            categories.fields().forEachRemaining { entry ->
                entry.value.path("list").forEach { item ->
                    append(item.value("name")).append(' ')
                }
            }
        }.lowercase()

        return text.contains("phim bộ") || text.contains("tv show")
    }
}

private data class EpisodeSource(
    val key: String,
    val label: String,
    val server: String,
    val embed: String,
    val direct: String,
)

private data class ResolvedStream(
    val url: String,
    val referer: String,
    val type: ExtractorLinkType,
    val headers: Map<String, String>,
    val manifestKey: String?,
    val videoHash: String? = null,
)
