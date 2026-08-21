package com.chartdrama

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class ChartDramaProvider : MainAPI() {
    override var name = "ChartDrama"
    override var mainUrl = DEFAULT_BASE_URL
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L

    override val mainPage = mainPageOf(
        *CHART_CATALOG.map { (title, slug) -> slug to title }.toTypedArray()
    )

    private fun baseHost(): String {
        val b = ChartStore.activeBase()
        return normalizeBaseUrl(b) ?: DEFAULT_BASE_URL
    }

    private fun subdomain(slug: String): String = subdomainBase(baseHost(), slug)

    private fun headersFor(url: String): Map<String, String> {
        val baseHeaders = browserHeaders()
        val cf = ChartStore.cfHeadersFor(url)
        return if (cf.isEmpty()) baseHeaders else baseHeaders + cf
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val slug = request.data
        val id = SLUG_TO_ID[slug] ?: return newHomePageResponse(request, emptyList(), false)
        val sub = subdomain(slug)
        // Use search with a common letter "a" to get a large listing for this source, paginated
        val url = "$sub/api/series?q=a&page=$page&limit=12&source=$id"
        val items = fetchApiList(url, slug)
        return newHomePageResponse(request, items, hasNext = items.size >= 12)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        // Global search across all sources via main domain
        val base = baseHost()
        val url = "$base/api/series?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page&limit=12"
        val items = fetchApiList(url, null)
        return newSearchResponseList(items, hasNext = items.size >= 12)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items.take(10)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiItem(
        val slug: String? = null,
        val dramaId: Int? = null,
        val title: String? = null,
        val cover: String? = null,
        val latestEpisodeLabel: String? = null,
        val source: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiListResp(
        val items: List<ApiItem>? = null,
        val error: String? = null,
    )

    private suspend fun fetchApiList(url: String, expectedSlug: String?): List<MovieSearchResponse> {
        val resp = app.get(url, headers = headersFor(url), referer = baseHost() + "/", timeout = 30L, cacheTime = 10)
        // Cloudflare check
        if (resp.code == 403 || resp.text.contains("Just a moment") || resp.text.contains("cf-challenge")) {
            // Try with saved cookies already injected via headersFor, if still blocked, return empty and let user solve via settings
            if (resp.code == 403) return emptyList()
        }
        val data = resp.parsedSafe<ApiListResp>() ?: return emptyList()
        if (data.error != null) return emptyList()
        return data.items.orEmpty().mapNotNull { item ->
            val slug = item.slug ?: return@mapNotNull null
            val title = item.title ?: return@mapNotNull null
            val dramaId = item.dramaId ?: return@mapNotNull null
            val sourceSlug = expectedSlug ?: CHART_CATALOG.find { SLUG_TO_ID[it.second] == item.source }?.second ?: expectedSlug ?: "reelshort"
            val sub = subdomain(sourceSlug)
            val poster = item.cover?.takeIf { it.startsWith("http") }
            // Build URL that encodes slug + dramaId + source for load()
            val dramaUrl = "$sub/d/$slug?dramaId=$dramaId&source=$sourceSlug"
            newMovieSearchResponse(title, dramaUrl, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WatchResp(
        val dramaId: String? = null,
        val title: String? = null,
        val cover: String? = null,
        val synopsis: String? = null,
        val source: Int? = null,
        val slug: String? = null,
        val embedUrl: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResp(
        val dramaID: String? = null,
        val items: List<EpisodeItem>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        val ep: Int? = null,
        val url: String? = null,
    )

    override suspend fun load(url: String): LoadResponse {
        // url like https://reelshort.chartdrama.com/d/6a469b12d3f5c65f7f095b8a/you-ve-been-replaced-first-love?dramaId=77246&source=reelshort
        val parsed = parseChartUrl(url) ?: throw ErrorLoadingException("Invalid drama url: $url")
        val slug = parsed.first
        val dramaIdFromUrl = parsed.second
        val query = url.substringAfter('?', "")
        val params = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val sourceSlug = params["source"] ?: run {
            // Try to infer from subdomain
            try { java.net.URL(url).host.substringBefore('.') } catch (_: Exception) { "reelshort" }
        }
        val dramaId = dramaIdFromUrl.takeIf { it.isNotEmpty() } ?: params["dramaId"] ?: throw ErrorLoadingException("Missing dramaId")
        val sub = subdomain(sourceSlug)

        // Fetch watch for metadata
        val watchUrl = "$sub/api/watch/$slug"
        val watch = app.get(watchUrl, headers = headersFor(watchUrl), referer = baseHost() + "/", timeout = 30L, cacheTime = 30).parsedSafe<WatchResp>()

        val title = watch?.title ?: "Unknown"
        val poster = watch?.cover?.takeIf { it.startsWith("http") }
        val plot = watch?.synopsis

        // Fetch episodes
        val epUrl = "$sub/api/drama/$dramaId/episodes"
        val epResp = app.get(epUrl, headers = headersFor(epUrl), referer = baseHost() + "/", timeout = 30L, cacheTime = 30).parsedSafe<EpisodesResp>()
        val episodes = epResp?.items.orEmpty().filter { !it.url.isNullOrBlank() }

        if (episodes.isEmpty()) {
            // Fallback: single movie via watch embedUrl
            val singleUrl = watch?.embedUrl?.takeIf { it.startsWith("http") } ?: throw ErrorLoadingException("No episodes found")
            return newMovieLoadResponse(title, url, TvType.Movie, singleUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val eps = episodes.mapIndexed { idx, ep ->
            val epNum = ep.ep ?: idx + 1
            val epUrl = ep.url!!
            newEpisode(epUrl) {
                this.name = "Episode $epNum"
                this.episode = epNum
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, eps) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun linkType(url: String): ExtractorLinkType = when {
        url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data is direct m3u8 url from episodes API
        if (!data.startsWith("http")) return false
        callback(
            newExtractorLink(name, name, data) {
                this.referer = baseHost() + "/"
                this.quality = Qualities.Unknown.value
                this.type = linkType(data)
            }
        )
        return true
    }
}
