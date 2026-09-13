package com.stremiouniversal

import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.CancellationException

suspend fun <T> resultOr(default: T, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    default
}

const val MAX_STREAMS = 180
const val MAX_SUBTITLES = 12
const val MAX_SEARCH_RESULTS = 60
const val MAX_ITEMS_PER_ROW = 40
const val MAX_CATALOGS_PER_ADDON = 30
const val FILTER_CATALOGS_PER_ADDON = 6
const val NATIVE_SEARCH_MIN = 20

private val LIVE_TYPES = setOf("tv", "channel", "livestream", "live", "iptv")

private val FALLBACK_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://tracker.dler.org:6969/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.moeking.me:6969/announce",
    "http://tracker.openbittorrent.com:80/announce"
)

fun manifestBase(manifestUrl: String): String {
    var base = manifestUrl.trim().replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
    base = base.substringBefore("?")
    base = base.replace(Regex("/(manifest\\.json.*|streams?|catalogs?)/?$"), "").trimEnd('/')
    return base
}

fun manifestQuery(manifestUrl: String): String =
    if (manifestUrl.contains("?")) "?" + manifestUrl.substringAfter("?") else ""

fun withQuery(url: String, suffix: String): String = url + suffix

fun streamTypesFor(type: String): List<String> {
    val t = type.lowercase()
    return when (t) {
        "movie" -> listOf("movie")
        "series", "anime", "tv", "show" -> listOf("series")
        in LIVE_TYPES -> listOf(t)
        else -> listOf(t, "movie", "series").distinct()
    }
}

fun normalizeContentId(id: String): String {
    val clean = id.trim()
    return when {
        clean.matches(Regex("^tt\\d+$")) -> clean
        clean.isNotEmpty() && clean.all { it.isDigit() } -> "tmdb:$clean"
        else -> clean
    }
}

fun yearOf(node: com.fasterxml.jackson.databind.JsonNode?): Int? {
    if (node == null || node.isNull) return null
    if (node.isNumber) return node.asInt().takeIf { it in 1900..2100 }
    val text = node.asText().trim()
    text.toIntOrNull()?.takeIf { it in 1900..2100 }?.let { return it }
    return Regex("(19|20)\\d{2}").find(text)?.value?.toIntOrNull()
}

fun youtubeIdOf(source: String): String? {
    val trimmed = source.trim()
    if (trimmed.matches(Regex("^[A-Za-z0-9_-]{11}$"))) return trimmed
    Regex("[?&]v=([A-Za-z0-9_-]{11})").find(trimmed)?.groupValues?.get(1)?.let { return it }
    return Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(trimmed)?.groupValues?.get(1)
}

fun fixPosterUrl(poster: String?): String? {
    val p = poster?.trim().orEmpty()
    if (p.isEmpty()) return null
    if (p.startsWith("//")) return "https:$p"
    if (p.startsWith("/") && !p.startsWith("//")) return "https://image.tmdb.org/t/p/w500$p"
    if (p.startsWith("http://") || p.startsWith("https://")) return p
    return null
}

fun stripHtml(raw: String?): String =
    raw.orEmpty().replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()

fun resolutionOf(label: String): Pair<String?, Int> {
    val l = label.lowercase()
    return when {
        Regex("\\b(2160p?|4k|uhd)\\b").containsMatchIn(l) -> "4K" to 5
        Regex("\\b1440p?\\b").containsMatchIn(l) -> "1440p" to 4
        Regex("\\b1080(?:p|i)\\b").containsMatchIn(l) -> "1080p" to 3
        Regex("\\b720p?\\b").containsMatchIn(l) -> "720p" to 2
        Regex("\\b480p?\\b|\\bdvdrip\\b").containsMatchIn(l) -> "480p" to 1
        Regex("\\b360p?\\b").containsMatchIn(l) -> "360p" to 1
        Regex("\\b(cam|ts|tc|scr)\\b").containsMatchIn(l) -> "CAM" to 0
        Regex("\\bauto\\b").containsMatchIn(l) -> "AUTO" to 2
        else -> null to 1
    }
}

fun qualityValue(tag: String?): Int {
    val t = tag.orEmpty()
    val query = if (t.equals("4K", ignoreCase = true)) "2160p"
    else Regex("(\\d{3,4}[pP])").find(t)?.groupValues?.get(1)
    return getQualityFromName(query)
}

private fun seedersOf(label: String): Int {
    Regex("[👥🌱👤]\\s*(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    return Regex("(?:^|\\s)(\\d{2,})\\s*(?:seeders?|peers?)\\b", RegexOption.IGNORE_CASE)
        .find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

fun buildMagnet(infoHash: String?, name: String?, sources: List<String>): String? {
    val hash = infoHash.orEmpty().replace(Regex("[^a-fA-F0-9]"), "").lowercase()
    if (hash.length != 40) return null
    return buildString {
        append("magnet:?xt=urn:btih:").append(hash)
        if (!name.isNullOrBlank()) {
            append("&dn=").append(java.net.URLEncoder.encode(name.trim().take(120), "UTF-8"))
        }
        val trackers = FALLBACK_TRACKERS +
            sources.mapNotNull {
                when {
                    it.startsWith("tracker:") -> it.removePrefix("tracker:")
                    it.startsWith("dht:") -> it.removePrefix("dht:")
                    else -> null
                }
            }
        trackers.distinct().forEach { tracker ->
            append("&tr=").append(java.net.URLEncoder.encode(tracker, "UTF-8"))
        }
    }
}

fun mergeStreamHeaders(stream: StremioStream): Map<String, String> {
    val merged = mutableMapOf<String, String>()
    stream.headers?.forEach { (k, v) -> merged[k] = v }
    stream.behaviorHints?.headers?.forEach { (k, v) -> merged[k] = v }
    stream.behaviorHints?.proxyHeaders?.request?.forEach { (k, v) -> merged[k] = v }
    return merged
}

fun splitKodiHeaders(url: String): Pair<String, Map<String, String>> {
    val cut = url.indexOf('|')
    if (cut == -1) return url to emptyMap()
    val params = url.substring(cut + 1).split('&')
    if (params.isEmpty() || params.any { !it.contains('=') }) return url to emptyMap()
    val headers = params.mapNotNull { part ->
        val key = part.substringBefore('=').trim()
        if (key.isEmpty()) null else key to part.substringAfter('=')
    }.toMap()
    if (headers.isEmpty()) return url to emptyMap()
    return url.substring(0, cut) to headers
}

fun parseAddonUrl(raw: String): String? {
    val line = raw.trim()
    if (line.isEmpty()) return null
    val url = line.substringAfter("|", line).trim()
    return url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

fun toAddonConfigs(urls: List<String>): List<AddonConfig> =
    urls.mapNotNull(::parseAddonUrl).distinct().map { AddonConfig("", it) }

fun toStreamLink(stream: StremioStream, addonName: String, addonOrder: Int): StreamLink? {
    val direct = stream.url?.trim().orEmpty()
    val hash = stream.infoHash?.trim().orEmpty()
    val body = stream.description?.trim().orEmpty().ifEmpty { stream.title?.trim().orEmpty() }
    val text = listOfNotNull(stream.name?.trim(), body).filter { it.isNotEmpty() }.joinToString(" ")
    val low = text.lowercase()
    var kodiHeaders: Map<String, String> = emptyMap()
    val url = when {
        direct.startsWith("http://") || direct.startsWith("https://") -> {
            val (clean, kodi) = splitKodiHeaders(direct)
            val path = clean.replace(Regex("^https?://[^/]+"), "")
            if (Regex("/(login|logout|signin|signup)([._?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(path)) return null
            kodiHeaders = kodi
            clean
        }
        direct.startsWith("magnet:") -> direct
        hash.isNotEmpty() -> buildMagnet(
            hash,
            stream.name ?: stream.behaviorHints?.filename,
            stream.sources
        ) ?: return null
        else -> return null
    }
    val (resolution, rank) = resolutionOf(low)
    val header = stream.name?.trim().orEmpty()
    val title = when {
        body.isNotEmpty() && header.isNotEmpty() && header != body && header != addonName -> "$header • $body"
        body.isNotEmpty() -> body
        else -> header.ifEmpty { url }
    }
    return StreamLink(
        url = url,
        source = addonName,
        title = title,
        qualityTag = resolution,
        headers = mergeStreamHeaders(stream) + kodiHeaders,
        resolutionRank = rank,
        seeders = seedersOf(low),
        addonOrder = addonOrder,
        fileIdx = stream.fileIdx
    )
}

fun sortAndDedupe(links: List<StreamLink>): List<StreamLink> {
    val seen = mutableSetOf<String>()
    val unique = links.filter { link ->
        val hash = Regex("urn:btih:([a-fA-F0-9]{40})", RegexOption.IGNORE_CASE)
            .find(link.url)?.groupValues?.get(1)?.lowercase()
        val identity = if (hash != null) "$hash:${link.fileIdx}"
        else link.url.replace(Regex("^https?://"), "").trimEnd('/').substringBefore("#").lowercase()
        identity.isNotEmpty() && seen.add("${link.addonOrder}|$identity")
    }
    return unique.sortedWith(
        compareByDescending(StreamLink::resolutionRank)
            .thenByDescending(StreamLink::seeders)
            .thenBy(StreamLink::addonOrder)
    ).take(MAX_STREAMS)
}

fun matchesQuery(entry: CatalogEntry, query: String): Boolean {
    val q = query.lowercase().trim()
    if (q.isEmpty()) return false
    val haystack = (entry.name + " " + stripHtml(entry.description)).lowercase()
    val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val hits = tokens.count { token ->
        haystack.contains(token) ||
            stringList(entry.genres).any { it.lowercase().contains(token) } ||
            stringList(entry.cast).any { it.lowercase().contains(token) }
    }
    if (hits >= tokens.size) return true
    val flatTitle = haystack.replace(Regex("[^a-z0-9]"), "")
    val flatQuery = q.replace(Regex("[^a-z0-9]"), "")
    return flatTitle.isNotEmpty() && flatQuery.isNotEmpty() && flatTitle.contains(flatQuery)
}
