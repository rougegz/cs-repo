package com.dramafren

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class DramaFrenProvider : MainAPI() {
    override var name = "DramaFren"
    override var mainUrl = DramaFrenStore.base()
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L

    override val mainPage = mainPageOf(
        *DRAMAFREN_CATALOG.map { (title, slug) -> slug to title }.toTypedArray()
    )

    private fun exploreUrl(provider: String, page: Int): String =
        providerExploreUrl(DramaFrenStore.apiBase(), provider, page)

    private fun searchUrl(query: String): String =
        "${DramaFrenStore.apiBase()}/search?lang=en&q=${java.net.URLEncoder.encode(query, "UTF-8")}"

    private fun dramaUrl(provider: String, id: String, slug: String = "x"): String =
        "${DramaFrenStore.apiBase()}/drama/$provider/$id-$slug?lang=en"

    private fun watchUrl(provider: String, id: String, slug: String = "x", ep: Int): String =
        "${DramaFrenStore.apiBase()}/watch/$provider/$id-$slug?ep=$ep&lang=en"

    private fun cfHeaders(url: String): Map<String, String> {
        val base = browserHeaders().toMutableMap()
        DramaFrenStore.getCfCookieForUrl(url)?.let { ck -> base["Cookie"] = ck }
        return base
    }

    private fun isCloudflare(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("just a moment") || lower.contains("challenge-platform") ||
            lower.contains("cf-browser-verification") || lower.contains("_cf_chl") ||
            lower.contains("attention required") && lower.contains("cloudflare")
    }

    private val lastFirst = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = exploreUrl(request.data, page)
        val cards = fetchCards(url)
        if (cards.isEmpty()) return newHomePageResponse(request, emptyList(), hasNext = false)
        val first = cards.first().url
        val prev = lastFirst.put(request.data, first)
        if (prev == first && page > 1) return newHomePageResponse(request, emptyList(), hasNext = false)
        return newHomePageResponse(request, cards, hasNext = true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1 || query.isBlank()) return newSearchResponseList(emptyList(), false)
        val cards = fetchCards(searchUrl(query))
        return newSearchResponseList(cards, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query, 1).items.take(10)

    private suspend fun fetchCards(url: String): List<MovieSearchResponse> {
        val res = app.get(url, headers = cfHeaders(url), referer = "${DramaFrenStore.apiBase()}/", timeout = 30L, cacheTime = 10)
        val html = res.text
        if (isCloudflare(html)) return emptyList()
        val doc = Jsoup.parse(html, DramaFrenStore.apiBase())
        val out = ArrayList<MovieSearchResponse>(64)
        val seen = HashSet<String>(64)
        for (a in doc.select("a[href*=\"/drama/\"]")) {
            val href = a.attr("href").trim()
            if (!href.contains("/drama/")) continue
            if (!seen.add(href)) continue
            val img = a.selectFirst("img") ?: continue
            val poster = img.attr("src").trim().ifEmpty { img.attr("data-src").trim() }
            if (poster.isEmpty()) continue
            val title = img.attr("alt").trim().ifEmpty {
                a.selectFirst("h3, h2")?.text()?.trim() ?: a.text().trim().take(80)
            }
            if (title.isEmpty()) continue
            val abs = a.absUrl("href")
            out += newMovieSearchResponse(title, abs, TvType.AsianDrama) { this.posterUrl = poster }
        }
        if (out.isEmpty()) {
            val regex = Regex("""href="(/drama/[^"]+)".*?src="([^"]+)".*?alt="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            for (m in regex.findAll(html)) {
                val href = m.groupValues[1]
                val poster = m.groupValues[2]
                val title = m.groupValues[3].trim()
                if (title.isEmpty() || poster.isEmpty()) continue
                val abs = if (href.startsWith("http")) href else DramaFrenStore.apiBase() + href
                if (!seen.add(abs)) continue
                out += newMovieSearchResponse(title, abs, TvType.AsianDrama) { this.posterUrl = poster }
            }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val parsed = parseDramaUrl(url) ?: throw ErrorLoadingException("Unrecognized drama url: $url")
        val (provider, id) = parsed
        val res = app.get(url, headers = cfHeaders(url), referer = "${DramaFrenStore.apiBase()}/", timeout = 30L, cacheTime = 30)
        val html = res.text
        if (isCloudflare(html)) throw ErrorLoadingException("Cloudflare challenge — solve via Settings → DramaFren → Solve Cloudflare")
        val doc = Jsoup.parse(html, DramaFrenStore.apiBase())
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content")?.trim()
            ?: "Unknown"
        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")?.trim()
            ?: doc.selectFirst("img[src*=\"wsrv.nl\"], img[src*=\"fizzopic\"]")?.attr("src")?.trim()
        val plot = doc.selectFirst("meta[name=\"description\"]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()

        val eps = doc.select("a.episode-link, a[href*=\"/watch/\"]").mapNotNull { a ->
            val href = a.attr("href").trim()
            val m = Regex("""[?&]ep=(\d+)""").find(href) ?: return@mapNotNull null
            val num = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val abs = a.absUrl("href")
            num to abs
        }.distinctBy { it.first }.sortedBy { it.first }

        val episodes = if (eps.isNotEmpty()) eps else {
            val count = Regex("""(\d+)\s*EP""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            (1..count).map { it to watchUrl(provider, id, "x", it) }
        }

        val tag = listOf(PROVIDER_NAMES[provider] ?: provider)

        if (episodes.size <= 1) {
            val (_, epUrl) = episodes.first()
            return newMovieLoadResponse(title, url, TvType.Movie, epUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tag
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes.map { (num, epUrl) ->
            newEpisode(epUrl) {
                this.name = "Episode $num"
                this.episode = num
            }
        }) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tag
        }
    }

    private fun qualityFrom(label: String?, url: String): Int {
        getQualityFromName(label).takeIf { it != Qualities.Unknown.value }?.let { return it }
        val u = url.lowercase()
        return when {
            u.contains("2160") || u.contains("4k") -> Qualities.P2160.value
            u.contains("1080") || u.contains("fhd") -> Qualities.P1080.value
            u.contains("720") -> Qualities.P720.value
            u.contains("480") -> Qualities.P480.value
            u.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun linkType(url: String, apiType: String?): ExtractorLinkType = when {
        apiType.equals("hls", true) || url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", true) -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val res = app.get(data, headers = cfHeaders(data), referer = "${DramaFrenStore.apiBase()}/", timeout = 30L, cacheTime = 0)
        val html = res.text
        if (isCloudflare(html)) return false

        val seen = HashSet<String>()
        val urlRegex = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
        val mp4Regex = Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""")
        val m3u8s = urlRegex.findAll(html).map { it.value }.toList()
        val mp4s = mp4Regex.findAll(html).map { it.value }.toList()
        val all = (m3u8s + mp4s).distinct()

        val jsonQualityRegex = Regex(""""url"\s*:\s*"([^"]+\.m3u8[^"]*)".*?"quality"\s*:\s*"([^"]+)"""")
        val qualities = jsonQualityRegex.findAll(html).map { it.groupValues[2] to it.groupValues[1] }.toList()

        val subRegex = Regex(""""(?:label|language)"\s*:\s*"([^"]+)".*?"url"\s*:\s*"([^"]+\.vtt[^"]*)"""")
        for (m in subRegex.findAll(html)) {
            val label = m.groupValues[1]
            val url = m.groupValues[2]
            if (url.isNotBlank() && seen.add(url)) subtitleCallback(newSubtitleFile(label.ifBlank { "en" }, url))
        }
        val subRegex2 = Regex("""https?://[^"']+\.vtt[^"']*""")
        for (m in subRegex2.findAll(html)) {
            val url = m.value
            if (seen.add(url)) subtitleCallback(newSubtitleFile("en", url))
        }

        if (qualities.isNotEmpty()) {
            for ((label, url) in qualities) {
                if (!seen.add(url)) continue
                callback(newExtractorLink(name, name, url) {
                    this.referer = "${DramaFrenStore.apiBase()}/"
                    this.quality = qualityFrom(label, url)
                    this.type = linkType(url, null)
                })
            }
            return true
        }

        if (all.isNotEmpty()) {
            for (url in all) {
                callback(newExtractorLink(name, name, url) {
                    this.referer = "${DramaFrenStore.apiBase()}/"
                    this.quality = qualityFrom(null, url)
                    this.type = linkType(url, null)
                })
            }
            return true
        }

        val doc = Jsoup.parse(html, DramaFrenStore.apiBase())
        for (v in doc.select("video source, video")) {
            val src = v.attr("src").ifEmpty { v.attr("data-src") }.trim()
            if (src.isNotEmpty() && src.startsWith("http") && seen.add(src)) {
                callback(newExtractorLink(name, name, src) {
                    this.referer = "${DramaFrenStore.apiBase()}/"
                    this.quality = qualityFrom(null, src)
                    this.type = linkType(src, null)
                })
            }
        }

        val apiRegex = Regex(""""/api/[^"]*"""")
        for (m in apiRegex.findAll(html)) {
            val apiPath = m.value.trim('"')
            val apiUrl = if (apiPath.startsWith("http")) apiPath else DramaFrenStore.apiBase() + apiPath
            try {
                val apiRes = app.get(apiUrl, headers = cfHeaders(apiUrl), referer = data, timeout = 15L, cacheTime = 0).text
                val apiM3u8 = urlRegex.findAll(apiRes).map { it.value }.toList()
                for (u in apiM3u8) {
                    if (seen.add(u)) callback(newExtractorLink(name, name, u) {
                        this.referer = "${DramaFrenStore.apiBase()}/"
                        this.quality = qualityFrom(null, u)
                        this.type = ExtractorLinkType.M3U8
                    })
                }
                if (apiM3u8.isNotEmpty()) return true
            } catch (_: Exception) {}
        }

        return seen.isNotEmpty()
    }
}
