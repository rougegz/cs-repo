package com.vdrama

import android.content.Context
import android.content.SharedPreferences

/** Default site that aggregates the short-drama apps. Changeable in plugin settings. */
const val DEFAULT_BASE_URL = "https://v-drama.net"

/**
 * Home categories, in display order.
 * first = row title shown in CloudStream, second = app slug used by
 * /en/app/<slug>?page=N on the aggregator site.
 */
val VDRAMA_CATALOG: List<Pair<String, String>> = listOf(
    "DramaBox" to "dramabox",
    "ReelShort" to "reelshort",
    "FreeReels" to "freereels",
    "Youdrama" to "youdrama",
    "Hishort" to "hishort",
    "Meloshort" to "meloshort",
    "Sodareels" to "sodareels",
    "Dramamax" to "dramamax",
    "NetShort" to "netshort",
    "MoboReels" to "hoshiyomi-moboreels",
    "iDrama" to "hoshiyomi-idrama",
    "Pinedrama" to "hoshiyomi-pinedrama",
    "ShortMax" to "hoshiyomi-shortmax",
    "DramaBite" to "hoshiyomi-dramabite",
    "Flareflow" to "hoshiyomi-flareflow",
    "WeTV" to "hoshiyomi-wetv",
    "iQIYI" to "hoshiyomi-iqiyi",
    "DramaNova" to "hoshiyomi-dramanova",
    "Melolo" to "hoshiyomi-melolo",
    "StarShort" to "hoshiyomi-starshort",
)

/** App slug -> display name (used for result tags). */
val APP_NAMES: Map<String, String> = VDRAMA_CATALOG.toMap()

/** Slugs sorted longest-first so "-hoshiyomi-idrama-" wins over shorter overlaps. */
private val CATALOG_SLUGS_BY_LENGTH: List<String> =
    VDRAMA_CATALOG.map { it.second }.sortedByDescending { it.length }

/** Browser-like headers; the site serves a bot-block page without them. */
fun browserHeaders(): Map<String, String> = mapOf(
    "User-Agent" to
        "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
)

/**
 * Persists the user-chosen domain between restarts.
 * The live domain itself lives in MainAPI.mainUrl so CloudStream's own
 * per-provider URL override ("clone site") keeps working on top of it.
 */
object VdramaStore {
    private const val PREFS = "vdrama_prefs"
    private const val KEY_OVERRIDE = "base_url_override"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                }
            }
        }
    }

    fun loadOverride(): String? =
        prefs?.getString(KEY_OVERRIDE, null)?.takeIf { it.isNotBlank() }

    fun saveOverride(url: String?) {
        val editor = prefs?.edit() ?: return
        if (url.isNullOrBlank()) editor.remove(KEY_OVERRIDE) else editor.putString(KEY_OVERRIDE, url)
        editor.apply()
    }
}

/** Trim whitespace, guarantee a scheme, drop trailing slashes. Blank/null -> null. */
fun normalizeBaseUrl(input: String?): String? {
    val trimmed = input?.trim()?.trimEnd('/') ?: return null
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

/**
 * Drama urls look like /en/drama/<title-slug>-<app-slug>-<id> where both the
 * title and some app slugs contain hyphens, so match against known slugs and
 * take everything after the last "-<slug>-" occurrence as the id.
 *
 * Parses from the path only — host-agnostic, so old links keep working after
 * a domain change.
 */
fun parseDramaUrl(link: String): Pair<String, String>? {
    val path = link.substringBefore('?').substringBefore('#').trimEnd('/')
    val marker = "/en/drama/"
    val idx = path.lastIndexOf(marker)
    if (idx == -1) return null
    val slug = path.substring(idx + marker.length)
    for (app in CATALOG_SLUGS_BY_LENGTH) {
        val suffix = "-$app-"
        val at = slug.lastIndexOf(suffix)
        if (at != -1) {
            val id = slug.substring(at + suffix.length)
            if (id.isNotEmpty() && id.all { it.isLetterOrDigit() }) return app to id
        }
    }
    return null
}
