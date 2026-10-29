package com.nguonc

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URLEncoder

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Mới cập nhật",
        "phim-bo" to "Phim bộ",
        "phim-le" to "Phim lẻ",
        "hoat-hinh" to "Hoạt hình"
    )

    private val mapper = jacksonObjectMapper()
    private suspend fun fetch(path: String): JsonNode =
        mapper.readTree(app.get("$mainUrl/api/$path").text)

    private fun JsonNode.value(key: String): String = path(key).asText("")
    private fun imageUrl(raw: String): String? = raw.takeIf { it.isNotBlank() }?.let {
        when {
            it.startsWith("//") -> "https:$it"
            it.startsWith("https://") || it.startsWith("http://") -> it
            else -> "$mainUrl/${it.trimStart('/')}"
        }
    }

    private fun movieItem(node: JsonNode): SearchResponse? {
        val slug = node.value("slug").ifBlank { return null }
        return newMovieSearchResponse(
            node.value("name").ifBlank { slug },
            "$mainUrl/api/film/$slug",
            TvType.Movie
        ) {
            posterUrl = imageUrl(node.value("poster_url").ifBlank { node.value("thumb_url") })
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = fetch("films/${request.data}?page=$page")
        return newHomePageResponse(request.name, response.path("items").mapNotNull(::movieItem))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = fetch("films/search?keyword=${URLEncoder.encode(query, "UTF-8")}")
        return response.path("items").mapNotNull(::movieItem)
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast('/').substringBefore('?')
        val response = fetch("film/$slug")
        val movie = response.path("movie")
        if (movie.isMissingNode || movie.isNull) return null
        val episodes = response.path("episodes").flatMap { server ->
            server.path("items").mapNotNull { ep ->
                val stream = ep.value("link_m3u8").ifBlank { ep.value("embed") }
                stream.takeIf { it.isNotBlank() }?.let { Episode(it, name = ep.value("name")) }
            }
        }
        val title = movie.value("name").ifBlank { slug }
        val cover = imageUrl(movie.value("poster_url"))
        val plotText = movie.value("description")
        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = cover
                plot = plotText
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
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
        if (data.contains(".m3u8", ignoreCase = true)) {
            callback(newExtractorLink(name, "NguonC HLS", data, mainUrl, ExtractorLinkType.M3U8))
            return true
        }
        if (data.contains(".mp4", ignoreCase = true)) {
            callback(newExtractorLink(name, "NguonC MP4", data, mainUrl, ExtractorLinkType.VIDEO))
            return true
        }
        return loadExtractor(data, mainUrl, subtitleCallback, callback)
    }
}
