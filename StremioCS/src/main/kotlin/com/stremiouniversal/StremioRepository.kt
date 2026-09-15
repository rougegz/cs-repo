package com.stremiouniversal
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject
class StremioRepository(prefs: SharedPreferences?) {
    private val prefs: SharedPreferences? = prefs
    companion object {
        private data class TimedManifest(val at: Long, val manifest: StremioManifest)
        private val manifests = java.util.concurrent.ConcurrentHashMap<String, TimedManifest>()
    }
    fun loadConfiguredAddons(): List<AddonConfig> {
        val raw = prefs?.getString(StremioConstants.KEY_ADDONS, null)
            ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val rawUrl = obj.optString("url").ifEmpty { obj.optString("manifestUrl") }
                val normalized = normalizeAddonUrl(rawUrl) ?: return@mapNotNull null
                AddonConfig(
                    name = obj.optString("name").trim(),
                    manifestUrl = normalized,
                    enabled = obj.optBoolean("enabled", true)
                )
            }
        }.getOrDefault(emptyList())
    }
    fun loadEnabledAddons(): List<AddonConfig> =
        loadConfiguredAddons().filter { it.enabled }
    fun saveAddons(configs: List<AddonConfig>) {
        val arr = JSONArray()
        val now = System.currentTimeMillis()
        configs.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("url", it.manifestUrl)
                    .put("enabled", it.enabled)
                    .put("addedAt", now)
            )
        }
        prefs?.edit()
            ?.putString(StremioConstants.KEY_ADDONS, arr.toString())
            ?.putInt(StremioConstants.KEY_SCHEMA_V, StremioConstants.SCHEMA_V)
            ?.apply()
        manifests.keys.retainAll(configs.map { it.manifestUrl }.toSet())
    }
    fun addAddon(manifestUrl: String): Boolean {
        val normalized = normalizeAddonUrl(manifestUrl) ?: return false
        val current = loadConfiguredAddons()
        if (current.any { addonBaseKey(it.manifestUrl) == addonBaseKey(normalized) }) return false
        saveAddons(current + AddonConfig("", normalized))
        return true
    }
    fun removeAddonAt(index: Int) {
        val current = loadConfiguredAddons().toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            saveAddons(current)
        }
    }
    fun setAddonEnabled(index: Int, enabled: Boolean) {
        val current = loadConfiguredAddons().toMutableList()
        if (index in current.indices) {
            current[index] = current[index].copy(enabled = enabled)
            saveAddons(current)
        }
    }
    fun moveAddon(from: Int, to: Int) {
        val current = loadConfiguredAddons().toMutableList()
        if (from !in current.indices || to !in current.indices || from == to) return
        val item = current.removeAt(from)
        current.add(to, item)
        saveAddons(current)
    }
    fun clearAll() = saveAddons(emptyList())
    fun exportJson(): String {
        val arr = JSONArray()
        loadConfiguredAddons().forEach {
            arr.put(JSONObject().put("name", it.name).put("url", it.manifestUrl).put("enabled", it.enabled))
        }
        return arr.toString(2)
    }
    fun importJson(raw: String): Int {
        if (raw.length > 256 * 1024) return -1
        val parsed = runCatching {
            val input = raw.trim()
            val arr = if (input.startsWith("[")) JSONArray(input)
            else JSONArray().apply { put(JSONObject(input)) }
            if (arr.length() > 500) return -1
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = obj.optString("url").ifEmpty { obj.optString("manifestUrl") }
                normalizeAddonUrl(url)?.let {
                    AddonConfig(obj.optString("name").trim(), it, obj.optBoolean("enabled", true))
                }
            }
        }.getOrNull() ?: return -1
        if (parsed.isEmpty()) return 0
        val current = loadConfiguredAddons()
        val bases = current.map { manifestBase(it.manifestUrl) }.toMutableSet()
        val merged = current.toMutableList()
        var added = 0
        parsed.forEach {
            if (bases.add(manifestBase(it.manifestUrl))) {
                merged.add(it)
                added++
            }
        }
        if (added > 0) saveAddons(merged)
        return added
    }
    suspend fun previewAddon(manifestUrl: String): AddonPreview? {
        val normalized = normalizeAddonUrl(manifestUrl) ?: return null
        val manifest = manifestOf(normalized) ?: return null
        return AddonPreview(
            name = manifest.name?.trim()?.takeIf { it.isNotEmpty() } ?: addonDisplayHost(normalized),
            catalogCount = manifest.catalogs.size
        )
    }
    fun addonCount(): Int = loadConfiguredAddons().size
    suspend fun configuredAddons(): List<ConfiguredAddon> = supervisorScope {
        val enabled = loadEnabledAddons()
        if (enabled.isEmpty()) return@supervisorScope emptyList()
        enabled.mapIndexed { order, config ->
            async {
                val manifest = manifestOf(config.manifestUrl) ?: return@async null
                describe(config, order, manifest)
            }
        }.mapNotNull { resultOr(null) { it.await() } }
    }
    private suspend fun manifestOf(url: String): StremioManifest? {
        val ttlMs = (prefs?.getInt(
            StremioConstants.KEY_CACHE_TTL_H,
            StremioConstants.DEFAULT_CACHE_TTL_H
        ) ?: StremioConstants.DEFAULT_CACHE_TTL_H).coerceIn(0, 168) * 60L * 60L * 1000L
        manifests[url]?.let { (at, manifest) ->
            if (ttlMs <= 0L || System.currentTimeMillis() - at < ttlMs) return manifest
        }
        val timeoutS = (prefs?.getInt(
            StremioConstants.KEY_TIMEOUT_S,
            StremioConstants.DEFAULT_TIMEOUT_S
        ) ?: StremioConstants.DEFAULT_TIMEOUT_S).coerceIn(5, 120).toLong()
        return resultOr(null) {
            app.get(url, timeout = timeoutS).parsedSafe<StremioManifest>()
                ?.also { manifests[url] = TimedManifest(System.currentTimeMillis(), it) }
        } ?: manifests[url]?.manifest
    }
    private fun describe(config: AddonConfig, order: Int, manifest: StremioManifest): ConfiguredAddon? {
        val base = manifestBase(config.manifestUrl)
        if (base.isEmpty()) return null
        val catalogs = manifest.catalogs.flatMap { catalog ->
            if (catalog.type != null) listOf(catalog)
            else catalog.types.map { type -> catalog.copy(type = type, types = mutableListOf(type)) }
        }.filter { it.id.isNotEmpty() && it.type != null }.take(MAX_CATALOGS_PER_ADDON)
        return ConfiguredAddon(
            order = order,
            displayName = manifest.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: config.name.ifEmpty { "Addon ${order + 1}" },
            base = base,
            querySuffix = manifestQuery(config.manifestUrl),
            idPrefixes = streamPrefixesOf(manifest),
            catalogs = catalogs,
            hasCatalog = hasResource(manifest, "catalog") || catalogs.isNotEmpty(),
            hasStream = hasResource(manifest, "stream"),
            hasMeta = hasResource(manifest, "meta"),
            hasSubtitles = hasResource(manifest, "subtitles")
        )
    }
    private fun hasResource(manifest: StremioManifest, name: String): Boolean =
        manifest.resources.any { node -> resourceMatches(node, name) }
    private fun resourceMatches(node: JsonNode, name: String): Boolean {
        if (node.isTextual) return node.asText() == name
        if (!node.isObject) return false
        val declared = node.path("name").asText("")
        val legacyId = node.path("id").asText("")
        if (declared == name || legacyId == name) return true
        return name == "subtitles" && (declared == "subtitle" || declared == "subs")
    }
    private fun streamPrefixesOf(manifest: StremioManifest): List<String> {
        manifest.resources.forEach { node ->
            if (node.isObject && node.path("name").asText("") == "stream") {
                val declared = node.path("idPrefixes")
                if (declared.isArray && declared.size() > 0) {
                    return declared.mapNotNull { it.asText(null) }
                }
            }
        }
        return manifest.idPrefixes
    }
    suspend fun catalogRows(page: Int): List<CatalogRow> = supervisorScope {
        val addons = configuredAddons().filter { it.hasCatalog }
        if (addons.isEmpty()) return@supervisorScope emptyList()
        val skip = (page - 1).coerceAtLeast(0) * 100
        addons.map { addon ->
            async {
                addon.catalogs
                    .filter { catalog -> !isSearchCatalog(catalog) }
                    .map { catalog -> async { catalogRow(addon, catalog, skip) } }
                    .mapNotNull { resultOr(null) { it.await() } }
            }
        }.flatMap { resultOr(emptyList()) { it.await() } }
    }
    private suspend fun catalogMetas(addon: ConfiguredAddon, catalog: StremioCatalog, skip: Int): List<CatalogEntry> {
        val type = catalog.type ?: return emptyList()
        val paging = if (skip > 0) "/skip=$skip" else ""
        val encType = java.net.URLEncoder.encode(type, "UTF-8")
        val encId = java.net.URLEncoder.encode(catalog.id, "UTF-8")
        val url = withQuery("${addon.base}/catalog/$encType/${encId}$paging.json", addon.querySuffix)
        val timeoutS = (prefs?.getInt(StremioConstants.KEY_TIMEOUT_S, StremioConstants.DEFAULT_TIMEOUT_S)
            ?: StremioConstants.DEFAULT_TIMEOUT_S).coerceIn(5, 120).toLong()
        return resultOr(emptyList()) {
            app.get(url, timeout = timeoutS).parsedSafe<CatalogResponse>()?.metas.orEmpty()
        }
    }
    private suspend fun catalogRow(addon: ConfiguredAddon, catalog: StremioCatalog, skip: Int): CatalogRow? {
        val type = catalog.type ?: return null
        val metas = catalogMetas(addon, catalog, skip)
        if (metas.isEmpty()) return null
        val items = metas.mapNotNull { it.toRef(addon, type) }.take(MAX_ITEMS_PER_ROW)
        if (items.isEmpty()) return null
        return CatalogRow(catalog.name?.takeIf { it.isNotBlank() } ?: catalog.id, items)
    }
    suspend fun searchAll(query: String): List<MetaRef> = supervisorScope {
        val q = query.trim()
        if (q.isEmpty()) return@supervisorScope emptyList()
        val addons = configuredAddons().filter { it.hasCatalog }
        if (addons.isEmpty()) return@supervisorScope emptyList()
        val native = addons.map { addon ->
            async {
                addon.catalogs.filter(::supportsSearch).map { catalog ->
                    async { searchCatalog(addon, catalog, q) }
                }.flatMap { resultOr(emptyList()) { it.await() } }
            }
        }.flatMap { resultOr(emptyList()) { it.await() } }
            .distinctBy { "${it.type}:${it.id}" }
        if (native.size >= NATIVE_SEARCH_MIN) return@supervisorScope native.take(MAX_SEARCH_RESULTS)
        if (prefs?.getBoolean(StremioConstants.KEY_SEARCH_FALLBACK, StremioConstants.DEFAULT_SEARCH_FALLBACK) == false) {
            return@supervisorScope native.take(MAX_SEARCH_RESULTS)
        }
        val filtered = addons.map { addon ->
            async {
                addon.catalogs
                    .filter { !supportsSearch(it) && !isSearchCatalog(it) }
                    .take(FILTER_CATALOGS_PER_ADDON)
                    .map { catalog ->
                        async {
                            val type = catalog.type ?: return@async emptyList<MetaRef>()
                            catalogMetas(addon, catalog, 0)
                                .filter { matchesQuery(it, q) }
                                .mapNotNull { it.toRef(addon, type) }
                        }
                    }.flatMap { resultOr(emptyList()) { it.await() } }
            }
        }.flatMap { resultOr(emptyList()) { it.await() } }
        (native + filtered).distinctBy { "${it.type}:${it.id}" }.take(MAX_SEARCH_RESULTS)
    }
    private suspend fun searchCatalog(addon: ConfiguredAddon, catalog: StremioCatalog, query: String): List<MetaRef> {
        val type = catalog.type ?: return emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val encType = java.net.URLEncoder.encode(type, "UTF-8")
        val encId = java.net.URLEncoder.encode(catalog.id, "UTF-8")
        val url = withQuery("${addon.base}/catalog/$encType/${encId}/search=$encoded.json", addon.querySuffix)
        val timeoutS = (prefs?.getInt(StremioConstants.KEY_TIMEOUT_S, StremioConstants.DEFAULT_TIMEOUT_S)
            ?: StremioConstants.DEFAULT_TIMEOUT_S).coerceIn(5, 120).toLong()
        return resultOr(emptyList()) {
            app.get(url, timeout = timeoutS).parsedSafe<CatalogResponse>()?.metas.orEmpty()
        }.mapNotNull { it.toRef(addon, type) }
    }
    suspend fun metaDetails(ref: LinkRef): MetaDetails? {
        val addons = configuredAddons()
        if (addons.isEmpty()) return null
        val origin = addons.firstOrNull { it.base == ref.base }
        if (origin != null) {
            fetchMeta(origin, ref.type, ref.id)?.toDetails()?.let { return it }
        }
        if (ref.id.matches(Regex("^tt\\d+$"))) {
            cinemetaMeta(ref.type, ref.id)?.toDetails()?.let { return it }
        }
        return supervisorScope {
            addons.filter { it.hasMeta && it.base != ref.base }.map { addon ->
                async { fetchMeta(addon, ref.type, ref.id)?.toDetails() }
            }.mapNotNull { resultOr(null) { it.await() } }.firstOrNull()
        }
    }
    suspend fun fetchMeta(addon: ConfiguredAddon, type: String, id: String): CatalogEntry? {
        val encoded = java.net.URLEncoder.encode(id, "UTF-8")
        val url = withQuery("${addon.base}/meta/$type/$encoded.json", addon.querySuffix)
        val timeoutS = (prefs?.getInt(StremioConstants.KEY_TIMEOUT_S, StremioConstants.DEFAULT_TIMEOUT_S)
            ?: StremioConstants.DEFAULT_TIMEOUT_S).coerceIn(5, 120).toLong()
        val body = resultOr(null) { app.get(url, timeout = timeoutS).text } ?: return null
        return extractMetaEntry(body, id)
    }
    private suspend fun cinemetaMeta(type: String, id: String): CatalogEntry? {
        val kind = if (type == "movie") "movie" else "series"
        val timeoutS = (prefs?.getInt(StremioConstants.KEY_TIMEOUT_S, StremioConstants.DEFAULT_TIMEOUT_S)
            ?: StremioConstants.DEFAULT_TIMEOUT_S).coerceIn(5, 120).toLong()
        return resultOr(null) {
            app.get("${StremioConstants.CINEMETA_BASE}/meta/$kind/$id.json", timeout = timeoutS)
                .parsedSafe<CatalogResponse>()?.meta
        }
    }
    suspend fun streamsFor(ref: LinkRef): StreamsResult = supervisorScope {
        val normalized = normalizeContentId(ref.id)
        val targets = configuredAddons().filter { addon ->
            addon.hasStream && (addon.idPrefixes.isEmpty() || addon.idPrefixes.any { prefix ->
                prefix.isNotEmpty() && (ref.id.startsWith(prefix) || normalized.startsWith(prefix))
            })
        }
        if (targets.isEmpty()) return@supervisorScope StreamsResult(emptyList(), emptyList(), emptyList())
        val perAddon = targets.map { addon ->
            async { addon to addonStreams(addon, ref) }
        }.mapNotNull { resultOr(null) { it.await() } }
        val maxStreams = (prefs?.getInt(StremioConstants.KEY_MAX_STREAMS, StremioConstants.DEFAULT_MAX_STREAMS)
            ?: StremioConstants.DEFAULT_MAX_STREAMS).coerceIn(10, MAX_STREAMS)
        val links = sortAndDedupe(
            perAddon.flatMap { (addon, streams) ->
                streams.mapNotNull { toStreamLink(it, addon.displayName, addon.order) }
            }
        ).take(maxStreams)
        val raw = perAddon.flatMap { (_, streams) -> streams }
        val subsEnabled = prefs?.getBoolean(StremioConstants.KEY_SUBS_ENABLED, StremioConstants.DEFAULT_SUBS_ENABLED)
            ?: StremioConstants.DEFAULT_SUBS_ENABLED
        StreamsResult(
            links = links,
            inlineSubtitles = if (subsEnabled) raw.flatMap { it.subtitles }
                .mapNotNull { toRemoteSubtitle(it) }
                .distinctBy { it.url } else emptyList(),
            youtubeIds = raw.mapNotNull { it.ytId }.distinct()
        )
    }
    private suspend fun addonStreams(addon: ConfiguredAddon, ref: LinkRef): List<StremioStream> {
        val timeoutS = (prefs?.getInt(StremioConstants.KEY_TIMEOUT_S, StremioConstants.DEFAULT_TIMEOUT_S)
            ?: StremioConstants.DEFAULT_TIMEOUT_S).coerceIn(5, 120).toLong().coerceAtLeast(30L)
        return streamTypesFor(ref.type).amap { kind ->
            val encoded = java.net.URLEncoder.encode(ref.id, "UTF-8")
            val url = withQuery("${addon.base}/stream/$kind/$encoded.json", addon.querySuffix)
            resultOr(emptyList()) {
                app.get(url, timeout = timeoutS).parsedSafe<StreamsResponse>()?.streams.orEmpty()
            }
        }.flatten()
    }
    suspend fun subtitlesFor(ref: LinkRef): List<RemoteSubtitle> = supervisorScope {
        val subsEnabled = prefs?.getBoolean(StremioConstants.KEY_SUBS_ENABLED, StremioConstants.DEFAULT_SUBS_ENABLED)
            ?: StremioConstants.DEFAULT_SUBS_ENABLED
        if (!subsEnabled) return@supervisorScope emptyList()
        val timeoutS = (prefs?.getInt(StremioConstants.KEY_TIMEOUT_S, StremioConstants.DEFAULT_TIMEOUT_S)
            ?: StremioConstants.DEFAULT_TIMEOUT_S).coerceIn(5, 120).toLong().coerceAtMost(30L)
        configuredAddons().filter { it.hasSubtitles }.map { addon ->
            async {
                val encoded = java.net.URLEncoder.encode(ref.id, "UTF-8")
                val url = withQuery("${addon.base}/subtitles/${ref.type}/$encoded.json", addon.querySuffix)
                resultOr(emptyList()) {
                    app.get(url, timeout = timeoutS).parsedSafe<SubsResponse>()?.subtitles.orEmpty()
                }.mapNotNull(::toRemoteSubtitle)
            }
        }.flatMap { resultOr(emptyList()) { it.await() } }
            .distinctBy { it.url }
            .take(MAX_SUBTITLES)
    }
    private fun toRemoteSubtitle(sub: StremioSubtitle): RemoteSubtitle? {
        val url = sub.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        return RemoteSubtitle(url, sub.lang?.takeIf { it.isNotBlank() } ?: "en")
    }
    private fun CatalogEntry.toRef(addon: ConfiguredAddon, fallbackType: String): MetaRef? {
        if (id.isEmpty() || name.isEmpty()) return null
        return MetaRef(addon.base, type ?: fallbackType, id, name, fixPosterUrl(poster))
    }
    private fun CatalogEntry.toDetails(): MetaDetails? {
        if (name.isEmpty() || id.isEmpty()) return null
        val videos = videos.orEmpty().mapNotNull { video ->
            val vid = video.id ?: return@mapNotNull null
            VideoRef(
                id = vid,
                title = video.name ?: video.title ?: "Episode",
                season = video.season ?: 1,
                episode = video.episode ?: video.number ?: 1,
                thumbnail = fixPosterUrl(video.thumbnail),
                overview = stripHtml(video.overview ?: video.description).take(800)
            )
        }
        return MetaDetails(
            id = id,
            type = type.orEmpty(),
            name = name,
            poster = fixPosterUrl(poster),
            background = fixPosterUrl(background),
            description = stripHtml(description).take(1000),
            year = yearOf(year),
            rating = imdbRating?.asText()?.toDoubleOrNull(),
            genres = (stringList(genres) + stringList(genre)).distinct().takeIf { it.isNotEmpty() }.orEmpty(),
            cast = stringList(cast),
            trailerYoutubeIds = trailers.mapNotNull { it.source?.let(::youtubeIdOf) } +
                trailerStreams.mapNotNull { it.ytId },
            videos = videos
        )
    }
    private fun isSearchCatalog(catalog: StremioCatalog): Boolean =
        catalog.extra?.any { it.name == "search" && it.isRequired == true } == true
    private fun supportsSearch(catalog: StremioCatalog): Boolean =
        catalog.extra?.any { it.name == "search" } == true ||
            catalog.extraSupported?.contains("search") == true
}
