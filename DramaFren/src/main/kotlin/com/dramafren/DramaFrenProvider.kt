package com.dramafren

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

@Suppress("MemberVisibilityCanBePrivate")
open class DramaFrenProvider : MainAPI() {
    override var name = "DramaFren"
    override var mainUrl = DEFAULT_BASE_URL
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

    private fun listingUrl(slug: String, page: Int): String =
        "$mainUrl/$slug" + if (page > 1) "?page=$page" else ""

    private fun listingApiUrl(slug: String, page: Int): String =
        "$mainUrl/api/$slug/home?page=$page"

    private fun populerApiUrl(slug: String, page: Int): String =
        "$mainUrl/api/$slug/populer?page=$page"

    private fun newApiUrl(slug: String, page: Int): String =
        "$mainUrl/api/$slug/new?page=$page"

    private fun searchUrl(query: String): String =
        "$mainUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"

    private fun searchApiUrl(slug: String, query: String, page: Int): String =
        "$mainUrl/api/$slug/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page"

    private fun detailApiUrl(slug: String, id: String): String =
        "$mainUrl/api/$slug/detail?bookId=${java.net.URLEncoder.encode(id, "UTF-8")}"

    private fun detailApiUrlByDramaId(slug: String, id: String): String =
        "$mainUrl/api/$slug/detail?dramaId=${java.net.URLEncoder.encode(id, "UTF-8")}"

    private fun streamApiUrl(slug: String, id: String, episode: String): String =
        "$mainUrl/api/$slug/stream?bookId=${java.net.URLEncoder.encode(id, "UTF-8")}&chapterId=${java.net.URLEncoder.encode(episode, "UTF-8")}"

    private fun getCfHeaders(url: String): Map<String, String> {
        val domain = try { java.net.URL(url).host } catch (_: Exception) { mainUrl }
        val cookie = DramaFrenStore.loadCfCookie(domain) ?: DramaFrenStore.loadCfCookie(mainUrl) ?: return browserHeaders()
        return browserHeaders() + mapOf("Cookie" to cookie)
    }

    private val lastFirstCard = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val slug = request.data
        val combined = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        val apiUrls = if (page == 1) {
            listOf(listingApiUrl(slug, 1), populerApiUrl(slug, 1), newApiUrl(slug, 1))
        } else {
            listOf(listingApiUrl(slug, page))
        }
        for (u in apiUrls) {
            try {
                val cards = fetchApiCards(u, slug)
                for (c in cards) if (seen.add(c.url)) combined += c
            } catch (_: Exception) {}
        }
        if (combined.isNotEmpty()) {
            val hasNext = combined.size >= 10
            return newHomePageResponse(request, combined, hasNext = hasNext)
        }
        val cards = fetchHtmlCards(listingUrl(slug, page))
        if (cards.isEmpty()) return newHomePageResponse(request, emptyList(), hasNext = false)
        val first = cards.first().url
        val prev = lastFirstCard.put(slug, first)
        if (prev == first && page > 1) return newHomePageResponse(request, emptyList(), hasNext = false)
        return newHomePageResponse(request, cards, hasNext = true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1 || query.isBlank()) return newSearchResponseList(emptyList(), false)
        val combined = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for ((_, slug) in DRAMAFREN_CATALOG.take(5)) {
            try {
                val cards = fetchApiCards(searchApiUrl(slug, query, 1), slug)
                for (c in cards) if (seen.add(c.url)) combined += c
            } catch (_: Exception) {}
        }
        if (combined.isNotEmpty()) return newSearchResponseList(combined, false)
        val cards = fetchHtmlCards(searchUrl(query))
        return newSearchResponseList(cards, false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query, 1).items.take(10)

    private suspend fun fetchApiCards(url: String, slug: String): List<SearchResponse> {
        val res = app.get(url, headers = getCfHeaders(url), referer = "$mainUrl/", timeout = 30L, cacheTime = 10)
        if (isCloudflareChallenge(res.text, res.code)) throw ErrorLoadingException("Cloudflare challenge")
        val parsed = res.parsedSafe<ApiListResponse>() ?: return emptyList()
        val books = parsed.data?.firstOrNull()?.books ?: parsed.books ?: emptyList()
        return books.mapNotNull { b ->
            val title = b.dramaName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val id = b.dramaId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = b.thumbUrl
            val link = "$mainUrl/$slug/$id"
            newMovieSearchResponse(title, link, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
    }

    private suspend fun fetchHtmlCards(url: String): List<MovieSearchResponse> {
        val res = app.get(url, headers = getCfHeaders(url), referer = "$mainUrl/", timeout = 30L, cacheTime = 10)
        if (isCloudflareChallenge(res.text, res.code)) throw ErrorLoadingException("Cloudflare challenge at $url")
        val html = res.text
        val doc = Jsoup.parse(html, mainUrl)
        val out = ArrayList<MovieSearchResponse>(64)
        val seen = HashSet<String>(64)
        val selectors = listOf(
            "a[href*=/drama/]", "a[href*=/watch/]", "a[href*=/goodshort/]", "a[href*=/dramafren/]",
            ".card a", ".drama-card a", ".item a", "ul a[href*=/]"
        )
        val anchors = selectors.flatMap { doc.select(it) }.distinctBy { it.attr("href") }
        for (a in anchors) {
            val href = a.attr("href").trim()
            if (href.isEmpty() || href == "/" || href == "#") continue
            val img = a.selectFirst("img") ?: continue
            val title = img.attr("alt").ifBlank { img.attr("title") }.ifBlank { a.attr("title") }.ifBlank { a.text() }.trim()
            val poster = img.attr("src").ifBlank { img.attr("data-src") }.trim()
            if (title.isEmpty() || poster.isEmpty()) continue
            if (!seen.add(href)) continue
            val absUrl = a.absUrl("href")
            out += newMovieSearchResponse(title, absUrl, TvType.AsianDrama) {
                this.posterUrl = poster
            }
            if (out.size >= 60) break
        }
        if (out.isEmpty()) {
            for (a in doc.select("a[href]")) {
                val href = a.attr("href").trim()
                if (!href.startsWith("/") && !href.startsWith("http")) continue
                val img = a.selectFirst("img") ?: continue
                val title = img.attr("alt").trim().ifEmpty { a.text().trim() }
                val poster = img.attr("src").trim()
                if (title.length < 2 || poster.isEmpty()) continue
                if (!seen.add(href)) continue
                out += newMovieSearchResponse(title, a.absUrl("href"), TvType.AsianDrama) {
                    this.posterUrl = poster
                }
                if (out.size >= 30) break
            }
        }
        return out
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiListResponse(
        val author: String? = null,
        val message: String? = null,
        val type: String? = null,
        val data: List<BookContainer>? = null,
        val books: List<Book>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BookContainer(val books: List<Book>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Book(
        @com.fasterxml.jackson.annotation.JsonProperty("drama_name") val dramaName: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("drama_id") val dramaId: String? = null,
        val description: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("thumb_url") val thumbUrl: String? = null,
        val cover: String? = null,
        val tags: List<String>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DetailResponse(
        val success: Boolean? = null,
        val author: String? = null,
        val message: String? = null,
        val data: DetailData? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("drama_id") val dramaId: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("drama_name") val dramaName: String? = null,
        val description: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("thumb_url") val thumbUrl: String? = null,
        val cover: String? = null,
        val tags: List<String>? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("video_list") val videoList: List<EpisodeJson>? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("episode_list") val episodeList: List<EpisodeJson>? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("episodeList") val episodeListAlt: List<EpisodeJson>? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("chapterList") val chapterList: List<EpisodeJson>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DetailData(
        @com.fasterxml.jackson.annotation.JsonProperty("drama_id") val dramaId: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("drama_name") val dramaName: String? = null,
        val description: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("episode_count") val episodeCount: Int? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("video_list") val videoList: List<EpisodeJson>? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("episode_list") val episodeList: List<EpisodeJson>? = null,
        val tags: List<String>? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("thumb_url") val thumbUrl: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeJson(
        val episode: Int? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("episode_id") val episodeId: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("video_id") val videoId: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("chapterId") val chapterId: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("chapterIndex") val chapterIndex: Int? = null,
        val title: String? = null,
        val name: String? = null,
        val cover: String? = null,
        val thumbnail: String? = null,
        val isLocked: Boolean? = null,
        val locked: Boolean? = null,
    )

    override suspend fun load(url: String): LoadResponse {
        val (slug, id) = parseDramaUrl(url) ?: run {
            val path = try { java.net.URL(url).path.trim('/').split("/").filter { it.isNotBlank() } } catch (_: Exception) { emptyList() }
            val guessedSlug = path.firstOrNull()?.lowercase() ?: "goodshort"
            val guessedId = path.lastOrNull() ?: throw ErrorLoadingException("Unparseable url: $url")
            guessedSlug to guessedId
        }
        val detailUrls = listOf(
            detailApiUrl(slug, id),
            detailApiUrlByDramaId(slug, id),
            "$mainUrl/api/$slug/detail/$id",
            "$mainUrl/api/$slug/detail?bookId=$id",
            "$mainUrl/api/$slug/detail?id=$id",
        )
        var detail: DetailResponse? = null
        var detailData: DetailData? = null
        for (u in detailUrls) {
            try {
                val res = app.get(u, headers = getCfHeaders(u), referer = "$mainUrl/", timeout = 30L, cacheTime = 30)
                if (isCloudflareChallenge(res.text, res.code)) continue
                val parsed = res.parsedSafe<DetailResponse>() ?: continue
                val hasData = parsed.data != null || parsed.dramaName != null || parsed.dramaId != null
                if (hasData || parsed.data?.dramaName != null) {
                    detail = parsed
                    detailData = parsed.data
                    break
                }
                if (parsed.dramaName != null) {
                    detail = parsed
                    break
                }
            } catch (_: Exception) {}
        }
        if (detail == null && detailData == null) {
            try {
                val html = app.get(url, headers = getCfHeaders(url), referer = "$mainUrl/", timeout = 30L).text
                val doc = Jsoup.parse(html, mainUrl)
                val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
                val desc = doc.selectFirst("meta[name=description]")?.attr("content") ?: doc.selectFirst(".description, .synopsis, p")?.text()
                val poster = doc.selectFirst("img[src]")?.attr("src")
                return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.plot = desc
                    this.posterUrl = poster
                }
            } catch (e: Exception) {
                throw ErrorLoadingException("Failed to load drama details")
            }
        }
        val title = detailData?.dramaName ?: detail?.dramaName ?: detail?.data?.dramaName ?: "Unknown"
        val desc = detailData?.description ?: detail?.description
        val poster = detailData?.thumbUrl ?: detail?.thumbUrl ?: detail?.cover
        val tags = detailData?.tags ?: detail?.tags ?: emptyList()
        val episodes = detailData?.videoList ?: detailData?.episodeList
            ?: detail?.videoList ?: detail?.episodeList ?: detail?.episodeListAlt ?: detail?.chapterList
            ?: emptyList()
        if (episodes.isEmpty()) throw ErrorLoadingException("No episodes found for $title")
        if (episodes.size == 1) {
            val ep = episodes.first()
            val epId = ep.episodeId ?: ep.videoId ?: ep.chapterId ?: id
            return newMovieLoadResponse(title, url, TvType.Movie, streamApiUrl(slug, id, epId)) {
                this.plot = desc
                this.posterUrl = poster
                this.tags = tags
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes.mapIndexed { idx, ep -> ep.toEpisode(slug, id, idx) }) {
            this.plot = desc
            this.posterUrl = poster
            this.tags = tags
        }
    }

    private fun EpisodeJson.toEpisode(slug: String, dramaId: String, index: Int): Episode {
        val num = episode ?: chapterIndex ?: index + 1
        val epTitle = title ?: name ?: "Episode $num"
        val thumb = cover ?: thumbnail
        val isLocked = isLocked == true || locked == true
        val epId = episodeId ?: videoId ?: chapterId ?: "$num"
        return newEpisode(streamApiUrl(slug, dramaId, epId)) {
            this.name = epTitle
            this.episode = num
            this.posterUrl = thumb
            if (isLocked) this.description = "Locked"
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamResponse(
        val success: Boolean? = null,
        val author: String? = null,
        val message: String? = null,
        val type: String? = null,
        val data: StreamData? = null,
        val url: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("video_url") val videoUrl: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("playUrl") val playUrl: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("m3u8_url") val m3u8Url: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("h264_m3u8") val h264M3u8: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("h265_m3u8") val h265M3u8: String? = null,
        val qualities: List<StreamQuality>? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("videoList") val videoList: List<StreamQuality>? = null,
        val subtitles: List<SubtitleJson>? = null,
        val locked: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamData(
        val url: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("video_url") val videoUrl: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("playUrl") val playUrl: String? = null,
        val qualities: List<StreamQuality>? = null,
        val subtitles: List<SubtitleJson>? = null,
        val duration: Double? = null,
        val poster: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamQuality(
        val label: String? = null,
        val quality: Int? = null,
        val dpi: Int? = null,
        val width: Int? = null,
        val height: Int? = null,
        val url: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("videoPath") val videoPath: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("playUrl") val playUrl: String? = null,
        val bitrate: Int? = null,
        val codec: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SubtitleJson(
        val language: String? = null,
        val label: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("display_name") val displayName: String? = null,
        val url: String? = null,
        val type: String? = null,
    )

    private fun qualityFrom(label: String?, q: StreamQuality): Int {
        getQualityFromName(label)?.takeIf { it != Qualities.Unknown.value }?.let { return it }
        q.quality?.let { return it }
        q.dpi?.let { return it }
        q.width?.let {
            return when {
                it >= 2160 -> Qualities.P2160.value
                it >= 1080 -> Qualities.P1080.value
                it >= 720 -> Qualities.P720.value
                it >= 480 -> Qualities.P480.value
                else -> Qualities.P360.value
            }
        }
        val u = (q.url ?: q.videoPath ?: q.playUrl ?: "").lowercase()
        return when {
            u.contains("2160") || u.contains("4k") -> Qualities.P2160.value
            u.contains("1080") -> Qualities.P1080.value
            u.contains("720") -> Qualities.P720.value
            u.contains("480") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun linkType(url: String, apiType: String?): ExtractorLinkType = when {
        apiType.equals("hls", ignoreCase = true) || url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val res = app.get(data, headers = getCfHeaders(data), referer = "$mainUrl/", timeout = 30L, cacheTime = 0)
        if (isCloudflareChallenge(res.text, res.code)) throw ErrorLoadingException("Cloudflare challenge — solve via Settings")
        val parsed = res.parsedSafe<StreamResponse>() ?: return false
        if (parsed.locked == true) return false
        val subs = parsed.subtitles ?: parsed.data?.subtitles ?: emptyList()
        for (sub in subs) {
            val subUrl = sub.url?.takeIf { it.startsWith("http") } ?: continue
            val lang = sub.displayName ?: sub.label ?: sub.language ?: "en"
            subtitleCallback(newSubtitleFile(lang, subUrl))
        }
        val streamSubs = parsed.data?.subtitles ?: emptyList()
        for (sub in streamSubs) {
            val subUrl = sub.url?.takeIf { it.startsWith("http") } ?: continue
            if (subs.any { it.url == subUrl }) continue
            subtitleCallback(newSubtitleFile(sub.displayName ?: sub.label ?: "en", subUrl))
        }
        val qualities = mutableListOf<StreamQuality>()
        parsed.qualities?.let { qualities += it }
        parsed.videoList?.let { qualities += it }
        parsed.data?.qualities?.let { qualities += it }
        parsed.m3u8Url?.let { qualities += StreamQuality(label = "Auto", url = it) }
        parsed.h264M3u8?.let { qualities += StreamQuality(label = "H264", url = it) }
        parsed.h265M3u8?.let { qualities += StreamQuality(label = "H265", url = it) }
        parsed.videoUrl?.let { qualities += StreamQuality(label = "HD", url = it) }
        parsed.playUrl?.let { qualities += StreamQuality(label = "HD", url = it) }
        parsed.url?.let { qualities += StreamQuality(label = "HD", url = it) }
        parsed.data?.url?.let { qualities += StreamQuality(label = "HD", url = it) }
        parsed.data?.videoUrl?.let { qualities += StreamQuality(label = "HD", url = it) }
        parsed.data?.playUrl?.let { qualities += StreamQuality(label = "HD", url = it) }
        val unique = qualities.filter { !it.url.isNullOrBlank() || !it.videoPath.isNullOrBlank() || !it.playUrl.isNullOrBlank() }
            .distinctBy { it.url ?: it.videoPath ?: it.playUrl }
        if (unique.isEmpty()) return false
        for (q in unique) {
            val link = q.url ?: q.videoPath ?: q.playUrl ?: continue
            if (!link.startsWith("http")) continue
            val label = q.label ?: q.quality?.toString() ?: "${q.width ?: ""}p".takeIf { it != "p" } ?: "Auto"
            callback(
                newExtractorLink(name, label, link) {
                    this.referer = "$mainUrl/"
                    this.quality = qualityFrom(label, q)
                    this.type = linkType(link, parsed.type)
                    this.headers = mapOf("Referer" to "$mainUrl/")
                }
            )
        }
        return true
    }
}
