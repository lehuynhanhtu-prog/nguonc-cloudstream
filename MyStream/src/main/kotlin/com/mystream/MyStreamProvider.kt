package com.mystream

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
    private val archiveSearchUrl = "https://archive.org/advancedsearch.php"
    private val archiveMetadataUrl = "https://archive.org/metadata"
    private val archiveDownloadUrl = "https://archive.org/download"
    private val archivePageSize = 30

    override val mainPage = mainPageOf(
        "archive" to "Kho phim đầy đủ - Internet Archive",
        "home" to "MyStream tuyển chọn",
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


    private data class ArchivePage(
        val docs: List<JsonNode>,
        val hasNext: Boolean,
    )

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun pathEncode(value: String): String =
        value.split('/').joinToString("/") { part ->
            urlEncode(part).replace("+", "%20")
        }

    private fun archiveItemUrl(identifier: String): String =
        primaryCatalogUrl + "#ia=" + identifier

    private fun archivePosterUrl(identifier: String): String =
        "https://archive.org/services/img/" + pathEncode(identifier)

    private fun archiveYear(node: JsonNode): Int? {
        val yearNode = node.path("year")
        if (yearNode.isNumber) return yearNode.asInt().takeIf { it > 0 }
        val raw = if (yearNode.isArray) {
            yearNode.firstOrNull()?.asText("").orEmpty()
        } else {
            yearNode.asText("")
        }
        return Regex("(18|19|20)\\d{2}").find(raw)?.value?.toIntOrNull()
    }

    private fun archiveDocToSearch(doc: JsonNode): SearchResponse? {
        val identifier = doc.textValue("identifier").ifBlank { return null }
        val title = doc.textValue("title").ifBlank { identifier }
        return newMovieSearchResponse(title, archiveItemUrl(identifier), TvType.Movie) {
            posterUrl = archivePosterUrl(identifier)
            year = archiveYear(doc)
        }
    }

    private suspend fun archivePage(page: Int, query: String? = null): ArchivePage {
        val baseQuery = "mediatype:movies AND collection:feature_films"
        val fullQuery = query
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { baseQuery + " AND title:(" + it + ")" }
            ?: baseQuery

        val safePage = page.coerceAtLeast(1)
        val url = archiveSearchUrl +
            "?q=" + urlEncode(fullQuery) +
            "&fl%5B%5D=identifier" +
            "&fl%5B%5D=title" +
            "&fl%5B%5D=year" +
            "&fl%5B%5D=date" +
            "&fl%5B%5D=description" +
            "&sort%5B%5D=downloads+desc" +
            "&rows=" + archivePageSize +
            "&page=" + safePage +
            "&output=json"

        val root = fetchJson(url) ?: return ArchivePage(emptyList(), false)
        val response = root.path("response")
        val docs = response.path("docs").takeIf { it.isArray }?.toList().orEmpty()
        val total = response.path("numFound").asInt(docs.size)
        return ArchivePage(
            docs = docs,
            hasNext = safePage * archivePageSize < total,
        )
    }

    private fun homeList(title: String, nodes: List<JsonNode>): HomePageList? {
        val results = nodes.mapNotNull(::toSearchResponse)
        return results.takeIf { it.isNotEmpty() }?.let { HomePageList(title, it) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "archive") {
            val archive = archivePage(page)
            return newHomePageResponse(
                request,
                archive.docs.mapNotNull(::archiveDocToSearch),
                archive.hasNext,
            )
        }

        // Keep the editable JSON catalog as a separate curated shelf.  Do not mix
        // it with the paginated Internet Archive source: CloudStream paginates one
        // MainPageRequest at a time, and mixing both sources made the home screen
        // appear to contain only the handful of local demo/catalog entries.
        if (page > 1) {
            return newHomePageResponse(request, emptyList(), false)
        }

        val all = items()
            .sortedWith(
                compareByDescending<JsonNode> { it.textValue("updatedAt") }
                    .thenByDescending { it.path("year").asInt(0) }
            )
        return newHomePageResponse(request, all.mapNotNull(::toSearchResponse), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()

        val local = items().filter { item ->
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

        val remote = runCatching {
            archivePage(1, query).docs.mapNotNull(::archiveDocToSearch)
        }.getOrDefault(emptyList())

        return local + remote
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
        val archiveId = url.substringAfter("#ia=", "").substringBefore('&').trim()
        if (archiveId.isNotBlank()) {
            return loadArchiveItem(archiveId)
        }

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


    private fun flexibleText(node: JsonNode, key: String): String {
        val value = node.path(key)
        return when {
            value.isArray -> value.firstOrNull()?.asText("").orEmpty().trim()
            value.isTextual || value.isNumber -> value.asText("").trim()
            else -> ""
        }
    }

    private fun flexibleList(node: JsonNode, key: String): List<String> {
        val value = node.path(key)
        return when {
            value.isArray -> value.map { it.asText("").trim() }.filter { it.isNotBlank() }
            value.isTextual -> value.asText("")
                .split(';', ',', '|')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun archiveDirectUrl(identifier: String, fileName: String): String =
        archiveDownloadUrl + "/" + pathEncode(identifier) + "/" + pathEncode(fileName)

    private fun archiveQuality(fileName: String): String {
        val match = Regex("(2160|1440|1080|720|480|360|240)p", RegexOption.IGNORE_CASE)
            .find(fileName)
            ?.groupValues
            ?.getOrNull(1)
        return match?.plus("p").orEmpty()
    }

    private suspend fun loadArchiveItem(identifier: String): LoadResponse? {
        val root = fetchJson(archiveMetadataUrl + "/" + pathEncode(identifier)) ?: return null
        val metadata = root.path("metadata")
        val title = flexibleText(metadata, "title").ifBlank { identifier }
        val archivePlot = flexibleText(metadata, "description")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { null }
        val dateText = flexibleText(metadata, "date")
        val yearValue = Regex("(18|19|20)\\d{2}")
            .find(dateText)
            ?.value
            ?.toIntOrNull()
        val tagsValue = (
            flexibleList(metadata, "subject") +
                flexibleList(metadata, "keywords")
            ).distinct().take(20).ifEmpty { null }

        val files = root.path("files")
            .takeIf { it.isArray }
            ?.filter { file ->
                val name = file.textValue("name")
                val size = file.path("size").asLong(0L)
                name.endsWith(".mp4", true) &&
                    size >= 1_000_000L &&
                    size <= 3_000_000_000L
            }
            .orEmpty()
            .sortedWith(
                compareBy<JsonNode> {
                    if (it.textValue("source").equals("derivative", true)) 0 else 1
                }.thenBy { it.path("size").asLong(Long.MAX_VALUE) }
            )

        if (files.isEmpty()) return null

        val payload = mapper.createObjectNode()
        val sources = mapper.createArrayNode()
        files.take(6).forEach { file ->
            val fileName = file.textValue("name")
            val source = mapper.createObjectNode()
            val format = file.textValue("format").ifBlank { "MP4" }
            source.put("name", "Internet Archive - " + format)
            source.put("url", archiveDirectUrl(identifier, fileName))
            source.put("type", "video")
            source.put("quality", archiveQuality(fileName))
            sources.add(source)
        }

        val subtitles = mapper.createArrayNode()
        root.path("files").takeIf { it.isArray }?.forEach { file ->
            val fileName = file.textValue("name")
            if (!fileName.endsWith(".vtt", true) && !fileName.endsWith(".srt", true)) {
                return@forEach
            }
            val subtitle = mapper.createObjectNode()
            subtitle.put("name", fileName.substringBeforeLast('.'))
            subtitle.put("lang", "Sub")
            subtitle.put("url", archiveDirectUrl(identifier, fileName))
            subtitles.add(subtitle)
        }

        payload.set<JsonNode>("sources", sources)
        payload.set<JsonNode>("subtitles", subtitles)

        return newMovieLoadResponse(
            title,
            archiveItemUrl(identifier),
            TvType.Movie,
            payload.toString(),
        ) {
            posterUrl = archivePosterUrl(identifier)
            plot = archivePlot
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
