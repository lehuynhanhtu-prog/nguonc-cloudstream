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
    private val pageSize = 30
    private val cacheTtlMs = 15_000L

    @Volatile
    private var cachedCatalog: JsonNode? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    override val mainPage = mainPageOf(
        "featured" to "Nổi bật",
        "latest" to "Mới cập nhật",
        "movie" to "Phim lẻ",
        "series" to "Phim bộ",
        "anime" to "Anime",
        "all" to "Tất cả",
    )

    private val fallbackCatalog = """
        {
          "version": 2,
          "updatedAt": "2026-11-04",
          "items": [
            {
              "id": "big-buck-bunny",
              "title": "Big Buck Bunny",
              "type": "movie",
              "year": 2008,
              "updatedAt": "2026-11-04",
              "featured": true,
              "poster": "https://storage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg",
              "plot": "Nội dung mẫu công khai dùng để kiểm tra MyStream.",
              "tags": ["Mẫu", "Công khai"],
              "sources": [
                {
                  "name": "HLS Adaptive",
                  "url": "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                  "type": "hls",
                  "quality": "1080p"
                }
              ],
              "subtitles": []
            }
          ]
        }
    """.trimIndent()

    private fun JsonNode.textValue(key: String): String =
        path(key).asText("").trim()

    private fun JsonNode.stringList(key: String): List<String> =
        path(key).takeIf { it.isArray }
            ?.map { it.asText("").trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private suspend fun catalog(forceRefresh: Boolean = false): JsonNode {
        val now = System.currentTimeMillis()
        cachedCatalog?.let { cached ->
            if (!forceRefresh && now - cachedAtMs < cacheTtlMs) return cached
        }

        val remote = runCatching {
            app.get(
                catalogUrl,
                headers = mapOf(
                    "Accept" to "application/json,text/plain,*/*",
                    "Cache-Control" to "no-cache",
                )
            ).text
        }.getOrNull()

        val parsed = runCatching {
            mapper.readTree(remote?.takeIf { it.isNotBlank() } ?: fallbackCatalog)
        }.getOrElse {
            mapper.readTree(fallbackCatalog)
        }

        cachedCatalog = parsed
        cachedAtMs = now
        return parsed
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

    private fun itemId(item: JsonNode): String =
        item.textValue("id").ifBlank {
            item.textValue("title")
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
        }

    private fun itemUrl(item: JsonNode): String =
        "$catalogUrl#item=${itemId(item)}"

    private fun itemSortKey(item: JsonNode): String =
        item.textValue("updatedAt").ifBlank {
            item.path("year").asInt(0).toString().padStart(4, '0')
        }

    private fun combinedTags(item: JsonNode): List<String> =
        (item.stringList("genres") + item.stringList("tags")).distinct()

    private fun toSearchResponse(item: JsonNode): SearchResponse? {
        val title = item.textValue("title").ifBlank { return null }
        return newMovieSearchResponse(title, itemUrl(item), itemType(item)) {
            posterUrl = item.textValue("poster").ifBlank { null }
            year = item.path("year").asInt(0).takeIf { it > 0 }
        }
    }

    private fun sectionItems(section: String, all: List<JsonNode>): List<JsonNode> {
        val latest = all.sortedByDescending(::itemSortKey)
        return when (section.lowercase()) {
            "featured" -> latest.filter { it.path("featured").asBoolean(false) }.ifEmpty { latest.take(20) }
            "latest" -> latest
            "movie" -> latest.filter { itemType(it) == TvType.Movie }
            "series" -> latest.filter { itemType(it) == TvType.TvSeries }
            "anime" -> latest.filter { itemType(it) == TvType.Anime }
            else -> latest
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val filtered = sectionItems(request.data.ifBlank { "all" }, items())
        val safePage = page.coerceAtLeast(1)
        val from = (safePage - 1) * pageSize
        if (from >= filtered.size) {
            return newHomePageResponse(request, emptyList(), false)
        }

        val to = minOf(from + pageSize, filtered.size)
        val pageItems = filtered.subList(from, to).mapNotNull(::toSearchResponse)
        return newHomePageResponse(request, pageItems, to < filtered.size)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        return items()
            .filter { item ->
                val haystack = buildString {
                    append(item.textValue("title"))
                    append(' ')
                    append(item.textValue("originalTitle"))
                    append(' ')
                    append(item.textValue("country"))
                    append(' ')
                    append(item.textValue("status"))
                    append(' ')
                    combinedTags(item).forEach {
                        append(it)
                        append(' ')
                    }
                }.lowercase()
                words.all { it in haystack }
            }
            .sortedByDescending(::itemSortKey)
            .mapNotNull(::toSearchResponse)
    }

    private fun playbackData(item: JsonNode, episode: JsonNode? = null): String {
        val sourceNode = episode?.path("sources")
            ?.takeIf { it.isArray && it.size() > 0 }
            ?: item.path("sources")

        val mergedSubtitles = mapper.createArrayNode()
        item.path("subtitles").takeIf { it.isArray }?.forEach { mergedSubtitles.add(it) }
        episode?.path("subtitles")?.takeIf { it.isArray }?.forEach { mergedSubtitles.add(it) }

        return mapper.writeValueAsString(
            mapOf(
                "sources" to sourceNode,
                "subtitles" to mergedSubtitles,
            )
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfter("#item=", "").substringBefore('&').trim()
        if (id.isBlank()) return null

        val item = items().firstOrNull { itemId(it) == id } ?: return null
        val title = item.textValue("title").ifBlank { id }
        val type = itemType(item)
        val detailUrl = itemUrl(item)
        val poster = item.textValue("poster").ifBlank { null }
        val backdrop = item.textValue("backdrop").ifBlank { null }
        val plotText = item.textValue("plot").ifBlank { null }
        val yearValue = item.path("year").asInt(0).takeIf { it > 0 }
        val tagsValue = combinedTags(item).takeIf { it.isNotEmpty() }

        val episodeNodes = item.path("episodes").takeIf { it.isArray }?.toList().orEmpty()
        if (episodeNodes.isNotEmpty()) {
            val episodes = episodeNodes.mapIndexed { index, episode ->
                newEpisode(playbackData(item, episode)) {
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

        return newMovieLoadResponse(title, detailUrl, type, playbackData(item)) {
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

    private fun linkType(source: JsonNode, url: String): ExtractorLinkType {
        val type = source.textValue("type").lowercase()
        return when {
            type == "hls" || type == "m3u8" || url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            type == "dash" || type == "mpd" || url.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
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
                    subtitle.textValue("lang").ifBlank {
                        subtitle.textValue("name").ifBlank { "Sub" }
                    },
                    url,
                )
            )
        }

        var emitted = false
        node.path("sources").takeIf { it.isArray }?.forEach { source ->
            if (source.path("enabled").isBoolean && !source.path("enabled").asBoolean()) {
                return@forEach
            }

            val streamUrl = source.textValue("url")
            if (streamUrl.isBlank()) return@forEach

            val sourceName = source.textValue("name").ifBlank { name }
            val refererValue = source.textValue("referer")
            val sourceType = source.textValue("type").lowercase()

            if (sourceType == "embed" || sourceType == "extractor") {
                loadExtractor(
                    streamUrl,
                    refererValue.ifBlank { null },
                    subtitleCallback,
                ) { link ->
                    callback(link)
                    emitted = true
                }
                return@forEach
            }

            callback(
                newExtractorLink(
                    name,
                    "$name - $sourceName",
                    streamUrl,
                    type = linkType(source, streamUrl),
                ) {
                    referer = refererValue
                    quality = quality(source.textValue("quality"))
                    headers = headers(source)
                }
            )
            emitted = true
        }

        return emitted
    }
}
