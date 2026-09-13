package com.stremiouniversal

import com.lagradost.cloudstream3.utils.getQualityFromName

const val MAX_STREAMS = 180
const val MAX_SUBTITLES = 12
const val MAX_SEARCH_RESULTS = 60
const val MAX_ITEMS_PER_ROW = 40
const val MAX_CATALOGS_PER_ADDON = 30
const val FILTER_CATALOGS_PER_ADDON = 6

private val LIVE_TYPES = setOf("tv", "channel", "livestream", "live", "iptv")

private val CREDENTIAL_HEADERS = setOf(
    "authorization", "cookie", "set-cookie", "host",
    "content-length", "connection", "proxy-authorization"
)

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

private const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

fun manifestBase(manifestUrl: String): String =
    manifestUrl.substringBefore("?").replace(Regex("/manifest\\.json.*$"), "").trimEnd('/')

fun manifestQuery(manifestUrl: String): String =
    if (manifestUrl.contains("?")) "?" + manifestUrl.substringAfter("?") else ""

fun withQuery(url: String, suffix: String): String = url + suffix

fun isLiveType(type: String?): Boolean = type?.lowercase() in LIVE_TYPES

fun streamTypesFor(type: String): List<String> {
    val t = type.lowercase()
    return when (t) {
        "movie" -> listOf("movie")
        "series", "anime" -> listOf("series")
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

fun displayName(name: String?, title: String?): String = when {
    !name.isNullOrBlank() && !title.isNullOrBlank() -> "$name $title"
    else -> title ?: name.orEmpty()
}

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

private fun codecOf(l: String): String? = when {
    Regex("\\b(av1|av01)\\b").containsMatchIn(l) -> "AV1"
    Regex("\\b(x265|h.?265|hevc)\\b").containsMatchIn(l) -> "HEVC"
    Regex("\\b(x264|h.?264|avc)\\b").containsMatchIn(l) -> "H.264"
    Regex("\\bvp9\\b").containsMatchIn(l) -> "VP9"
    else -> null
}

private fun audioOf(l: String): String? = when {
    Regex("\\batmos\\b|\\btruehd\\b").containsMatchIn(l) -> "Atmos"
    Regex("\\bdts[-\\s]?hd\\b").containsMatchIn(l) -> "DTS-HD"
    Regex("\\bdts\\b").containsMatchIn(l) -> "DTS"
    Regex("\\bflac\\b").containsMatchIn(l) -> "FLAC"
    Regex("\\baac\\b|\\beac3\\b").containsMatchIn(l) -> "AAC"
    Regex("\\bac3\\b").containsMatchIn(l) -> "AC3"
    Regex("\\bopus\\b").containsMatchIn(l) -> "Opus"
    else -> null
}

private fun languageOf(l: String): String? {
    val found = mutableListOf<String>()
    listOf(
        Regex("\\bmulti\\b") to "Multi",
        Regex("\\bdual[\\s._-]?audio\\b|\\bdual\\b") to "Dual",
        Regex("\\bhindi\\b") to "Hin",
        Regex("\\btamil\\b") to "Tam",
        Regex("\\btelugu\\b") to "Tel",
        Regex("\\bmalayalam\\b") to "Mal",
        Regex("\\bkannada\\b") to "Kan",
        Regex("\\bbengali\\b") to "Ben",
        Regex("\\bjapanese?\\b") to "Jpn",
        Regex("\\bkorean?\\b") to "Kor",
        Regex("\\bchinese?\\b") to "Chi",
        Regex("\\bspanish?\\b") to "Spa",
        Regex("\\bfrench?\\b") to "Fre",
        Regex("\\bgerman?\\b") to "Ger",
        Regex("\\brussian?\\b") to "Rus",
        Regex("\\benglish\\b") to "Eng"
    ).forEach { (pattern, tag) -> if (pattern.containsMatchIn(l)) found.add(tag) }
    return found.takeIf { it.isNotEmpty() }?.joinToString("+")
}

private fun sizeOf(videoBytes: Long?, label: String): String? {
    if (videoBytes != null && videoBytes > 0) {
        val gb = videoBytes.toDouble() / 1073741824.0
        return "%.2f".format(gb).trimEnd('0').trimEnd('.') + "GB"
    }
    val match = Regex("(\\d+(?:\\.\\d+)?)\\s*(GB|GiB|MB|MiB)", RegexOption.IGNORE_CASE).find(label)
        ?: return null
    val amount = match.groupValues[1]
    return if (match.groupValues[2].lowercase().startsWith("g")) "${amount}GB"
    else {
        val mb = amount.toDoubleOrNull() ?: return null
        if (mb >= 1024) "%.2f".format(mb / 1024).trimEnd('0').trimEnd('.') + "GB" else "${amount}MB"
    }
}

private fun seedersOf(label: String): Int {
    Regex("[👥🌱👤]\\s*(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    return Regex("(?:^|\\s)(\\d{2,})\\s*(?:seeders?|peers?)\\b", RegexOption.IGNORE_CASE)
        .find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

fun buildMagnet(infoHash: String?, name: String?, sources: List<String>, extraTrackers: List<String> = emptyList()): String? {
    val hash = infoHash.orEmpty().replace(Regex("[^a-fA-F0-9]"), "").lowercase()
    if (hash.length != 40) return null
    return buildString {
        append("magnet:?xt=urn:btih:").append(hash)
        if (!name.isNullOrBlank()) {
            append("&dn=").append(java.net.URLEncoder.encode(name.trim().take(120), "UTF-8"))
        }
        val trackers = FALLBACK_TRACKERS + extraTrackers +
            sources.filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") }
        trackers.distinct().forEach { tracker ->
            append("&tr=").append(java.net.URLEncoder.encode(tracker, "UTF-8"))
        }
    }
}

fun sanitizeHeaders(request: Map<String, String>?, fallback: Map<String, String>? = null): Map<String, String> {
    val merged = mutableMapOf<String, String>()
    fallback?.forEach { (k, v) -> merged[k] = v }
    request?.forEach { (k, v) -> merged[k] = v }
    val out = merged.filterKeys { it.lowercase() !in CREDENTIAL_HEADERS }.toMutableMap()
    if (out.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
        out["User-Agent"] = DEFAULT_UA
    }
    return out
}

fun toStreamLink(stream: StremioStream, addonName: String, addonOrder: Int): StreamLink? {
    val direct = stream.url?.trim().orEmpty()
    val hash = stream.infoHash?.trim().orEmpty()
    val label = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ")
    val low = label.lowercase()
    val url = when {
        direct.startsWith("http://") || direct.startsWith("https://") -> {
            val path = direct.replace(Regex("^https?://[^/]+"), "")
            if (Regex("/(login|logout|signin|signup)([._?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(path)) return null
            direct
        }
        direct.startsWith("magnet:") -> direct
        hash.isNotEmpty() -> buildMagnet(
            hash,
            stream.behaviorHints?.filename ?: stream.title ?: stream.name,
            stream.sources
        ) ?: return null
        else -> return null
    }
    val (resolution, rank) = resolutionOf(low)
    val parts = listOfNotNull(
        resolution,
        sizeOf(stream.behaviorHints?.videoSize, low)?.let { "💾$it" },
        seedersOf(low).takeIf { it > 0 }?.let { "🌱$it" },
        codecOf(low),
        audioOf(low)?.let { "🔊$it" },
        languageOf(low)
    )
    val prefix = parts.joinToString("|")
    return StreamLink(
        url = url,
        label = if (prefix.isEmpty()) "[$addonName]" else "$prefix[$addonName]",
        qualityTag = resolution,
        headers = sanitizeHeaders(stream.behaviorHints?.proxyHeaders?.request, stream.behaviorHints?.headers),
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
        val key = if (hash != null) "$hash:${link.fileIdx}"
        else link.url.replace(Regex("^https?://"), "").trimEnd('/').substringBefore("#").lowercase()
        key.isNotEmpty() && seen.add(key)
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
            entry.genres.any { it.lowercase().contains(token) } ||
            entry.cast.any { it.lowercase().contains(token) }
    }
    if (hits >= tokens.size) return true
    val flatTitle = haystack.replace(Regex("[^a-z0-9]"), "")
    val flatQuery = q.replace(Regex("[^a-z0-9]"), "")
    return flatTitle.isNotEmpty() && flatQuery.isNotEmpty() && flatTitle.contains(flatQuery)
}
