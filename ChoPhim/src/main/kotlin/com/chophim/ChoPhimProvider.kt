package com.chophim

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class ChoPhimProvider : MainAPI() {
    override var mainUrl = "https://chophim.cam"
    override var name = "Chợ Phim"
    override var lang = "vi"
    override val hasMainPage = true
    override val usesWebView = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val loadLinksTimeoutMs: Long? = 60_000L

    private val cloudflareKiller by lazy { CloudflareKiller() }

    private val mirrors = listOf(
        "https://chophim.cam",
        "https://chophim.app",
    )

    override val mainPage = mainPageOf(
        "/" to "Mới cập nhật",
        "/phim-bo" to "Phim bộ",
        "/phim-le" to "Phim lẻ",
        "/hoat-hinh" to "Hoạt hình",
    )

    private fun browserHeaders(referer: String = mainUrl) = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer,
        "Cache-Control" to "no-cache",
    )

    private fun absolute(base: String, raw: String): String {
        if (raw.isBlank()) return ""
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> runCatching { URI(base).resolve(raw).toString() }.getOrDefault(raw)
        }
    }

    private suspend fun getHtml(url: String, referer: String = mainUrl): String {
        val direct = runCatching {
            val response = app.get(
                url,
                headers = browserHeaders(referer),
                referer = referer,
                interceptor = cloudflareKiller,
            )
            if (response.code in 200..299) response.text else ""
        }.getOrDefault("")
        if (direct.isNotBlank() && !direct.contains("Just a moment", true)) return direct

        return runCatching {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""https?://(?:www\.)?chophim\.(?:cam|app)/.*""", RegexOption.IGNORE_CASE),
                userAgent = null,
                useOkhttp = false,
                timeout = 25_000L,
            )
            app.get(
                url = url,
                referer = referer,
                headers = browserHeaders(referer),
                interceptor = resolver,
            ).text
        }.getOrDefault("")
    }

    private suspend fun getDocument(pathOrUrl: String): Pair<String, Document>? {
        val candidates = if (pathOrUrl.startsWith("http")) {
            listOf(pathOrUrl)
        } else {
            mirrors.map { it.trimEnd('/') + "/" + pathOrUrl.trimStart('/') }
        }
        for (url in candidates) {
            val html = getHtml(url, url.substringBeforeLast('/', "$url/"))
            if (html.isBlank()) continue
            val doc = Jsoup.parse(html, url)
            if (doc.title().isNotBlank() || doc.selectFirst("body") != null) {
                return url to doc
            }
        }
        return null
    }

    private fun isMovieHref(href: String): Boolean {
        val p = href.lowercase()
        if (p.isBlank() || p == "/" || p.startsWith("#") || p.startsWith("javascript:")) return false
        if (p.contains("/xem-phim") || p.contains("/watch")) return false
        if (p.contains("/the-loai") || p.contains("/quoc-gia") || p.contains("/dien-vien") || p.contains("/dao-dien")) return false
        if (p.contains("/tim-kiem") || p.contains("/search") || p.contains("/login") || p.contains("/dang-nhap")) return false
        return true
    }

    private fun titleFromCard(a: Element): String {
        val imageAlt = a.selectFirst("img[alt]")?.attr("alt").orEmpty()
            .replace(Regex("^Poster\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
        if (imageAlt.isNotBlank()) return imageAlt

        return listOf(
            "h1", "h2", "h3", "[class*=title]", "[class*=name]"
        ).asSequence()
            .mapNotNull { selector -> a.selectFirst(selector)?.text()?.trim() }
            .firstOrNull { it.length >= 2 }
            .orEmpty()
    }

    private fun posterFromCard(a: Element, base: String): String? {
        val img = a.selectFirst("img") ?: return null
        val raw = listOf(
            img.attr("src"),
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        return absolute(base, raw).takeIf { it.startsWith("http") }
    }

    private fun cardResponses(doc: Document, base: String): List<SearchResponse> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<SearchResponse>()

        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            if (!isMovieHref(href)) continue
            val hasPoster = a.selectFirst("img") != null
            val title = titleFromCard(a)
            if (!hasPoster || title.isBlank()) continue
            val url = absolute(base, href)
            if (!seen.add(url)) continue

            val text = a.text().lowercase()
            val type = when {
                text.contains("tập ") || text.contains("episode") || text.contains("phần ") -> TvType.TvSeries
                text.contains("anime") || text.contains("hoạt hình") -> TvType.Anime
                else -> TvType.Movie
            }

            out += newMovieSearchResponse(title, url, type) {
                posterUrl = posterFromCard(a, base)
                year = Regex("""(?:19|20)\d{2}""").find(a.text())?.value?.toIntOrNull()
            }
        }
        return out
    }

    private fun withPage(path: String, page: Int): String {
        if (page <= 1) return path
        val sep = if ('?' in path) '&' else '?'
        return path + sep + "page=" + page
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val target = withPage(request.data, page)
        val fetched = getDocument(target)
            ?: return newHomePageResponse(request, emptyList(), false)
        val (url, doc) = fetched
        val items = cardResponses(doc, url)

        val hasNext = doc.select("a[href*='page='], a[rel=next], button").any { el ->
            val label = el.text().lowercase()
            label.contains("tiếp") || label.contains("sau") || label.contains((page + 1).toString())
        } || items.size >= 18

        return newHomePageResponse(request, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val candidates = listOf(
            "/tim-kiem?keyword=$q",
            "/tim-kiem?q=$q",
            "/search?q=$q",
            "/?s=$q",
        )

        for (candidate in candidates) {
            val fetched = getDocument(candidate) ?: continue
            val (url, doc) = fetched
            val results = cardResponses(doc, url)
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    private fun pageTitle(doc: Document): String {
        return listOf(
            doc.selectFirst("h1")?.text(),
            doc.selectFirst("meta[property=og:title]")?.attr("content"),
            doc.title().substringBefore(" - Chợ Phim").substringBefore(" | Chợ Phim"),
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    private fun pagePoster(doc: Document, base: String): String? {
        val raw = listOf(
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            doc.selectFirst("img[alt*=Poster]")?.attr("src"),
            doc.selectFirst("main img")?.attr("src"),
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        return absolute(base, raw).takeIf { it.startsWith("http") }
    }

    private fun pagePlot(doc: Document): String? {
        return listOf(
            doc.selectFirst("meta[name=description]")?.attr("content"),
            doc.selectFirst("meta[property=og:description]")?.attr("content"),
            doc.selectFirst("[class*=description]")?.text(),
            doc.selectFirst("[class*=content] p")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun episodeLinks(doc: Document, base: String): List<Pair<String, String>> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<Pair<String, String>>()
        val selectors = "a[href], button[data-href], button[data-url], [data-href], [data-url]"

        for (el in doc.select(selectors)) {
            val raw = el.attr("href").ifBlank { el.attr("data-href") }.ifBlank { el.attr("data-url") }.trim()
            if (raw.isBlank()) continue
            val labelText = el.text().lowercase()
            val rawLower = raw.lowercase()
            val looksPlayable = labelText.contains("xem") || labelText.contains("tập") || labelText.contains("episode") ||
                rawLower.contains("xem-phim") || rawLower.contains("/watch") || rawLower.contains("/tap-") || rawLower.contains("/episode/")
            if (!looksPlayable) continue
            val url = absolute(base, raw)
            if (!seen.add(url)) continue
            val label = el.text().trim().ifBlank {
                Regex("""(?:tap|episode)[-_ /]?(\d+)""", RegexOption.IGNORE_CASE)
                    .find(raw)?.groupValues?.getOrNull(1)?.let { "Tập $it" } ?: "Xem phim"
            }
            out += label to url
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val fetched = getDocument(url) ?: return null
        val (resolvedUrl, doc) = fetched
        val title = pageTitle(doc).ifBlank { return null }
        val poster = pagePoster(doc, resolvedUrl)
        val plot = pagePlot(doc)
        val year = Regex("""(?:19|20)\d{2}""").find(doc.text())?.value?.toIntOrNull()
        val episodes = episodeLinks(doc, resolvedUrl)

        if (episodes.size > 1) {
            return newTvSeriesLoadResponse(
                title,
                resolvedUrl,
                TvType.TvSeries,
                episodes.mapIndexed { index, pair ->
                    newEpisode(pair.second) {
                        name = pair.first
                        episode = Regex("""\d+""").find(pair.first)?.value?.toIntOrNull() ?: (index + 1)
                    }
                },
            ) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        val playUrl = episodes.firstOrNull()?.second ?: resolvedUrl
        return newMovieLoadResponse(title, resolvedUrl, TvType.Movie, playUrl) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private fun directMediaUrls(html: String, base: String): List<String> {
        val clean = html.replace("\\/", "/")
        val regex = Regex(
            """https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""",
            RegexOption.IGNORE_CASE,
        )
        return regex.findAll(clean)
            .map { absolute(base, it.value) }
            .distinct()
            .toList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = getHtml(data, mainUrl)
        if (html.isBlank()) return false
        val doc = Jsoup.parse(html, data)
        var emitted = false

        for (stream in directMediaUrls(html, data)) {
            callback(
                newExtractorLink(
                    name,
                    "$name - Trực tiếp",
                    stream,
                    type = if (stream.substringBefore('?').endsWith(".m3u8", true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    },
                ) {
                    referer = data
                    quality = Qualities.Unknown.value
                    headers = browserHeaders(data)
                }
            )
            emitted = true
        }

        val embeds = linkedSetOf<String>()
        doc.select("iframe[src], source[src], video[src], a[href]").forEach { el ->
            val raw = el.attr("src").ifBlank { el.attr("href") }.trim()
            if (raw.isBlank()) return@forEach
            val absoluteUrl = absolute(data, raw)
            if (!absoluteUrl.startsWith("http")) return@forEach
            if (absoluteUrl == data) return@forEach
            if (
                absoluteUrl.contains("embed", true) ||
                absoluteUrl.contains("player", true) ||
                absoluteUrl.contains("watch", true) ||
                absoluteUrl.contains(".m3u8", true) ||
                absoluteUrl.contains(".mp4", true)
            ) {
                embeds += absoluteUrl
            }
        }

        for (embed in embeds.take(12)) {
            if (embed.contains(".m3u8", true) || embed.contains(".mp4", true)) continue
            var extracted = false
            runCatching {
                loadExtractor(embed, data, subtitleCallback) { link ->
                    extracted = true
                    callback(link)
                }
            }
            if (extracted) emitted = true
        }

        if (!emitted) {
            runCatching {
                val resolver = WebViewResolver(
                    interceptUrl = Regex(
                        """(?i)(?:\.m3u8(?:[?#]|$)|\.mp4(?:[?#]|$)|/[^?#]*playlist[^?#]*(?:[?#]|$))"""
                    ),
                    userAgent = null,
                    useOkhttp = false,
                    timeout = 30_000L,
                )
                val response = app.get(
                    url = data,
                    referer = mainUrl,
                    headers = browserHeaders(mainUrl),
                    interceptor = resolver,
                )
                val intercepted = response.url
                if (intercepted.contains(".m3u8", true) || intercepted.contains(".mp4", true)) {
                    callback(
                        newExtractorLink(
                            name,
                            "$name - WebView",
                            intercepted,
                            type = if (intercepted.contains(".m3u8", true)) {
                                ExtractorLinkType.M3U8
                            } else {
                                ExtractorLinkType.VIDEO
                            },
                        ) {
                            referer = data
                            quality = Qualities.Unknown.value
                            headers = browserHeaders(data)
                        }
                    )
                    emitted = true
                }
            }
        }

        return emitted
    }
}
