package com.vdrama

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.URLUtil

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
    "FlickReels" to "hoshiyomi-flickreels",
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
 * Persists the user-chosen domains and Cloudflare cookies.
 * Handles 3 mirrors via vdrama_domain_1/2/3 + vdrama_active_index,
 * plus cf_cookie_v-drama.net for Cloudflare clearance.
 * Legacy single-key base_url_override is migrated automatically.
 */
object VdramaStore {
    private const val PREFS = "vdrama_prefs"
    private const val KEY_LEGACY = "base_url_override"
    private const val KEY_DOMAIN_1 = "vdrama_domain_1"
    private const val KEY_DOMAIN_2 = "vdrama_domain_2"
    private const val KEY_DOMAIN_3 = "vdrama_domain_3"
    private const val KEY_ACTIVE = "vdrama_active_index"
    private const val KEY_CF = "cf_cookie_v-drama.net"
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

    // ---- domain helpers ----

    fun loadDomain(index: Int): String? {
        migrateLegacyIfNeeded()
        val key = when (index) {
            0 -> KEY_DOMAIN_1
            1 -> KEY_DOMAIN_2
            2 -> KEY_DOMAIN_3
            else -> return null
        }
        return prefs?.getString(key, null)?.takeIf { it.isNotBlank() }?.let { normalizeBaseUrl(it) }
    }

    fun saveDomain(index: Int, raw: String?) {
        val key = when (index) {
            0 -> KEY_DOMAIN_1
            1 -> KEY_DOMAIN_2
            2 -> KEY_DOMAIN_3
            else -> return
        }
        val editor = prefs?.edit() ?: return
        if (raw.isNullOrBlank()) {
            editor.remove(key)
            editor.apply()
            return
        }
        val normalized = normalizeBaseUrl(raw) ?: return
        // validation: require valid URL (URLUtil.isValidUrl or normalize succeeds)
        if (!URLUtil.isValidUrl(normalized)) return
        editor.putString(key, normalized)
        editor.apply()
    }

    fun loadActiveIndex(): Int = (prefs?.getInt(KEY_ACTIVE, 0) ?: 0).coerceIn(0, 2)

    fun saveActiveIndex(index: Int) {
        prefs?.edit()?.putInt(KEY_ACTIVE, index.coerceIn(0, 2))?.apply()
    }

    // Compatibility aliases for dialog
    fun getDomains(): List<String> = List(3) { loadDomain(it) ?: "" }
    fun getActiveIndex(): Int = loadActiveIndex()
    fun setActiveIndex(i: Int) = saveActiveIndex(i)

    /** Returns the currently selected domain, or DEFAULT if none set. */
    fun activeBase(): String {
        migrateLegacyIfNeeded()
        return loadDomain(loadActiveIndex()) ?: DEFAULT_BASE_URL
    }

    /** Alias used by VDramaPlugin. */
    fun base(): String = activeBase()

    /** Backwards compat: single override used by VDramaPlugin. Returns active domain. */
    fun loadOverride(): String? {
        migrateLegacyIfNeeded()
        return loadDomain(loadActiveIndex()) ?: prefs?.getString(KEY_LEGACY, null)?.takeIf { it.isNotBlank() }?.let { normalizeBaseUrl(it) }
    }

    fun saveOverride(url: String?) {
        saveDomain(loadActiveIndex(), url)
        if (url.isNullOrBlank()) prefs?.edit()?.remove(KEY_LEGACY)?.apply()
    }

    private fun migrateLegacyIfNeeded() {
        val legacy = prefs?.getString(KEY_LEGACY, null)?.takeIf { it.isNotBlank() } ?: return
        if (prefs?.contains(KEY_DOMAIN_1) == true) return
        val normalized = normalizeBaseUrl(legacy) ?: legacy
        if (URLUtil.isValidUrl(normalized)) {
            prefs?.edit()?.putString(KEY_DOMAIN_1, normalized)?.remove(KEY_LEGACY)?.apply()
        }
    }

    // ---- Cloudflare ----

    /** Save cf_clearance cookie string to the exact key required by spec. */
    fun saveCfCookie(cookie: String) {
        prefs?.edit()?.putString(KEY_CF, cookie)?.apply()
        prefs?.edit()?.putString(KEY_CF_PREFIX + "v-drama.net", cookie)?.apply()
        prefs?.edit()?.putString(KEY_CF_PREFIX + "www.v-drama.net", cookie)?.apply()
        runCatching { CookieManager.getInstance().setCookie(DEFAULT_BASE_URL, cookie) }
        runCatching { CookieManager.getInstance().setCookie(activeBase(), cookie) }
    }

    fun saveCfCookie(url: String, cookie: String) {
        val host = runCatching { java.net.URL(url).host }.getOrNull() ?: "v-drama.net"
        prefs?.edit()?.putString(KEY_CF_PREFIX + host, cookie)?.apply()
        prefs?.edit()?.putString(KEY_CF, cookie)?.apply()
        runCatching { CookieManager.getInstance().setCookie(url, cookie) }
    }

    fun loadCfCookie(): String? = prefs?.getString(KEY_CF, null)?.takeIf { it.isNotBlank() }

    fun getCfCookie(host: String): String? =
        prefs?.getString(KEY_CF_PREFIX + host, null)?.takeIf { it.isNotBlank() }
            ?: prefs?.getString(KEY_CF, null)?.takeIf { it.isNotBlank() }

    fun getCfCookieForUrl(url: String): String? {
        val host = runCatching { java.net.URL(url).host }.getOrNull() ?: return loadCfCookie()
        return getCfCookie(host) ?: getCfCookie(host.removePrefix("www."))
    }

    /** Headers to inject Cloudflare cookie into requests. */
    fun cfHeaders(): Map<String, String> {
        val cookie = loadCfCookie() ?: return emptyMap()
        return mapOf("Cookie" to cookie)
    }

    fun cfHeadersFor(url: String): Map<String, String> {
        val cookie = getCfCookieForUrl(url) ?: return emptyMap()
        return mapOf("Cookie" to cookie)
    }

    /** Merge browser headers with cf cookie if present. */
    fun headersWithCf(url: String): Map<String, String> {
        val base = browserHeaders()
        val cf = cfHeadersFor(url)
        return if (cf.isEmpty()) base else base + cf
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
