package com.mystream

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
    private val primaryCatalogUrl = "$mainUrl/mystream.json"
    private val feedIndexUrl = "$mainUrl/mystream-feeds.json"

    override val mainPage = mainPageOf(
        "home" to "MyStream",
    )

    private val fallbackCatalog = """
        {
          "version": 2,
          "items": [
            {
              "id": "big-buck-bunny",
              "title": "Big Buck Bunny",
              "type": "movie",
              "year": 2008,
              "featured": true,
              "section": "Phim mở",
              "genres": ["Hoạt hình", "Gia đình"],
              "tags": ["Blender", "Open Movie", "CC BY"],
              "poster": "https://commons.wikimedia.org/wiki/Special:Redirect/file/Big_buck_bunny_poster_big.jpg",
              "plot": "Phim hoạt hình mở của Blender Foundation, dùng làm nội dung dự phòng và kiểm thử playback MyStream.",
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

    private var cacheAt = 0L
    private var cacheItems: List<JsonNode> = emptyList()
    private val cacheTtlMs = 60_000L

    private fun JsonNode.textValue(key: String): String = path(key).asText("").trim()

    private fun JsonNode.stringList(key: String): List<String> =
        path(key).takeIf { it.isArray }
            ?.map { it.asText("").trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun enabled(node: JsonNode): Boolean =
        !node.has("enabled") || node.path("enabled").asBoolean(true)

    private fun normalizeCatalogUrl(value: String): String {
        val url = value.trim()
        if (url.startsWith("https://") || url.startsWith("http://")) return url
        return mainUrl + "/" + url.trimStart('/')
    }

    private suspend fun fetchJson(url: String): JsonNode? {
        val text = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "Accept" to "application/json,text/plain,*/*",
                    "Cache-Control" to "no-cache",
                )
            ).text
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        return runCatching { mapper.readTree(text) }.getOrNull()
    }

    private suspend fun catalogUrls(): List<String> {
        val index = fetchJson(feedIndexUrl) ?: return listOf(primaryCatalogUrl)
        val feeds = index.path("feeds").takeIf { it.isArray }?.toList().orEmpty()
        val urls = feeds
            .filter(::enabled)
            .mapNotNull { feed -> feed.textValue("url").takeIf { it.isNotBlank() } }
            .map(::normalizeCatalogUrl)
            .distinct()
        return urls.ifEmpty { listOf(primaryCatalogUrl) }
    }

    private suspend fun items(forceRefresh: Boolean = false): List<JsonNode> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cacheItems.isNotEmpty() && now - cacheAt < cacheTtlMs) {
            return cacheItems
        }

        val merged = linkedMapOf<String, JsonNode>()
        for (url in catalogUrls()) {
            val root = fetchJson(url) ?: continue
            root.path("items").takeIf { it.isArray }?.forEach { item ->
                if (!enabled(item)) return@forEach
                val id = item.textValue("id").ifBlank {
                    item.textValue("title")
                        .lowercase()
                        .replace(Regex("[^a-z0-9]+"), "-")
                        .trim('-')
                }
                if (id.isNotBlank()) merged[id] = item
            }
        }

        if (merged.isEmpty()) {
            val fallback = mapper.readTree(fallbackCatalog)
            fallback.path("items").forEach { item ->
                val id = item.textValue("id")
                if (id.isNotBlank()) merged[id] = item
            }
        }

        cacheItems = merged.values.toList()
        cacheAt = now
        return cacheItems
    }

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
        primaryCatalogUrl + "#item=" + itemId(item)

    private fun toSearchResponse(item: JsonNode): SearchResponse? {
        val title = item.textValue("title").ifBlank { return null }
        val type = itemType(item)
        return newMovieSearchResponse(title, itemUrl(item), type) {
            posterUrl = item.textValue("poster").ifBlank { null }
            year = item.path("year").asInt(0).takeIf { it > 0 }
        }
    }

    private fun homeList(title: String, nodes: List<JsonNode>): HomePageList? {
        val results = nodes.mapNotNull(::toSearchResponse)
        return results.takeIf { it.isNotEmpty() }?.let { HomePageList(title, it) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), false)

        val all = items()
        val lists = mutableListOf<HomePageList>()

        fun add(title: String, nodes: List<JsonNode>) {
            homeList(title, nodes)?.let(lists::add)
        }

        add("Nổi bật", all.filter { it.path("featured").asBoolean(false) })
        add(
            "Mới cập nhật",
            all.sortedWith(
                compareByDescending<JsonNode> { it.textValue("updatedAt") }
                    .thenByDescending { it.path("year").asInt(0) }
            ).take(40)
        )

        val customSections = all
            .filter { it.textValue("section").isNotBlank() }
            .groupBy { it.textValue("section") }
        customSections.forEach { (section, nodes) -> add(section, nodes) }

        add("Phim lẻ", all.filter { itemType(it) == TvType.Movie })
        add("Phim bộ", all.filter { itemType(it) == TvType.TvSeries })
        add("Anime", all.filter { itemType(it) == TvType.Anime })

        val genreOrder = all
            .flatMap { it.stringList("genres") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
        genreOrder.forEach { genre ->
            add(genre, all.filter { item -> item.stringList("genres").any { it.equals(genre, true) } })
        }

        if (lists.isEmpty()) {
            add("Thư viện MyStream", all)
        }

        return newHomePageResponse(lists.distinctBy { it.name }, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()

        return items().filter { item ->
            val haystack = buildString {
                append(item.textValue("title"))
                append(' ')
                append(item.textValue("originalTitle"))
                append(' ')
                append(item.textValue("plot"))
                append(' ')
                append(item.textValue("section"))
                append(' ')
                item.stringList("altTitles").forEach { append(it).append(' ') }
                item.stringList("tags").forEach { append(it).append(' ') }
                item.stringList("genres").forEach { append(it).append(' ') }
                item.stringList("actors").forEach { append(it).append(' ') }
            }.lowercase()
            terms.all { it in haystack }
        }.mapNotNull(::toSearchResponse)
    }

    private fun episodePayload(item: JsonNode, episode: JsonNode): String {
        val payload: ObjectNode = mapper.createObjectNode()
        val episodeSources = episode.path("sources")
        val episodeSubs = episode.path("subtitles")
        val itemSources = item.path("sources")
        val itemSubs = item.path("subtitles")

        payload.set<JsonNode>(
            "sources",
            if (episodeSources.isArray && episodeSources.size() > 0) episodeSources else itemSources
        )
        payload.set<JsonNode>(
            "subtitles",
            if (episodeSubs.isArray && episodeSubs.size() > 0) episodeSubs else itemSubs
        )
        return payload.toString()
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfter("#item=", "").substringBefore('&').trim()
        if (id.isBlank()) return null

        val item = items(forceRefresh = true).firstOrNull { itemId(it) == id } ?: return null
        val title = item.textValue("title").ifBlank { id }
        val type = itemType(item)
        val detailUrl = itemUrl(item)
        val poster = item.textValue("poster").ifBlank { null }
        val backdrop = item.textValue("backdrop").ifBlank { null }
        val plotText = item.textValue("plot").ifBlank { null }
        val yearValue = item.path("year").asInt(0).takeIf { it > 0 }
        val tagsValue = (item.stringList("genres") + item.stringList("tags")).distinct().ifEmpty { null }

        val episodeNodes = item.path("episodes")
            .takeIf { it.isArray }
            ?.filter(::enabled)
            .orEmpty()

        if (episodeNodes.isNotEmpty()) {
            val episodes = episodeNodes.mapIndexed { index, episode ->
                newEpisode(episodePayload(item, episode)) {
                    name = episode.textValue("name").ifBlank { "Tập " + (index + 1) }
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

    private fun quality(value: String): Int {
        val normalized = value.trim().lowercase().removeSuffix("p")
        return when (normalized) {
            "4k", "2160" -> Qualities.P2160.value
            "1440" -> Qualities.P1440.value
            "1080" -> Qualities.P1080.value
            "720" -> Qualities.P720.value
            "480" -> Qualities.P480.value
            "360" -> Qualities.P360.value
            "240" -> Qualities.P240.value
            "144" -> Qualities.P144.value
            else -> normalized.toIntOrNull() ?: Qualities.Unknown.value
        }
    }

    private fun linkType(source: JsonNode, url: String): ExtractorLinkType {
        return when (source.textValue("type").lowercase()) {
            "hls", "m3u8" -> ExtractorLinkType.M3U8
            "dash", "mpd" -> ExtractorLinkType.DASH
            "video", "mp4", "file" -> ExtractorLinkType.VIDEO
            else -> when {
                url.substringBefore('?').endsWith(".m3u8", true) -> ExtractorLinkType.M3U8
                url.substringBefore('?').endsWith(".mpd", true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
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
            if (!enabled(subtitle)) return@forEach
            val url = subtitle.textValue("url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEach
            subtitleCallback(
                newSubtitleFile(
                    subtitle.textValue("lang")
                        .ifBlank { subtitle.textValue("name") }
                        .ifBlank { "Sub" },
                    url,
                )
            )
        }

        var emitted = false
        node.path("sources").takeIf { it.isArray }?.forEach { source ->
            if (!enabled(source)) return@forEach

            val streamUrl = source.textValue("url")
            if (!streamUrl.startsWith("http://") && !streamUrl.startsWith("https://")) return@forEach

            val sourceName = source.textValue("name").ifBlank { name }
            val refererValue = source.textValue("referer")
            val sourceHeaders = headers(source)

            callback(
                newExtractorLink(
                    name,
                    "$name - $sourceName",
                    streamUrl,
                    type = linkType(source, streamUrl),
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
