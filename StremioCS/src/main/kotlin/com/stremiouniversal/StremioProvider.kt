package com.stremiouniversal

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class StremioProvider(private val repository: StremioRepository) : MainAPI() {
    override var mainUrl = ""
    override var name = "StremioCS"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)

    override val mainPage = mainPageOf("Stremio" to "stremio")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = resultOr(emptyList()) { repository.catalogRows(page) }
        return newHomePageResponse(
            rows.map { row ->
                HomePageList(
                    row.title,
                    row.items.mapNotNull { it.toSearchResponse() }
                )
            }.filter { it.list.isNotEmpty() },
            hasNext = rows.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        resultOr(null) { search(query) }

    override suspend fun search(query: String): List<SearchResponse> =
        resultOr(emptyList()) { repository.searchAll(query) }
            .mapNotNull { it.toSearchResponse() }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseLinkRef(url) ?: return null
        val details = resultOr(null) { repository.metaDetails(ref) }
            ?: MetaDetails(ref.id, ref.type, ref.id, null, null, "", null, null, emptyList(), emptyList(), emptyList(), emptyList())
        val payload = LinkRef(ref.base, details.type.ifEmpty { ref.type }, details.id).toJsonString()
        val hasEpisodes = details.videos.isNotEmpty()
        val screenType = if (hasEpisodes) TvType.TvSeries else contentTypeOf(details.type)
        if (!hasEpisodes) {
            return newMovieLoadResponse(details.name, payload, screenType, payload) {
                posterUrl = details.poster
                backgroundPosterUrl = details.background
                plot = details.description
                year = details.year
                tags = details.genres.takeIf { it.isNotEmpty() }
                score = Score.from10(details.rating?.toString())
                addActors(details.cast)
                details.id.takeIf { it.matches(Regex("^tt\\d+$")) }?.let { addImdbId(it) }
                details.trailerYoutubeIds.firstOrNull()?.let { addTrailer("https://www.youtube.com/watch?v=$it") }
            }
        }
        val episodes = details.videos.map { video ->
            newEpisode(LinkRef(ref.base, details.type.ifEmpty { ref.type }, video.id).toJsonString()) {
                name = video.title
                season = video.season
                episode = video.episode
                posterUrl = video.thumbnail ?: details.poster
                description = video.overview
            }
        }
        return newTvSeriesLoadResponse(details.name, payload, screenType, episodes) {
            posterUrl = details.poster
            backgroundPosterUrl = details.background
            plot = details.description
            year = details.year
            tags = details.genres.takeIf { it.isNotEmpty() }
            score = Score.from10(details.rating?.toString())
            addActors(details.cast)
            details.id.takeIf { it.matches(Regex("^tt\\d+$")) }?.let { addImdbId(it) }
            details.trailerYoutubeIds.firstOrNull()?.let { addTrailer("https://www.youtube.com/watch?v=$it") }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = parseLinkRef(data) ?: return false
        val result = resultOr(null) { repository.streamsFor(ref) } ?: return false
        result.links.forEach { link ->
            callback(
                newExtractorLink(link.source, link.title, link.url, INFER_TYPE) {
                    quality = qualityValue(link.qualityTag)
                    headers = link.headers
                }
            )
        }
        result.youtubeIds.forEach { ytId ->
            resultOr(Unit) { loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback) }
        }
        val remote = resultOr(emptyList()) { repository.subtitlesFor(ref) }
        (remote + result.inlineSubtitles).distinctBy { it.url }.forEach { sub ->
            subtitleCallback(
                newSubtitleFile(
                    SubtitleHelper.fromTagToEnglishLanguageName(sub.lang) ?: sub.lang,
                    sub.url
                )
            )
        }
        return true
    }

    private fun MetaRef.toSearchResponse(): SearchResponse? {
        if (id.isEmpty() || name.isEmpty()) return null
        return newMovieSearchResponse(name, LinkRef(base, type, id).toJsonString(), contentTypeOf(type)) {
            posterUrl = poster
        }
    }

    private fun contentTypeOf(type: String): TvType = when (type.lowercase()) {
        "movie", "short" -> TvType.Movie
        "series", "anime" -> TvType.TvSeries
        else -> TvType.Movie
    }
}
