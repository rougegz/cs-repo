package com.chartdrama

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager

const val DEFAULT_BASE_URL = "https://www.chartdrama.com"

/** Home categories in the exact order the user requested. Pair(displayName, slug) */
val CHART_CATALOG: List<Pair<String, String>> = listOf(
    "Reelshort" to "reelshort",
    "Dramabox" to "dramabox",
    "GoodShort" to "goodshort",
    "DramaWave" to "dramawave",
    "NetShort" to "netshort",
    "ShortMax" to "shortmax",
    "StardustTV" to "stardusttv",
    "FreeReels" to "freereels",
    "StarShort" to "starshort",
    "ShotShort" to "shotshort",
    "DramaTV" to "dramatv",
    "4Drama" to "4drama",
    "FlexTV" to "flextv",
    "Shorts" to "shorts",
    "NovaFilck" to "novafilck",
    "ThisReels" to "thisreels",
    "SodaTV" to "sodatv",
    "KalosTV" to "kalostv",
    "MuVpix" to "muvpix",
    "Toonory" to "toonory",
    "AuraReels" to "aurareels",
    "VenixTV" to "venixtv",
    "StarReel" to "starreel",
    "LeapReels" to "leapreels",
    "TasteLife" to "tastelife",
    "FlareFlow" to "flareflow",
    "JoyReels" to "joyreels",
    "ZiptaleTV" to "ziptaletv",
    "Vyntage" to "vyntage",
    "SanpPlay" to "sanpplay",
    "SwoopReels" to "swoopreels",
    "Flikso" to "flikso",
    "Plotify" to "plotify",
    "Myrelle" to "myrelle",
    "Nebuluxe" to "nebuluxe",
    "Lunory" to "lunory",
)

/** slug -> sourceId as seen in the site's JS `We` object */
val SLUG_TO_ID: Map<String, Int> = mapOf(
    "reelshort" to 2, "dramabox" to 5, "goodshort" to 6, "dramawave" to 7,
    "netshort" to 9, "shortmax" to 10, "stardusttv" to 12, "freereels" to 13,
    "starshort" to 14, "shotshort" to 16, "dramatv" to 17, "4drama" to 19,
    "flextv" to 20, "shorts" to 21, "novafilck" to 22, "thisreels" to 23,
    "sodatv" to 24, "kalostv" to 25, "muvpix" to 27, "toonory" to 28,
    "aurareels" to 29, "venixtv" to 30, "starreel" to 33, "leapreels" to 34,
    "tastelife" to 35, "flareflow" to 43, "joyreels" to 46, "ziptaletv" to 47,
    "vyntage" to 51, "sanpplay" to 55, "swoopreels" to 56, "flikso" to 57,
    "plotify" to 59, "myrelle" to 75, "nebuluxe" to 65, "lunory" to 66,
)

fun browserHeaders(): Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
    "Accept" to "application/json, text/plain, */*",
    "Accept-Language" to "en-US,en;q=0.9",
    "Referer" to DEFAULT_BASE_URL + "/",
)

object ChartStore {
    private const val PREFS = "chartdrama_prefs"
    private const val KEY_DOMAIN_1 = "chartdrama_domain_1"
    private const val KEY_DOMAIN_2 = "chartdrama_domain_2"
    private const val KEY_DOMAIN_3 = "chartdrama_domain_3"
    private const val KEY_ACTIVE = "chartdrama_active"
    private const val KEY_BASE_LEGACY = "base_url_override"
    private const val KEY_CF_PREFIX = "cf_cookie_"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) synchronized(this) {
            if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun loadDomain(index: Int): String? = prefs?.getString(
        when (index) {
            1 -> KEY_DOMAIN_1
            2 -> KEY_DOMAIN_2
            3 -> KEY_DOMAIN_3
            else -> return null
        }, null
    )?.takeIf { it.isNotBlank() }

    fun saveDomain(index: Int, url: String?) {
        val key = when (index) {
            1 -> KEY_DOMAIN_1
            2 -> KEY_DOMAIN_2
            3 -> KEY_DOMAIN_3
            else -> return
        }
        val e = prefs?.edit() ?: return
        val norm = normalizeBaseUrl(url)
        if (norm.isNullOrBlank()) e.remove(key) else e.putString(key, norm)
        e.apply()
    }

    fun loadActive(): Int = prefs?.getInt(KEY_ACTIVE, 1)?.coerceIn(1, 3) ?: 1

    fun saveActive(index: Int) {
        prefs?.edit()?.putInt(KEY_ACTIVE, index.coerceIn(1, 3))?.apply()
    }

    fun activeBase(): String? {
        val idx = loadActive()
        return loadDomain(idx)?.let { normalizeBaseUrl(it) } ?: loadBaseLegacy()?.let { normalizeBaseUrl(it) }
    }

    private fun loadBaseLegacy(): String? = prefs?.getString(KEY_BASE_LEGACY, null)?.takeIf { it.isNotBlank() }

    fun loadBase(): String? = activeBase() ?: loadBaseLegacy()

    fun saveBase(url: String?) {
        val idx = loadActive()
        saveDomain(idx, url)
        val e = prefs?.edit() ?: return
        val norm = normalizeBaseUrl(url)
        if (norm.isNullOrBlank()) e.remove(KEY_BASE_LEGACY) else e.putString(KEY_BASE_LEGACY, norm)
        e.apply()
    }

    fun saveCfCookie(domain: String, cookie: String) {
        val clean = domain.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':').removePrefix("www.")
        val host = if (clean.isBlank()) domain else clean
        prefs?.edit()?.putString(KEY_CF_PREFIX + host, cookie)?.apply()
        if (host != "chartdrama.com") {
            prefs?.edit()?.putString(KEY_CF_PREFIX + "chartdrama.com", cookie)?.apply()
        }
        try { CookieManager.getInstance().setCookie("https://" + host, cookie) } catch (_: Exception) {}
        try { CookieManager.getInstance().setCookie("https://www." + host, cookie) } catch (_: Exception) {}
        try { CookieManager.getInstance().setCookie("https://chartdrama.com", cookie) } catch (_: Exception) {}
    }

    fun loadCfCookie(domain: String): String? {
        val clean = domain.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
        return prefs?.getString(KEY_CF_PREFIX + clean, null)
            ?: prefs?.getString(KEY_CF_PREFIX + clean.removePrefix("www."), null)
            ?: prefs?.getString(KEY_CF_PREFIX + "chartdrama.com", null)
            ?: prefs?.getString("cf_cookie_chartdrama.com", null)
    }

    fun cfHeadersFor(url: String): Map<String, String> {
        val host = try { java.net.URL(url).host } catch (_: Exception) { return emptyMap() }
        val cookie = loadCfCookie(host) ?: loadCfCookie(host.removePrefix("www.")) ?: prefs?.getString(KEY_CF_PREFIX + "chartdrama.com", null) ?: return emptyMap()
        return mapOf("Cookie" to cookie)
    }

    fun hasCfCookie(): Boolean = !loadCfCookie("chartdrama.com").isNullOrBlank() || !loadCfCookie("www.chartdrama.com").isNullOrBlank()

    fun getCfCookieForStatus(): String? = loadCfCookie("chartdrama.com") ?: loadCfCookie("www.chartdrama.com")
}

fun normalizeBaseUrl(input: String?): String? {
    val t = input?.trim()?.trimEnd('/') ?: return null
    if (t.isEmpty()) return null
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://" + t
}

fun subdomainBase(base: String, slug: String): String {
    val norm = normalizeBaseUrl(base) ?: DEFAULT_BASE_URL
    val withoutScheme = norm.removePrefix("https://").removePrefix("http://")
    val host = withoutScheme.removePrefix("www.").substringBefore('/').substringBefore(':')
    return "https://" + slug + "." + host
}

fun parseChartUrl(url: String): Pair<String, String>? {
    val qIdx = url.indexOf('?')
    val pathPart = if (qIdx == -1) url else url.substring(0, qIdx)
    val marker = "/d/"
    val idx = pathPart.indexOf(marker)
    if (idx == -1) return null
    val slug = pathPart.substring(idx + marker.length).trimEnd('/')
    if (slug.isEmpty()) return null
    val dramaId = url.substringAfter("dramaId=", "").substringBefore("&").takeIf { it.isNotEmpty() }
    return slug to (dramaId ?: "")
}
