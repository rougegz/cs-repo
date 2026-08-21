package com.dramafren

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager

const val DEFAULT_API_BASE = "https://api.dramafren.org"
const val DEFAULT_REEL_BASE = "https://reelfren.dramafren.org"
const val DEFAULT_BASE = DEFAULT_API_BASE

val DRAMAFREN_CATALOG: List<Pair<String, String>> = listOf(
    "Melolo" to "melolo",
    "Sereal+" to "sereal",
    "PineDrama" to "pinedrama",
    "Shorten" to "shorten",
    "HappyShort" to "happyshort",
    "Vigloo" to "vigloo",
    "RaptDrama" to "raptdrama",
    "CubeTV" to "cubetv",
    "JoyReels" to "joyreels",
    "AnyReel" to "anyreel",
    "MiniTV" to "minitv",
    "Bstation" to "bstation",
    "GoldDrama" to "golddrama",
    "Reelife" to "reelife",
    "ReelShort" to "reelshort",
    "DramaBox" to "dramabox",
    "DramaNova" to "dramanova",
    "KalosTV" to "kalostv",
    "VibeShort" to "vibeshort",
    "FreeReels" to "freereels",
    "WeTV" to "wetv",
    "StoryReel" to "storyreel",
    "MovieBox" to "moviebox",
    "MovieBox Shorts" to "movieboxshorts",
    "MyDrama" to "mydrama",
    "FlareFlow" to "flareflow",
    "PlayLet" to "playlet",
    "ShortMax" to "shortmax",
)

val PROVIDER_NAMES: Map<String, String> = DRAMAFREN_CATALOG.toMap()

val PROVIDER_CATEGORY_PARAM: Map<String, String> = mapOf(
    "sereal" to "feed=latest",
    "pinedrama" to "category=0",
    "shorten" to "category=releases",
    "happyshort" to "category=home",
    "vigloo" to "category=home",
    "bstation" to "category=dracin",
    "golddrama" to "category=all",
    "reelife" to "category=all",
    "dramanova" to "category=all",
    "kalostv" to "category=all",
    "vibeshort" to "category=all",
    "freereels" to "category=all",
    "moviebox" to "category=1232643093049001320",
    "movieboxshorts" to "category=all",
    "mydrama" to "category=all",
)

fun browserHeaders(): Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9",
    "Referer" to "$DEFAULT_BASE/",
)

object DramaFrenStore {
    private const val PREFS = "dramafren_prefs"
    private const val KEY_DOMAIN_1 = "domain_1"
    private const val KEY_DOMAIN_2 = "domain_2"
    private const val KEY_DOMAIN_3 = "domain_3"
    private const val KEY_ACTIVE = "active_domain"
    private const val KEY_CF_PREFIX = "cf_cookie_"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) synchronized(this) {
            if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    // 3-domain handling — production level
    fun getDomains(): List<String> {
        val d1 = normalizeBaseUrl(prefs?.getString(KEY_DOMAIN_1, null)) ?: DEFAULT_API_BASE
        val d2 = normalizeBaseUrl(prefs?.getString(KEY_DOMAIN_2, null)) ?: DEFAULT_REEL_BASE
        val d3 = normalizeBaseUrl(prefs?.getString(KEY_DOMAIN_3, null)) ?: ""
        return listOf(d1, d2, d3)
    }

    fun getActiveIndex(): Int = prefs?.getInt(KEY_ACTIVE, 0) ?: 0

    fun setActiveIndex(index: Int) {
        prefs?.edit()?.putInt(KEY_ACTIVE, index.coerceIn(0, 2))?.apply()
    }

    fun saveDomain(index: Int, raw: String?) {
        val key = when(index) { 0->KEY_DOMAIN_1; 1->KEY_DOMAIN_2; else->KEY_DOMAIN_3 }
        val n = normalizeBaseUrl(raw)
        val e = prefs?.edit() ?: return
        if (n.isNullOrBlank()) {
            if (index==2) e.remove(key) else e.putString(key, if(index==0) DEFAULT_API_BASE else DEFAULT_REEL_BASE)
        } else e.putString(key, n)
        e.apply()
    }

    fun base(): String {
        val idx = getActiveIndex()
        val domains = getDomains()
        val chosen = domains.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: DEFAULT_API_BASE
        return chosen
    }

    // Backwards compat for old keys
    fun apiBase(): String = getDomains()[0].takeIf { it.isNotBlank() } ?: DEFAULT_API_BASE
    fun reelBase(): String = getDomains()[1].takeIf { it.isNotBlank() } ?: DEFAULT_REEL_BASE
    fun loadApiOverride(): String? = prefs?.getString(KEY_DOMAIN_1, null)?.takeIf { it.isNotBlank() }
    fun loadReelOverride(): String? = prefs?.getString(KEY_DOMAIN_2, null)?.takeIf { it.isNotBlank() }
    fun saveApiBase(raw: String?) = saveDomain(0, raw)
    fun saveReelBase(raw: String?) = saveDomain(1, raw)

    // Cloudflare
    fun saveCfCookie(url: String, cookie: String) {
        val host = runCatching { java.net.URL(url).host }.getOrNull() ?: return
        prefs?.edit()?.putString(KEY_CF_PREFIX + host, cookie)?.apply()
        runCatching { CookieManager.getInstance().setCookie(url, cookie) }
    }
    fun getCfCookie(host: String): String? = prefs?.getString(KEY_CF_PREFIX + host, null)
    fun getCfCookieForUrl(url: String): String? {
        val host = runCatching { java.net.URL(url).host }.getOrNull() ?: return null
        return getCfCookie(host)
    }
}

fun normalizeBaseUrl(input: String?): String? {
    val t = input?.trim()?.trimEnd('/') ?: return null
    if (t.isEmpty()) return null
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
}

private val CATALOG_SLUGS_BY_LENGTH = DRAMAFREN_CATALOG.map { it.second }.sortedByDescending { it.length }

fun parseDramaUrl(link: String): Pair<String, String>? {
    val path = link.substringBefore('?').substringBefore('#').trimEnd('/')
    val marker = "/drama/"
    val idx = path.lastIndexOf(marker)
    if (idx == -1) return null
    val after = path.substring(idx + marker.length)
    val slash = after.indexOf('/')
    if (slash == -1) return null
    val provider = after.substring(0, slash)
    if (provider !in PROVIDER_NAMES.values && provider !in DRAMAFREN_CATALOG.map { it.second }) return null
    val rest = after.substring(slash + 1)
    val id = rest.substringBefore('-')
    if (id.isEmpty() || !id.all { it.isDigit() }) return null
    return provider to id
}

fun providerExploreUrl(base: String, provider: String, page: Int): String {
    val cat = PROVIDER_CATEGORY_PARAM[provider]
    val pagePart = if (page > 1) "&page=$page" else ""
    return if (cat != null) "$base/explore?provider=$provider&lang=en&$cat$pagePart"
    else "$base/explore?provider=$provider&lang=en$pagePart"
}
