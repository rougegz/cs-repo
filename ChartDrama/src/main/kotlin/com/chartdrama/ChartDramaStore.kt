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
    private const val KEY_BASE = "base_url_override"
    private const val KEY_CF_PREFIX = "cf_cookie_"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) synchronized(this) {
            if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun loadBase(): String? = prefs?.getString(KEY_BASE, null)?.takeIf { it.isNotBlank() }

    fun saveBase(url: String?) {
        val e = prefs?.edit() ?: return
        if (url.isNullOrBlank()) e.remove(KEY_BASE) else e.putString(KEY_BASE, url)
        e.apply()
    }

    fun saveCfCookie(domain: String, cookie: String) {
        prefs?.edit()?.putString(KEY_CF_PREFIX + domain, cookie)?.apply()
        // Also push to WebView cookie store so future WebViews are pre-solved
        try { CookieManager.getInstance().setCookie("https://$domain", cookie) } catch (_: Exception) {}
    }

    fun loadCfCookie(domain: String): String? = prefs?.getString(KEY_CF_PREFIX + domain, null)

    fun cfHeadersFor(url: String): Map<String, String> {
        val host = try { java.net.URL(url).host } catch (_: Exception) { return emptyMap() }
        val cookie = loadCfCookie(host) ?: loadCfCookie(host.removePrefix("www.")) ?: return emptyMap()
        return mapOf("Cookie" to cookie)
    }
}

fun normalizeBaseUrl(input: String?): String? {
    val t = input?.trim()?.trimEnd('/') ?: return null
    if (t.isEmpty()) return null
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
}

/** Turn https://www.chartdrama.com + slug into https://slug.chartdrama.com  (handles mirrors like https://mirror.example.com) */
fun subdomainBase(base: String, slug: String): String {
    val norm = normalizeBaseUrl(base) ?: DEFAULT_BASE_URL
    // strip scheme
    val withoutScheme = norm.removePrefix("https://").removePrefix("http://")
    // strip www. if present
    val host = withoutScheme.removePrefix("www.").substringBefore('/').substringBefore(':')
    return "https://$slug.$host"
}

/** For host-agnostic parsing, chartdrama drama urls are /d/{slug} where slug is like 6a469b.../title */
fun parseChartUrl(url: String): Pair<String, String>? {
    // url is like https://reelshort.chartdrama.com/d/6a469b12d3f5c65f7f095b8a/you-ve-been-replaced-first-love?dramaId=77246
    // or https://reelshort.chartdrama.com/d/6a469b12d3f5c65f7f095b8a/you-ve-been-replaced-first-love
    // We need to extract the encoded slug and dramaId if present in query
    val qIdx = url.indexOf('?')
    val pathPart = if (qIdx == -1) url else url.substring(0, qIdx)
    val marker = "/d/"
    val idx = pathPart.indexOf(marker)
    if (idx == -1) return null
    val slug = pathPart.substring(idx + marker.length).trimEnd('/')
    if (slug.isEmpty()) return null
    // Try to get dramaId from query
    val dramaId = url.substringAfter("dramaId=", "").substringBefore("&").takeIf { it.isNotEmpty() }
    return slug to (dramaId ?: "")
}
