package com.vdrama

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

/**
 * One provider, twenty home categories — one per short-drama app.
 *
 * Data flow (all verified against v-drama.net):
 *   listing  GET /en/app/<slug>?page=N        HTML card grid, ~60 per page
 *   search   GET /en/?q=<query>               all matches on one page
 *   detail   GET /api/detail?provider&id&lang JSON with episodeList
 *   stream   GET /api/stream?provider&dramaId&episodeId&lang JSON media
 */
@Suppress("MemberVisibilityCanBePrivate")
open class VDramaProvider : MainAPI() {
    override var name = "VDrama"
    override var mainUrl = DEFAULT_BASE_URL
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    // 20 categories hit the same host; load them one-by-one instead of bursting.
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L

    /** Endless scroll: CloudStream calls this again with page+1 while hasNext. */
    override val mainPage = mainPageOf(
        *VDRAMA_CATALOG.map { (title, slug) -> slug to title }.toTypedArray()
    )

    // ---------------------------------------------------------------------
    // Url builders — always derive from mainUrl so a domain change applies
    // immediately (plugin settings dialog and CloudStream's clone-site both
    // write to mainUrl).
    // ---------------------------------------------------------------------

    private fun listingUrl(slug: String, page: Int): String =
        "$mainUrl/en/app/$slug" + if (page > 1) "?page=$page" else ""

    private fun searchUrl(query: String): String =
        "$mainUrl/en/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"

    private fun detailApiUrl(provider: String, id: String): String =
        "$mainUrl/api/detail?provider=${java.net.URLEncoder.encode(provider, "UTF-8")}" +
            "&id=${java.net.URLEncoder.encode(id, "UTF-8")}&lang=en-US"

    private fun streamApiUrl(provider: String, dramaId: String, episodeId: String): String =
        "$mainUrl/api/stream?provider=${java.net.URLEncoder.encode(provider, "UTF-8")}" +
            "&dramaId=${java.net.URLEncoder.encode(dramaId, "UTF-8")}" +
            "&episodeId=${java.net.URLEncoder.encode(episodeId, "UTF-8")}&lang=en-US"

    // ---------------------------------------------------------------------
    // Home page
    // ---------------------------------------------------------------------

    /** Last first-card url seen per category; detects sites that clamp paging. */
    private val lastFirstCard = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cards = fetchCards(listingUrl(request.data, page))
        if (cards.isEmpty()) {
            return newHomePageResponse(request, emptyList(), hasNext = false)
        }
        // Site keeps serving the final page for any higher page number -> stop
        // scrolling instead of appending the same items forever.
        val first = cards.first().url
        val previous = lastFirstCard.put(request.data, first)
        if (previous == first && page > 1) {
            return newHomePageResponse(request, emptyList(), hasNext = false)
        }
        return newHomePageResponse(request, cards, hasNext = true)
    }

    // ---------------------------------------------------------------------
    // Search (site returns every match on one page)
    // ---------------------------------------------------------------------

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1 || query.isBlank()) {
            return newSearchResponseList(emptyList(), false)
        }
        val cards = fetchCards(searchUrl(query))
        return newSearchResponseList(cards, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query, 1).items.take(10)

    // ---------------------------------------------------------------------
    // Card parsing (shared by home + search)
    // ---------------------------------------------------------------------

    /**
     * Card markup:
     * <ul class="card-grid"><li class="card"><a href="/en/drama/...">
     *   <img class="card-img" src="<poster>" alt="<title>">
     */
    private suspend fun fetchCards(url: String): List<com.lagradost.cloudstream3.MovieSearchResponse> {
        val html = app.get(
            url,
            headers = browserHeaders(),
            referer = "$mainUrl/",
            timeout = 30L,
            cacheTime = 10, // minutes; rides OkHttp's 50MiB disk cache
        ).text

        val doc = Jsoup.parse(html, mainUrl)
        val out = ArrayList<com.lagradost.cloudstream3.MovieSearchResponse>(64)
        val seen = HashSet<String>(64)
        for (a in doc.select("ul.card-grid a[href*=/en/drama/]")) {
            val img = a.selectFirst("img.card-img") ?: continue
            val title = img.attr("alt").trim()
            val poster = img.attr("src").trim()
            val href = a.attr("href").trim()
            if (title.isEmpty() || href.isEmpty() || poster.isEmpty()) continue
            if (!seen.add(href)) continue
            out += newMovieSearchResponse(title, a.absUrl("href"), TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
        return out
    }

    // ---------------------------------------------------------------------
    // Detail page -> episodes
    // ---------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeJson(
        val episode: Int? = null,
        val episodeId: String? = null,
        val title: String? = null,
        val thumbnail: String? = null,
        val locked: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DetailJson(
        val success: Boolean? = null,
        val id: String? = null,
        val title: String? = null,
        val image: String? = null,
        val description: String? = null,
        val episodes: Int? = null,
        val episodeList: List<EpisodeJson>? = null,
        val error: String? = null,
    )

    override suspend fun load(url: String): LoadResponse {
        val (provider, id) = parseDramaUrl(url)
            ?: throw ErrorLoadingException("Unrecognized drama url: $url")

        val detail = app.get(
            detailApiUrl(provider, id),
            headers = browserHeaders(),
            referer = "$mainUrl/",
            timeout = 30L,
            cacheTime = 30,
        ).parsedSafe<DetailJson>() ?: throw ErrorLoadingException("Failed to load drama details")

        if (detail.success != true) {
            throw ErrorLoadingException(detail.error ?: "Drama details unavailable")
        }

        val title = detail.title?.takeIf { it.isNotBlank() } ?: "Unknown"
        val eps = detail.episodeList.orEmpty().filter { !it.episodeId.isNullOrBlank() }
        val tag = listOf(APP_NAMES[provider] ?: provider)

        // Single-episode entries are movies; everything else is a series.
        if (eps.size <= 1 && detail.episodes == 1) {
            val only = eps.firstOrNull()
                ?: throw ErrorLoadingException("No episodes found for $title")
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                streamApiUrl(provider, id, only.episodeId!!),
            ) {
                this.plot = detail.description
                this.posterUrl = detail.image
                this.tags = tag
            }
        }

        if (eps.isEmpty()) throw ErrorLoadingException("No episodes found for $title")

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.AsianDrama,
            eps.mapIndexed { index, ep -> ep.toEpisode(provider, id, index) },
        ) {
            this.plot = detail.description
            this.posterUrl = detail.image
            this.tags = tag
        }
    }

    private fun EpisodeJson.toEpisode(provider: String, dramaId: String, index: Int): Episode {
        // Capture json fields first: inside the builder lambda the receiver is
        // cloudstream's Episode, whose members would shadow these names.
        val num = episode ?: index + 1
        val name = title?.takeIf { it.isNotBlank() } ?: "Episode $num"
        val thumb = thumbnail
        val isLocked = locked == true
        return newEpisode(streamApiUrl(provider, dramaId, episodeId!!)) {
            this.name = name
            this.episode = num
            this.posterUrl = thumb
            if (isLocked) this.description = "Locked"
        }
    }

    // ---------------------------------------------------------------------
    // Streams + subtitles
    // ---------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class QualityJson(val label: String? = null, val url: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SubtitleJson(
        val label: String? = null,
        val language: String? = null,
        val url: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamJson(
        val success: Boolean? = null,
        val url: String? = null,
        val type: String? = null,
        val qualities: List<QualityJson>? = null,
        val subtitles: List<SubtitleJson>? = null,
        val locked: Boolean? = null,
        val error: String? = null,
    )

    /** Label ("1080p", "4K") first, then hints in the url (-fhd/-hd/-sd). */
    private fun qualityFrom(label: String?, url: String): Int {
        getQualityFromName(label).takeIf { it != Qualities.Unknown.value }?.let { return it }
        val u = url.lowercase()
        return when {
            u.contains("2160") || u.contains("4k") || u.contains("uhd") -> Qualities.P2160.value
            u.contains("1080") || u.contains("fhd") -> Qualities.P1080.value
            u.contains("720") || u.endsWith("-hd.m3u8") -> Qualities.P720.value
            u.contains("480") || u.contains("/sd.") || u.endsWith("-sd.m3u8") -> Qualities.P480.value
            u.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun linkType(url: String, apiType: String?): ExtractorLinkType = when {
        apiType.equals("hls", ignoreCase = true) ||
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
        // Stream links expire -> never serve them from cache.
        val res = app.get(
            data,
            headers = browserHeaders(),
            referer = "$mainUrl/",
            timeout = 30L,
            cacheTime = 0,
        ).parsedSafe<StreamJson>()

        if (res?.success != true || res.locked == true) return false

        for (sub in res.subtitles.orEmpty()) {
            val subUrl = sub.url?.takeIf { it.startsWith("http") } ?: continue
            subtitleCallback(newSubtitleFile(sub.label ?: sub.language ?: "en", subUrl))
        }

        val qualities = res.qualities.orEmpty().filter { !it.url.isNullOrBlank() }
        val sources = if (qualities.isNotEmpty()) qualities
        else listOfNotNull(res.url?.takeIf { it.startsWith("http") }?.let { QualityJson(null, it) })
        if (sources.isEmpty()) return false

        for (q in sources) {
            val link = q.url!!
            callback(
                newExtractorLink(name, name, link) {
                    this.referer = "$mainUrl/"
                    this.quality = qualityFrom(q.label, link)
                    this.type = linkType(link, res.type)
                }
            )
        }
        return true
    }
}
