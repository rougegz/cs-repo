package com.narto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class NartoProvider : MainAPI() {
    override var name = "Narto"
    override var mainUrl = DEFAULT_BASE_URL
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L

    override val mainPage = mainPageOf(
        *NARTO_CATALOG.map { (label, key) -> key to label }.toTypedArray()
    )

    private fun sectionsUrl(provider: String): String {
        // Site returns ALL sections in one call (no server pagination) — verified live.
        return "$mainUrl/home/providers/sections?provider=$provider&lang=en-US&target_lang=en-US" +
            "&_cb=${System.currentTimeMillis()}"
    }

    private fun searchUrl(query: String): String =
        "$mainUrl/search?lang=en-US&q=${java.net.URLEncoder.encode(query, "UTF-8")}"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request, emptyList(), hasNext = false)
        val url = sectionsUrl(request.data)
        val headers = NartoStore.injectCfCookies(url, nartoHeaders())
        val json = app.get(url, headers = headers, referer = "$mainUrl/", timeout = 30L, cacheTime = 5).parsedSafe<SectionsResponse>()
        val items = json?.sections?.flatMap { sec ->
            sec.items.mapNotNull { item ->
                val title = item.title?.trim() ?: return@mapNotNull null
                val watch = item.watch_url?.trim() ?: return@mapNotNull null
                val poster = item.poster_url?.trim() ?: ""
                // Category headers ("Popular") have no poster — skip them.
                if (poster.isEmpty()) return@mapNotNull null
                // watch_url is already absolute, contains provider & book_id
                newMovieSearchResponse(title, watch, TvType.AsianDrama) {
                    this.posterUrl = poster
                }
            }
        }?.distinctBy { it.url } ?: emptyList()

        // Single-shot: site returns everything at once, no server pagination.
        return newHomePageResponse(request, items, hasNext = false)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SectionsResponse(
        val ok: Boolean? = null,
        val sections: List<Section>? = null,
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Section(
        val tab_key: String? = null,
        val items: List<SectionItem> = emptyList(),
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SectionItem(
        val title: String? = null,
        val poster_url: String? = null,
        val watch_url: String? = null,
        val book_id: String? = null,
    )

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1 || query.isBlank()) return newSearchResponseList(emptyList(), false)
        val url = searchUrl(query)
        val headers = NartoStore.injectCfCookies(url, nartoHeaders())
        val html = app.get(url, headers = headers, referer = "$mainUrl/", timeout = 30L, cacheTime = 10).text
        val doc = Jsoup.parse(html, mainUrl)
        val out = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()
        // search results are similar cards: links to /detail/watch/...
        for (a in doc.select("a[href*=/detail/watch/]")) {
            val href = a.absUrl("href").substringBefore("?").trim()
            if (href.isEmpty() || !seen.add(href)) continue
            val img = a.selectFirst("img") ?: continue
            val title = (img.attr("alt").ifBlank { a.text() }).trim()
            if (title.isEmpty()) continue
            val poster = img.absUrl("src").ifBlank { img.absUrl("data-src") }
            out += newMovieSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = poster.ifBlank { null } }
        }
        // also try watch_url pattern from sections search fallback
        if (out.isEmpty()) {
            for (a in doc.select("a[href*=/search/import]")) {
                val href = a.absUrl("href").trim()
                if (href.isEmpty() || !seen.add(href)) continue
                val title = a.attr("title").ifBlank { a.text().trim() }
                if (title.isEmpty()) continue
                out += newMovieSearchResponse(title, href, TvType.AsianDrama)
            }
        }
        return newSearchResponseList(out, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items.take(10)

    override suspend fun load(url: String): LoadResponse {
        // watch_url or detail url -> fetch detail page, extract episodes
        val headers = NartoStore.injectCfCookies(url, nartoHeaders())
        val html = app.get(url, headers = headers, referer = "$mainUrl/", timeout = 30L, cacheTime = 10).text
        val doc = Jsoup.parse(html, url)

        // title from h1 or og:title
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"
        val poster = doc.selectFirst("article img")?.absUrl("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("article p")?.text()?.trim()
            ?: doc.selectFirst("meta[name=og:description]")?.attr("content")

        // episodes: links to /detail/watch/<slug>/<ep>?lang=
        val eps = doc.select("a[href*=/detail/watch/][href*=/]").mapNotNull { a ->
            val href = a.absUrl("href").trim()
            // filter only episode links (contain /<number>? or /<number> )
            val epNum = href.substringAfterLast("/").substringBefore("?").toIntOrNull() ?: return@mapNotNull null
            href to epNum
        }.distinctBy { it.first }.sortedBy { it.second }

        // fallback: if no numbered episodes, treat as movie with single watch link
        if (eps.isEmpty()) {
            // try to find watch/import link as movie
            val watchLink = doc.selectFirst("a[href*=/search/import]")?.absUrl("href") ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, watchLink) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = listOf("Narto")
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, eps.map { (epUrl, num) ->
            newEpisode(epUrl) {
                this.name = "Episode $num"
                this.episode = num
            }
        }) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = listOf("Narto")
        }
    }

    private fun qualityFrom(url: String, label: String?): Int {
        getQualityFromName(label).takeIf { it != Qualities.Unknown.value }?.let { return it }
        val u = url.lowercase()
        return when {
            u.contains("2160") || u.contains("4k") -> Qualities.P2160.value
            u.contains("1080") || u.contains("fhd") -> Qualities.P1080.value
            u.contains("720") -> Qualities.P720.value
            u.contains("480") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val headers = NartoStore.injectCfCookies(data, nartoHeaders())
        val html = app.get(data, headers = headers, referer = "$mainUrl/", timeout = 30L, cacheTime = 0).text

        // detect cloudflare challenge
        if (html.contains("Just a moment", true) || html.contains("challenge-platform", true) || html.contains("_cf_chl", true)) {
            // let user solve via settings WebView; fail gracefully
            return false
        }

        // primary: contentUrl in ld+json or meta
        val m3u8s = Regex("""https://[^"']+\.m3u8[^"']*""").findAll(html).map { it.value }.toList()
        val mp4s = Regex("""https://[^"']+\.mp4[^"']*""").findAll(html).map { it.value }.toList()
        val vtts = Regex("""https://[^"']+\.vtt[^"']*""").findAll(html).map { it.value }.toList()

        // subtitles: look for vtt
        vtts.forEach { sub ->
            val lang = when {
                sub.contains("en", true) -> "English"
                sub.contains("id", true) -> "Indonesia"
                else -> "English"
            }
            subtitleCallback(newSubtitleFile(lang, sub))
        }

        // also check for subtitle labels in html
        val subLabels = Regex("""label["']?\s*[:=]\s*["']([^"']+)["']""").findAll(html).map { it.groupValues[1] }.toList()

        val sources = (m3u8s + mp4s).distinct()
        if (sources.isEmpty()) {
            // try contentUrl from ld+json
            val contentUrl = Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            if (contentUrl != null && contentUrl.startsWith("http")) {
                val isM3u8 = contentUrl.contains(".m3u8")
                callback(newExtractorLink(name, "Narto", contentUrl) {
                    this.referer = "$mainUrl/"
                    this.quality = qualityFrom(contentUrl, null)
                    this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                })
                return true
            }
            return false
        }

        var found = false
        for (src in sources) {
            val isM3u8 = src.contains(".m3u8")
            // try to infer label from nearby text
            val label = subLabels.firstOrNull { it.contains("1080") || it.contains("720") || it.contains("480") }
            callback(newExtractorLink(name, "Narto", src) {
                this.referer = "$mainUrl/"
                this.quality = qualityFrom(src, label)
                this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            })
            found = true
        }

        // audio: check for audio tracks (often separate m3u8 with AUDIO group)
        // Narto pages sometimes list audio language in html like "Audio: English"
        val audioLangs = Regex("""Audio[^<]*:\s*([^<]+)""", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1].trim() }.toList()
        // we don't have separate audio extractor, but we can add subtitle-like handling: if audio found, add as subtitle note
        // quality already handled

        return found
    }
}
