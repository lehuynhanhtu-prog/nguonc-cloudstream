package com.mystream

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MyStreamProvider : MainAPI() {
    override var mainUrl = "https://raw.githubusercontent.com/lehuynhanhtu-prog/nguonc-cloudstream/builds"
    override var name = "MyStream"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val mapper = jacksonObjectMapper()
    private val catalogUrl = "$mainUrl/mystream.json"

    override val mainPage = mainPageOf(
        "all" to "Thư viện MyStream",
    )

    private val fallbackCatalog = """
        {
          "version": 1,
          "items": [
            {
              "id": "big-buck-bunny",
              "title": "Big Buck Bunny - Kiểm thử MyStream",
              "type": "movie",
              "year": 2008,
              "poster": "https://storage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg",
              "plot": "Video mẫu công khai dùng để kiểm tra phát video trực tiếp trong MyStream.",
              "tags": ["Mẫu", "Kiểm thử"],
              "sources": [
                {
                  "name": "MP4 mẫu",
                  "url": "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                  "type": "video",
                  "quality": "720p"
                }
              ],
              "subtitles": []
            }
          ]
        }
    """.trimIndent()

    private fun JsonNode.textValue(key: String): String =
        path(key).asText("").trim()

    private suspend fun catalog(): JsonNode {
        val remote = runCatching {
            app.get(
                catalogUrl,
                headers = mapOf(
                    "Accept" to "application/json,text/plain,*/*",
                    "Cache-Control" to "no-cache",
                )
            ).text
        }.getOrNull()

        return runCatching {
            mapper.readTree(remote?.takeIf { it.isNotBlank() } ?: fallbackCatalog)
        }.getOrElse {
            mapper.readTree(fallbackCatalog)
        }
    }

    private suspend fun items(): List<JsonNode> =
        catalog().path("items").takeIf { it.isArray }?.toList().orEmpty()

    private fun itemType(item: JsonNode): TvType {
        val explicit = item.textValue("type").lowercase()
        val hasEpisodes = item.path("episodes").isArray && item.path("episodes").size() > 0
        return when (explicit) {
            "series", "tv", "tvseries" -> TvType.TvSeries
            "anime" -> TvType.Anime
            else -> if (hasEpisodes) TvType.TvSeries else TvType.Movie
        }
    }

    private fun itemUrl(item: JsonNode): String {
        val id = item.textValue("id").ifBlank {
            item.textValue("title").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        }
        return "$catalogUrl#item=$id"
    }

    private fun toSearchResponse(item: JsonNode): SearchResponse? {
        val title = item.textValue("title").ifBlank { return null }
        val type = itemType(item)
        return newMovieSearchResponse(title, itemUrl(item), type) {
            posterUrl = item.textValue("poster").ifBlank { null }
            year = item.path("year").asInt(0).takeIf { it > 0 }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        return newHomePageResponse(request.name, items().mapNotNull(::toSearchResponse))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        return items().filter { item ->
            val haystack = buildString {
                append(item.textValue("title"))
                append(' ')
                append(item.textValue("originalTitle"))
                append(' ')
                item.path("tags").takeIf { it.isArray }?.forEach {
                    append(it.asText())
                    append(' ')
                }
            }.lowercase()
            q.split(Regex("\\s+")).all { it in haystack }
        }.mapNotNull(::toSearchResponse)
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfter("#item=", "").substringBefore('&').trim()
        if (id.isBlank()) return null

        val item = items().firstOrNull {
            itemUrl(it).substringAfter("#item=") == id
        } ?: return null

        val title = item.textValue("title").ifBlank { id }
        val type = itemType(item)
        val detailUrl = itemUrl(item)
        val poster = item.textValue("poster").ifBlank { null }
        val backdrop = item.textValue("backdrop").ifBlank { null }
        val plotText = item.textValue("plot").ifBlank { null }
        val yearValue = item.path("year").asInt(0).takeIf { it > 0 }
        val tagsValue = item.path("tags").takeIf { it.isArray }?.map { it.asText() }?.filter { it.isNotBlank() }

        val episodeNodes = item.path("episodes").takeIf { it.isArray }?.toList().orEmpty()
        if (episodeNodes.isNotEmpty()) {
            val episodes = episodeNodes.mapIndexed { index, episode ->
                newEpisode(episode.toString()) {
                    name = episode.textValue("name").ifBlank { "Tập ${index + 1}" }
                    this.episode = episode.path("episode").asInt(index + 1)
                    season = episode.path("season").asInt(0).takeIf { it > 0 }
                    posterUrl = episode.textValue("poster").ifBlank { poster }
                }
            }

            return newTvSeriesLoadResponse(title, detailUrl, type, episodes) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = plotText
                year = yearValue
                tags = tagsValue
            }
        }

        return newMovieLoadResponse(title, detailUrl, type, item.toString()) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            plot = plotText
            year = yearValue
            tags = tagsValue
        }
    }

    private fun headers(node: JsonNode): Map<String, String> {
        val objectNode = node.path("headers")
        if (!objectNode.isObject) return emptyMap()
        return objectNode.fields().asSequence()
            .mapNotNull { (key, value) ->
                value.asText("").takeIf { it.isNotBlank() }?.let { key to it }
            }
            .toMap()
    }

    private fun quality(value: String): Int = when {
        value.contains("2160", true) || value.contains("4k", true) -> Qualities.P2160.value
        value.contains("1440", true) -> Qualities.P1440.value
        value.contains("1080", true) -> Qualities.P1080.value
        value.contains("720", true) -> Qualities.P720.value
        value.contains("480", true) -> Qualities.P480.value
        value.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val node = runCatching { mapper.readTree(data) }.getOrNull() ?: return false

        node.path("subtitles").takeIf { it.isArray }?.forEach { subtitle ->
            val url = subtitle.textValue("url")
            if (url.isBlank()) return@forEach
            subtitleCallback(
                newSubtitleFile(
                    subtitle.textValue("lang").ifBlank { subtitle.textValue("name").ifBlank { "Sub" } },
                    url,
                )
            )
        }

        var emitted = false
        node.path("sources").takeIf { it.isArray }?.forEach { source ->
            val streamUrl = source.textValue("url")
            if (streamUrl.isBlank()) return@forEach

            val typeText = source.textValue("type")
            val linkType = if (
                typeText.equals("hls", true) ||
                typeText.equals("m3u8", true) ||
                streamUrl.contains(".m3u8", true)
            ) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            val sourceName = source.textValue("name").ifBlank { name }
            val refererValue = source.textValue("referer")
            val sourceHeaders = headers(source)

            callback(
                newExtractorLink(
                    name,
                    "$name - $sourceName",
                    streamUrl,
                    type = linkType,
                ) {
                    referer = refererValue
                    quality = quality(source.textValue("quality"))
                    headers = sourceHeaders
                }
            )
            emitted = true
        }

        return emitted
    }
}
