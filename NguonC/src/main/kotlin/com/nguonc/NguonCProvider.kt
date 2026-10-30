package com.nguonc

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val loadLinksTimeoutMs: Long? = 60_000L

    private val mapper = jacksonObjectMapper()
    private val apiBases = listOf(
        "$mainUrl/api",
        "https://nguonc.com/api",
    )

    private val browserUserAgent =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

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
        val json = runCatching {
            val text = app.get(
                endpoint,
                headers = mapOf(
                    "User-Agent" to browserUserAgent,
                    "Accept" to "application/json,text/plain,*/*",
                    "Referer" to referer,
                    "Origin" to originOf(referer),
                ),
                referer = referer,
            ).text
            mapper.readTree(text)
        }.getOrNull() ?: return emptyList()

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
        val payload = runCatching { mapper.readTree(data) }.getOrNull() ?: return false
        val referer = payload.value("referer").ifBlank { "$mainUrl/" }
        val original = payload.path("sources")
        if (!original.isArray) return false

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

            resolveStreamC(source.embed, referer)?.let { stream ->
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
                        if (!stream.videoHash.isNullOrBlank()) {
                            extractorData = mapper.writeValueAsString(
                                mapOf(
                                    "kind" to "streamc",
                                    "videoHash" to stream.videoHash,
                                    "playlistUrl" to stream.url,
                                )
                            )
                        }
                    }
                )
                return true
            }

            var extracted = false
            runCatching {
                loadExtractor(source.embed, referer, subtitleCallback) { link ->
                    extracted = true
                    callback(link)
                }
            }
            return extracted
        }

        for (source in sources) {
            if (emitSource(source)) return true
        }

        val wantedKey = sources.firstOrNull()?.key
        val wantedLabel = sources.firstOrNull()?.label
        val refreshed = refreshEpisodeSources(referer, wantedKey, wantedLabel)

        for (source in refreshed) {
            if (emitSource(source)) return true
        }

        return false
    }

    private suspend fun resolveStreamC(embedUrl: String, movieUrl: String): ResolvedStream? {
        return runCatching {
            val embedHtml = app.get(
                embedUrl,
                headers = playerHeaders(movieUrl),
                referer = movieUrl,
            ).text

            findDirectMedia(embedHtml)?.let { direct ->
                return@runCatching ResolvedStream(
                    url = URI(embedUrl).resolve(direct).toString(),
                    referer = embedUrl,
                    type = if (direct.contains(".m3u8", true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    },
                    headers = playerHeaders(embedUrl),
                    videoHash = null,
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

                val responseText = app.post(
                    bootstrapApi,
                    json = mapOf(
                        "action" to "bootstrap",
                        "referrer" to movieUrl.take(4096),
                        "frame_origins" to listOf(movieOrigin),
                        "request_grant" to true,
                        "playlist_format" to "aesgcm-v2",
                        "pretty_url" to true,
                        "path_chunks" to true,
                        "bootstrap_format" to "aesgcm-v1",
                    ),
                    headers = mapOf(
                        "User-Agent" to browserUserAgent,
                        "Accept" to "application/json,text/plain,*/*",
                        "Origin" to embedOrigin,
                    ),
                    referer = embedUrl,
                ).text

                val envelope = mapper.readTree(responseText)
                val openedText = if (envelope.value("format") == "aesgcm-v1") {
                    decryptBootstrap(
                        envelope.value("iv"),
                        envelope.value("data"),
                        bootstrapApi,
                    )
                } else {
                    responseText
                }

                val opened = mapper.readTree(openedText)
                val playlistUrl = opened.path("preissued").value("playlist")
                if (playlistUrl.isBlank()) return@runCatching null

                val videoHash = opened.value("video").ifBlank {
                    Regex("""[?&]hash=([0-9a-fA-F]+)""")
                        .find(embedUrl)?.groupValues?.getOrNull(1).orEmpty()
                }
                if (videoHash.isBlank()) return@runCatching null

                return@runCatching ResolvedStream(
                    url = playlistUrl,
                    referer = embedUrl,
                    type = ExtractorLinkType.M3U8,
                    headers = playerHeaders(embedUrl),
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

            ResolvedStream(
                url = legacyUrl,
                referer = embedUrl,
                type = ExtractorLinkType.M3U8,
                headers = playerHeaders(embedUrl),
                videoHash = legacy.value("hD").ifBlank { null },
            )
        }.getOrNull()
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val raw = extractorLink.extractorData ?: return null
        val data = runCatching { mapper.readTree(raw) }.getOrNull() ?: return null
        if (data.value("kind") != "streamc") return null

        val videoHash = data.value("videoHash")
        val playlistUrl = data.value("playlistUrl")
        if (videoHash.isBlank() || playlistUrl.isBlank()) return null

        val playlistHost = runCatching { URI(playlistUrl).host }.getOrNull() ?: return null

        return Interceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", browserUserAgent)
                .apply {
                    if (original.header("Referer").isNullOrBlank() && extractorLink.referer.isNotBlank()) {
                        header("Referer", extractorLink.referer)
                    }
                }
                .build()

            val response = chain.proceed(request)
            val body = response.body ?: return@Interceptor response

            if (!request.url.host.equals(playlistHost, ignoreCase = true)) {
                return@Interceptor response
            }

            val bytes = body.bytes()
            val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
                ?: return@Interceptor response.newBuilder()
                    .body(bytes.toResponseBody(body.contentType()))
                    .build()

            if (!text.contains("#ENC-AESGCM")) {
                return@Interceptor response.newBuilder()
                    .body(bytes.toResponseBody(body.contentType()))
                    .build()
            }

            val decrypted = runCatching {
                absolutizePlaylist(
                    decryptPlaylist(text, videoHash),
                    request.url.toString(),
                )
            }.getOrNull() ?: return@Interceptor response.newBuilder()
                .body(bytes.toResponseBody(body.contentType()))
                .build()

            response.newBuilder()
                .removeHeader("Content-Length")
                .removeHeader("Content-Encoding")
                .header("Content-Type", "application/vnd.apple.mpegurl")
                .body(
                    decrypted.toByteArray(Charsets.UTF_8).toResponseBody(
                        "application/vnd.apple.mpegurl".toMediaTypeOrNull()
                    )
                )
                .build()
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
    val videoHash: String?,
)
