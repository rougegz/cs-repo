package com.dramafren

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

/**
 * DramaFren — verified JSON API flow (Playwright-captured):
 *   listing : GET /api/home?offset=N&lang=en&provider=<slug>   -> {data:[{id,title,cover,intro}], offset}
 *   search  : GET /search?lang=en&q=...                        -> HTML cards
 *   detail  : GET /api/detail?provider=<slug>&id=<id>&lang=en  -> {title,cover,intro,episodes,videos}
 *   stream  : GET /api/video?provider&id&ep&lang=en&server=1&cv=v21 -> {videoUrl, qualityList[], locked}
 */
class DramaFrenProvider : MainAPI() {
    override var name = "DramaFren"
    override var mainUrl = DEFAULT_API_BASE
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

    private fun api(): String = DramaFrenStore.base()

    private fun homeApiUrl(provider: String, page: Int): String =
        "${api()}/api/home?offset=$page&lang=en&provider=${java.net.URLEncoder.encode(provider, "UTF-8")}"

    private fun detailApiUrl(provider: String, id: String): String =
        "${api()}/api/detail?provider=${java.net.URLEncoder.encode(provider, "UTF-8")}" +
            "&id=${java.net.URLEncoder.encode(id, "UTF-8")}&lang=en"

    private fun videoApiUrl(provider: String, id: String, ep: Int): String =
        "${api()}/api/video?provider=${java.net.URLEncoder.encode(provider, "UTF-8")}" +
            "&id=${java.net.URLEncoder.encode(id, "UTF-8")}&ep=$ep&lang=en&server=1&cv=v21"

    private fun cfHeaders(url: String): Map<String, String> {
        val base = browserHeaders().toMutableMap()
        base["Referer"] = "${api()}/"
        DramaFrenStore.getCfCookieForUrl(url)?.let { ck -> base["Cookie"] = ck }
        return base
    }

    private fun isCloudflare(html: String): Boolean {
        val l = html.lowercase()
        return l.contains("just a moment") || l.contains("challenge-platform") ||
            l.contains("cf-browser-verification") || l.contains("_cf_chl")
    }

    private val lastFirst = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ---- DTOs ----
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HomeItem(
        val id: String? = null,
        val provider: String? = null,
        val title: String? = null,
        val cover: String? = null,
        val intro: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HomeResponse(val data: List<HomeItem>? = null, val offset: Int? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchApiResponse(
        val query: String? = null,
        val count: Int? = null,
        val hasMore: Boolean? = null,
        val data: List<HomeItem>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DetailResponse(
        val id: String? = null,
        val title: String? = null,
        val cover: String? = null,
        val intro: String? = null,
        val episodes: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class QualityEntry(val label: String? = null, val url: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoResponse(
        val title: String? = null,
        val episodeNumber: Int? = null,
        val totalEpisodes: Int? = null,
        val locked: Boolean? = null,
        val videoUrl: String? = null,
        val qualityList: List<QualityEntry>? = null,
    )

    // ---- Home (endless via offset) ----
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // API offsets are 1-based pages of ~18 items
        val res = app.get(homeApiUrl(request.data, page), headers = cfHeaders(api()), timeout = 30L, cacheTime = if (page == 1) 5 else 10)
        val parsed = runCatching { res.parsedSafe<HomeResponse>() }.getOrNull()
        val items = parsed?.data.orEmpty().filter { !it.id.isNullOrBlank() && !it.title.isNullOrBlank() }
        if (items.isEmpty()) return newHomePageResponse(request, emptyList(), hasNext = false)

        val cards = items.map { it ->
            val providerSlug = it.provider ?: request.data
            newMovieSearchResponse(it.title!!, dramaUrl(providerSlug, it.id!!), TvType.AsianDrama) {
                this.posterUrl = it.cover
            }
        }
        val first = cards.first().url
        val prev = lastFirst.put(request.data, first)
        if (prev == first && page > 1) return newHomePageResponse(request, emptyList(), hasNext = false)
        return newHomePageResponse(request, cards, hasNext = true)
    }

    private fun dramaUrl(provider: String, id: String): String =
        "${api()}/drama/$provider/$id-x?lang=en"

    // ---- Search (JSON API, verified live) ----
    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        // API supports offset paging via &offset=N (page-1)*10 style; use it for endless search
        val url = "${api()}/api/search?lang=en&q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
            if (page > 1) "&offset=${(page - 1) * 10}" else ""
        val res = app.get(url, headers = cfHeaders(url), timeout = 30L, cacheTime = 5)
        val json = runCatching { res.parsedSafe<SearchApiResponse>() }.getOrNull()
            ?: return newSearchResponseList(emptyList(), false)
        val items = json.data.orEmpty().filter { !it.id.isNullOrBlank() && !it.title.isNullOrBlank() }
        val cards = items.map {
            newMovieSearchResponse(it.title!!, dramaUrl(it.provider ?: "", it.id!!), TvType.AsianDrama) {
                this.posterUrl = it.cover
            }
        }
        return newSearchResponseList(cards, hasNext = json.hasMore == true && items.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query, 1).items.take(10)

    // ---- Detail ----
    override suspend fun load(url: String): LoadResponse {
        val parsed = parseDramaUrl(url) ?: throw ErrorLoadingException("Unrecognized url: $url")
        val (provider, id) = parsed

        val detail = app.get(detailApiUrl(provider, id), headers = cfHeaders(api()), timeout = 30L, cacheTime = 30)
            .parsedSafe<DetailResponse>() ?: throw ErrorLoadingException("Failed to load details")

        val title = detail.title?.takeIf { it.isNotBlank() } ?: "Unknown"
        val total = detail.episodes ?: 1
        val tag = listOf(PROVIDER_NAMES[provider] ?: provider)

        if (total <= 1) {
            return newMovieLoadResponse(title, url, TvType.Movie, videoApiUrl(provider, id, 1)) {
                this.plot = detail.intro
                this.posterUrl = detail.cover
                this.tags = tag
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, (1..total).map { num ->
            newEpisode(videoApiUrl(provider, id, num)) {
                this.name = "Episode $num"
                this.episode = num
                this.data = videoApiUrl(provider, id, num)
            }
        }) {
            this.plot = detail.intro
            this.posterUrl = detail.cover
            this.tags = tag
        }
    }

    // ---- Links ----
    private fun qualityFrom(label: String?, url: String): Int {
        getQualityFromName(label).takeIf { it != Qualities.Unknown.value }?.let { return it }
        val u = url.lowercase()
        return when {
            u.contains(".m3u8") -> Qualities.Unknown.value
            u.contains("2160") || u.contains("4k") -> Qualities.P2160.value
            u.contains("1080") -> Qualities.P1080.value
            u.contains("720") -> Qualities.P720.value
            u.contains("480") -> Qualities.P480.value
            else -> Qualities.P720.value // CDN mp4 default
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val res = app.get(data, headers = cfHeaders(api()), referer = "${api()}/", timeout = 30L, cacheTime = 0)
        val parsed = runCatching { res.parsedSafe<VideoResponse>() }.getOrNull()
            ?: return false
        if (parsed.locked == true) return false

        val sources = parsed.qualityList.orEmpty().filter { !it.url.isNullOrBlank() }
            .ifEmpty { listOfNotNull(parsed.videoUrl?.let { QualityEntry("Auto", it) }) }
        if (sources.isEmpty()) return false

        for (q in sources) {
            val link = q.url!!
            callback(newExtractorLink(name, q.label ?: name, link) {
                this.referer = "${api()}/"
                this.quality = qualityFrom(q.label, link)
                this.type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            })
        }
        return true
    }
}
