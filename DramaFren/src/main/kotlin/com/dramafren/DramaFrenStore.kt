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

/** Verified genre tabs per provider: "provider|Genre" -> category id (from explore pages). */
val PROVIDER_GENRES: Map<String, String> = mapOf(
        "pinedrama|Trending" to "101",
        "pinedrama|High society" to "7628991192533095425",
        "pinedrama|Urban life" to "7628991192536978448",
        "pinedrama|Celebrity" to "7628991192536994832",
        "pinedrama|Workplace" to "7628991192537011216",
        "pinedrama|Royal court" to "7628991192537043984",
        "pinedrama|Rural life" to "7628991192537060368",
        "pinedrama|Aristocracy" to "7628991192537076752",
        "pinedrama|System quests" to "7628991192537093136",
        "pinedrama|Rags to riches" to "7628991192537109520",
        "pinedrama|Martial arts" to "7628991192537125904",
        "pinedrama|Cultivation" to "7628991192537142288",
        "pinedrama|Fantasy" to "7628991192537158672",
        "raptdrama|Comedy" to "18",
        "raptdrama|Passion" to "20",
        "raptdrama|Western" to "22",
        "raptdrama|Comic drama" to "23",
        "vibeshort|Contemporary" to "200001",
        "vibeshort|Historical" to "200002",
        "vibeshort|Fantasy" to "200003",
        "vibeshort|Realistic" to "200004",
        "vibeshort|Suspense" to "200005",
        "vibeshort|Urban" to "200006",
        "vibeshort|Sci-Fi" to "200009",
        "vibeshort|Gaming" to "200011",
        "vibeshort|High Fantasy" to "200012",
        "vibeshort|Low Fantasy" to "200013",
        "vibeshort|Xianxia" to "200014",
        "wetv|Anime" to "10071",
        "wetv|🍿MINISERIES🔥" to "10393",
        "wetv|Chinese" to "10023",
        "wetv|🌈 LOVE UNFILTERED" to "10479",
        "wetv|K-Drama" to "10090",
        "wetv|Asian" to "10101",
        "wetv|Variety" to "10326",
        "wetv|Movies" to "10102",
        "storyreel|Contemporary" to "200001",
        "storyreel|Historical" to "200002",
        "storyreel|Fantasy" to "200003",
        "storyreel|Realistic" to "200004",
        "storyreel|Suspense" to "200005",
        "storyreel|Urban" to "200006",
        "storyreel|Sci-Fi" to "200009",
        "storyreel|Gaming" to "200011",
        "storyreel|High Fantasy" to "200012",
        "storyreel|Low Fantasy" to "200013",
        "storyreel|Xianxia" to "200014",
        "moviebox|TOP100" to "6159907949583500480",
        "moviebox|Returnings" to "8109661952110199232",
        "moviebox|Anime" to "62133389738001440",
        "moviebox|Animation" to "3130672938110833528",
        "moviebox|Black Drama" to "8505361996374835640",
        "moviebox|SA Drama" to "1503943377597910848",
        "moviebox|K-Drama" to "4380734070238626200",
        "moviebox|C-Drama" to "173752404280836544",
        "moviebox|Thai-Drama" to "1164329479448281992",
        "moviebox|Action" to "6978603205429526968",
        "moviebox|Fantay" to "7219449993227633120",
        "moviebox|Sci-Fi" to "4075118481979722960",
        "moviebox|Superhero" to "7317586330186645624",
        "moviebox|Period" to "1826888755169346232",
        "moviebox|Romance" to "2389813900859556536",
        "moviebox|Comedy" to "8785384881686725944",
        "moviebox|Teen Romance" to "1746633591129342248",
        "moviebox|Teen Fantasy" to "6934224112055632896",
        "moviebox|Cop Drama" to "8897504261530175160",
        "moviebox|Medical Drama" to "4162236365956153536",
        "flareflow|Modern" to "200072",
        "flareflow|Fantasy" to "200074",
        "flareflow|Urban" to "200137",
        "flareflow|Historical" to "200073",
        "flareflow|Low Fantasy" to "200080",
        "flareflow|Suspense" to "200076",
        "flareflow|Realistic" to "200135",
        "flareflow|High Fantasy" to "200143",
        "flareflow|Xianxia" to "200084",
        "flareflow|Sci-Fi" to "200083",
        "flareflow|Gaming" to "200085",
        "flareflow|LGBT" to "200077",
        "playlet|Anime" to "765",
        "playlet|Hot Picks🌟" to "376",
        "playlet|Asian" to "378",
        "playlet|Original" to "377",
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
    // Accept any provider slug: search/detail can return apps outside our 28-row catalog
    // (dramawave, radreel, stardusttv...) and rejecting them caused "error" on load.
    if (!provider.matches(Regex("[a-z0-9]+"))) return null
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
