package com.nguonc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "films/phim-moi-cap-nhat" to "Mới cập nhật",
        "films/danh-sach/phim-bo" to "Phim bộ",
        "films/danh-sach/phim-le" to "Phim lẻ",
        "films/danh-sach/hoat-hinh" to "Hoạt hình"
    )

    private val mapper = jacksonObjectMapper()

    private suspend fun api(path: String): JsonNode {
        val url = if (path.startsWith("http")) path else "$mainUrl/api/$path"
        return mapper.readTree(app.get(url).text)
    }

    private fun JsonNode.text(key: String): String = path(key).asText("").trim()

    private fun poster(raw: String): String? {
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> "$mainUrl/${raw.trimStart('/')}"
        }
    }

    private fun toSearchResponse(node: JsonNode): SearchResponse? {
        val slug = node.text("slug").ifBlank { return null }
        val title = node.text("name").ifBlank { slug }
        return newMovieSearchResponse(title, "$mainUrl/api/film/$slug", TvType.Movie) {
            posterUrl = poster(node.text("poster_url").ifBlank { node.text("thumb_url") })
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = api("${request.data}?page=$page")
        val items = response.path("items").mapNotNull(::toSearchResponse)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = api("films/search?keyword=$encoded&page=1")
        return response.path("items").mapNotNull(::toSearchResponse)
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringBefore("?").trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return null

        val response = api("film/$slug")
        val movie = response.path("movie")
        if (movie.isMissingNode || movie.isNull) return null

        val detailUrl = "$mainUrl/phim/$slug"
        val rootEpisodes = response.path("episodes")
        val movieEpisodes = movie.path("episodes")
        val servers = when {
            rootEpisodes.isArray && rootEpisodes.size() > 0 -> rootEpisodes
            movieEpisodes.isArray -> movieEpisodes
            else -> mapper.createArrayNode()
        }

        val episodes = servers.flatMap { server ->
            val items = server.path("items").let {
                if (it.isArray && it.size() > 0) it else server.path("server_data")
            }
            items.mapNotNull { ep ->
                val m3u8 = ep.text("link_m3u8").ifBlank { ep.text("m3u8") }
                val embed = ep.text("embed").ifBlank { ep.text("link_embed") }
                if (m3u8.isBlank() && embed.isBlank()) {
                    null
                } else {
                    val payload = mapper.writeValueAsString(
                        mapOf(
                            "m3u8" to m3u8,
                            "embed" to embed,
                            "referer" to detailUrl
                        )
                    )
                    newEpisode(payload) {
                        name = ep.text("name").ifBlank { ep.text("slug").ifBlank { "Tập" } }
                    }
                }
            }
        }

        val title = movie.text("name").ifBlank { slug }
        val cover = poster(movie.text("poster_url").ifBlank { movie.text("thumb_url") })
        val plotText = movie.text("description").ifBlank { movie.text("content") }
        val categoryText = movie.path("category").toString().lowercase()
        val isSeries = episodes.size > 1 ||
            categoryText.contains("phim bộ") ||
            categoryText.contains("tv shows") ||
            movie.text("type").contains("series", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = cover
                plot = plotText
            }
        } else {
            val movieData = episodes.firstOrNull()?.data ?: return null
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                posterUrl = cover
                plot = plotText
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val payload = try {
            mapper.readTree(data)
        } catch (_: Throwable) {
            null
        }

        if (payload == null || !payload.isObject) {
            val raw = data.trim()
            if (!raw.startsWith("http")) return false
            if (raw.contains(".m3u8", ignoreCase = true)) {
                callback(
                    newExtractorLink(name, "$name HLS", raw, type = ExtractorLinkType.M3U8) {
                        referer = mainUrl
                        quality = Qualities.Unknown.value
                    }
                )
                return true
            }
            if (raw.contains(".mp4", ignoreCase = true)) {
                callback(
                    newExtractorLink(name, "$name Video", raw, type = ExtractorLinkType.VIDEO) {
                        referer = mainUrl
                        quality = Qualities.Unknown.value
                    }
                )
                return true
            }
            var found = false
            loadExtractor(raw, mainUrl, subtitleCallback) { link ->
                found = true
                callback(link)
            }
            return found
        }

        val referer = payload.text("referer").ifBlank { mainUrl }
        val embed = payload.text("embed")
        val hls = payload.text("m3u8")
        var found = false

        if (embed.isNotBlank()) {
            loadExtractor(embed, referer, subtitleCallback) { link ->
                found = true
                callback(link)
            }
        }

        if (!found && hls.isNotBlank()) {
            callback(
                newExtractorLink(name, "$name HLS", hls, type = ExtractorLinkType.M3U8) {
                    this.referer = referer
                    quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        return found
    }
}
