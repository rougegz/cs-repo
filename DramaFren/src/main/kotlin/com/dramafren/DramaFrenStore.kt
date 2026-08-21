package com.dramafren

import android.content.Context
import android.content.SharedPreferences

const val DEFAULT_BASE_URL = "https://goodshort.dramafren.org"

val DRAMAFREN_CATALOG: List<Pair<String, String>> = listOf(
    "DramaFren" to "dramafren",
    "DramaBox" to "dramabox",
    "GoodShort" to "goodshort",
    "NetShort" to "netshort",
    "FlickReels" to "flickreels",
    "StarDustTV" to "stardusttv",
    "DramaWave" to "dramawave",
    "ShortMax" to "shortmax",
    "ReelShort" to "reelshort",
    "iDrama" to "idrama",
    "FlexTV" to "flextv",
    "DreameShort" to "dreameshort",
    "StarShort" to "starshort",
    "KalosTV" to "kalostv",
    "DramaBite" to "dramabite",
    "ShotShort" to "shotshort",
    "DramaPops" to "dramapops",
    "MicroDrama" to "microdrama",
    "ShortWave" to "shortwave",
    "MoboReels" to "moboreels",
    "ReelFren" to "reelfren",
)

val APP_NAMES: Map<String, String> = DRAMAFREN_CATALOG.toMap()

private val CATALOG_SLUGS_BY_LENGTH: List<String> =
    DRAMAFREN_CATALOG.map { it.second }.sortedByDescending { it.length }

fun browserHeaders(): Map<String, String> = mapOf(
    "User-Agent" to
        "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
)

object DramaFrenStore {
    private const val PREFS = "dramafren_prefs"
    private const val KEY_OVERRIDE = "base_url_override"
    private const val KEY_CF_PREFIX = "cf_cookie_"

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

    fun saveCfCookie(domain: String, cookie: String) {
        prefs?.edit()?.putString(KEY_CF_PREFIX + domain, cookie)?.apply()
    }

    fun loadCfCookie(domain: String): String? =
        prefs?.getString(KEY_CF_PREFIX + domain, null)?.takeIf { it.isNotBlank() }

    fun clearCfCookie(domain: String) {
        prefs?.edit()?.remove(KEY_CF_PREFIX + domain)?.apply()
    }
}

fun normalizeBaseUrl(input: String?): String? {
    val trimmed = input?.trim()?.trimEnd('/') ?: return null
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

fun parseDramaUrl(link: String): Pair<String, String>? {
    val path = link.substringBefore('?').substringBefore('#').trimEnd('/')
    val marker = "/drama/"
    val idx = path.lastIndexOf(marker)
    if (idx == -1) {
        val segs = path.split("/").filter { it.isNotBlank() }
        if (segs.size >= 2) {
            val last = segs.last()
            val second = segs[segs.size - 2]
            if (last.all { it.isLetterOrDigit() || it == '-' }) {
                for (app in CATALOG_SLUGS_BY_LENGTH) {
                    if (second.equals(app, ignoreCase = true)) return app to last
                }
            }
        }
        return null
    }
    val slug = path.substring(idx + marker.length)
    for (app in CATALOG_SLUGS_BY_LENGTH) {
        val suffix = "-$app-"
        val at = slug.lastIndexOf(suffix)
        if (at != -1) {
            val id = slug.substring(at + suffix.length)
            if (id.isNotEmpty() && id.all { it.isLetterOrDigit() }) return app to id
        }
    }
    val parts = slug.split("-")
    if (parts.size >= 2) {
        val id = parts.last()
        if (id.all { it.isLetterOrDigit() }) {
            for (app in CATALOG_SLUGS_BY_LENGTH) {
                if (slug.contains("-$app-")) return app to id
            }
        }
    }
    return null
}

fun isCloudflareChallenge(html: String?, code: Int): Boolean {
    if (html == null) return code == 403 || code == 503
    val markers = listOf(
        "Just a moment", "challenge-platform", "cf-browser-verification",
        "Checking your browser", "Attention Required", "cf_chl", "ray_id"
    )
    return (code == 403 || code == 503 || code == 429) &&
        markers.any { html.contains(it, ignoreCase = true) } ||
        html.contains("cf-mitigated", ignoreCase = true)
}
