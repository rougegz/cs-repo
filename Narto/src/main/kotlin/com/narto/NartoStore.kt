package com.narto

import android.content.Context
import android.content.SharedPreferences

const val DEFAULT_BASE_URL = "https://edge.narto-drama.com"

val NARTO_CATALOG: List<Pair<String, String>> = listOf(
    "BibiShort" to "bibishort",
    "BiliTV" to "bilitv",
    "CubeTV" to "cubetv",
    "DotDrama" to "dotdrama",
    "Dramabite" to "dramabite",
    "DramaBox" to "dramabox",
    "DramaNova" to "dramanova",
    "DramaWave" to "dramawave",
    "FlareFlow" to "flareflow",
    "FlexTV" to "flextv",
    "FlickReels" to "flickreels",
    "FreeReels" to "freereels",
    "FunDrama" to "fundrama",
    "GoodShort" to "goodshort",
    "HappyShort" to "happyshort",
    "iDrama" to "idrama",
    "JoyReels" to "joyreels",
    "KalosTV" to "kalostv",
    "Melolo" to "melolo",
    "MicroDrama" to "microdrama",
    "MoboReels" to "moboreels",
    "NetShort" to "netshort",
    "PineDrama" to "pinedrama",
    "RapidTV" to "rapidtv",
    "ReelBuzz" to "reelbuzz",
    "Reelife" to "reelife",
    "ReelShort" to "reelshort",
    "Sereal+" to "serealplus",
    "Shortical" to "shortical",
    "ShortMax" to "shortmax",
    "StardustTV" to "stardusttv",
    "StarShort" to "starshort",
    "Velolo" to "velolo",
    "Vigloo" to "vigloo",
    "Vyntage" to "vyntage",
)

val NARTO_APP_NAMES: Map<String, String> = NARTO_CATALOG.toMap()

fun nartoHeaders(): Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
    "Accept" to "application/json, text/plain, */*",
    "Accept-Language" to "en-US,en;q=0.9",
    "X-Requested-With" to "XMLHttpRequest",
)

/** HTML headers (no X-Requested-With — that makes the site return JSON). */
fun nartoHtmlHeaders(): Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
)

object NartoStore {
    private const val PREFS = "narto_prefs"
    private const val KEY_BASE = "base_url_override"
    private const val KEY_D1 = "domain_1"
    private const val KEY_D2 = "domain_2"
    private const val KEY_D3 = "domain_3"
    private const val KEY_ACTIVE = "active_domain"
    private const val KEY_CF_PREFIX = "cf_cookie_"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) synchronized(this) {
            if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    // 3-domain support
    fun getDomains(): List<String> {
        val legacy = loadBase()
        val d1 = normalizeNartoBase(prefs?.getString(KEY_D1, null)) ?: legacy ?: DEFAULT_BASE_URL
        val d2 = normalizeNartoBase(prefs?.getString(KEY_D2, null)) ?: ""
        val d3 = normalizeNartoBase(prefs?.getString(KEY_D3, null)) ?: ""
        return listOf(d1, d2, d3)
    }

    fun getActiveIndex(): Int = (prefs?.getInt(KEY_ACTIVE, 0) ?: 0).coerceIn(0, 2)
    fun setActiveIndex(i: Int) { prefs?.edit()?.putInt(KEY_ACTIVE, i.coerceIn(0,2))?.apply() }

    fun saveDomain(idx: Int, raw: String?) {
        val key = when(idx){0->KEY_D1;1->KEY_D2;else->KEY_D3}
        val n = normalizeNartoBase(raw)
        val e = prefs?.edit() ?: return
        if (n.isNullOrBlank()) {
            if (idx==0) e.putString(key, DEFAULT_BASE_URL) else e.remove(key)
        } else e.putString(key, n)
        e.apply()
    }

    fun activeBase(): String {
        val idx = getActiveIndex()
        return getDomains().getOrNull(idx)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
    }

    fun loadBase(): String? = prefs?.getString(KEY_BASE, null)?.takeIf { it.isNotBlank() }
    fun saveBase(url: String?) {
        val e = prefs?.edit() ?: return
        if (url.isNullOrBlank()) e.remove(KEY_BASE) else e.putString(KEY_BASE, url)
        e.apply()
    }

    fun saveCfCookie(urlOrDomain: String, cookie: String) {
        val domain = if (urlOrDomain.contains("://")) {
            try { java.net.URL(urlOrDomain).host } catch (_: Exception) { urlOrDomain }
        } else urlOrDomain
        prefs?.edit()?.putString(KEY_CF_PREFIX + domain, cookie)?.apply()
    }

    fun loadCfCookie(): String? {
        val host = try { java.net.URL(activeBase()).host } catch (_: Exception) { return null }
        return loadCfCookie(host)
    }

    fun loadCfCookie(domain: String): String? = prefs?.getString(KEY_CF_PREFIX + domain, null)

    fun injectCfCookies(url: String, headers: Map<String, String>): Map<String, String> {
        val domain = try { java.net.URL(url).host } catch (_: Exception) { return headers }
        val cookie = loadCfCookie(domain) ?: loadCfCookie(domain.removePrefix("www.")) ?: return headers
        val existing = headers["Cookie"]?.let { "$it; $cookie" } ?: cookie
        return headers + ("Cookie" to existing)
    }
}

fun normalizeNartoBase(input: String?): String? {
    val t = input?.trim()?.trimEnd('/') ?: return null
    if (t.isEmpty()) return null
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
}
