package com.stremiouniversal

import android.content.SharedPreferences
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject

private const val KEY_ADDONS = "stremio_addons"
private const val KEY_SUBTITLES = "external_subs"
private const val MANIFEST_TTL_MS = 24L * 60 * 60 * 1000

private val BUILT_IN_ADDONS = listOf(
    AddonConfig("DesiFlix", "https://manifest.desitvhub.eu.org/manifest.json")
)

private suspend fun <T> Deferred<T>.awaitOrNull(): T? = try {
    await()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

class StremioRepository(prefs: SharedPreferences?) {
    private val prefs: SharedPreferences? = prefs

    companion object {
        private data class TimedManifest(val at: Long, val manifest: StremioManifest)
        private val manifests = java.util.concurrent.ConcurrentHashMap<String, TimedManifest>()
    }

    fun loadConfiguredAddons(): List<AddonConfig> {
        val raw = prefs?.getString(KEY_ADDONS, null) ?: return BUILT_IN_ADDONS
        return runCatching {
            JSONArray(raw).let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val url = arr.optJSONObject(i)?.optString("url").orEmpty().trim()
                    val name = arr.optJSONObject(i)?.optString("name").orEmpty().trim()
                    url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        ?.let { AddonConfig(name, it) }
                }
            }.ifEmpty { BUILT_IN_ADDONS }
        }.getOrDefault(BUILT_IN_ADDONS)
    }

    fun saveAddons(configs: List<AddonConfig>) {
        val arr = JSONArray()
        configs.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.manifestUrl)) }
        prefs?.edit()?.putString(KEY_ADDONS, arr.toString())?.apply()
        manifests.clear()
    }

    fun subtitlesEnabled(): Boolean = prefs?.getBoolean(KEY_SUBTITLES, true) ?: true

    fun setSubtitlesEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SUBTITLES, enabled)?.apply()
    }

    suspend fun configuredAddons(): List<ConfiguredAddon> = supervisorScope {
        loadConfiguredAddons().mapIndexed { order, config ->
            async {
                val manifest = manifestOf(config.manifestUrl) ?: return@async null
                describe(config, order, manifest)
            }
        }.mapNotNull { it.awaitOrNull() }
    }

    private suspend fun manifestOf(url: String): StremioManifest? {
        manifests[url]?.let { (at, manifest) ->
            if (System.currentTimeMillis() - at < MANIFEST_TTL_MS) return manifest
        }
        return runCatching {
            app.get(url, timeout = 20L).parsedSafe<StremioManifest>()
                ?.also { manifests[url] = TimedManifest(System.currentTimeMillis(), it) }
        }.getOrNull() ?: manifests[url]?.manifest
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
        val skip = (page - 1) * 100
        addons.map { addon ->
            async {
                addon.catalogs
                    .filter { catalog -> !isSearchCatalog(catalog) }
                    .map { catalog -> async { catalogRow(addon, catalog, skip) } }
                    .mapNotNull { it.awaitOrNull() }
            }
        }.flatMap { it.awaitOrNull() ?: emptyList() }
    }

    private suspend fun catalogMetas(addon: ConfiguredAddon, catalog: StremioCatalog, skip: Int): List<CatalogEntry> {
        val type = catalog.type ?: return emptyList()
        val paging = if (skip > 0) "/skip=$skip" else ""
        val url = withQuery("${addon.base}/catalog/$type/${catalog.id}$paging.json", addon.querySuffix)
        return runCatching {
            app.get(url, timeout = 30L).parsedSafe<CatalogResponse>()?.metas.orEmpty()
        }.getOrDefault(emptyList())
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
        val native = addons.map { addon ->
            async {
                addon.catalogs.filter(::supportsSearch).map { catalog ->
                    async { searchCatalog(addon, catalog, q) }
                }.flatMap { it.awaitOrNull() ?: emptyList() }
            }
        }.flatMap { it.awaitOrNull() ?: emptyList() }
            .distinctBy { "${it.type}:${it.id}" }
        if (native.size >= NATIVE_SEARCH_MIN) return@supervisorScope native.take(MAX_SEARCH_RESULTS)
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
                    }.flatMap { it.awaitOrNull() ?: emptyList() }
            }
        }.flatMap { it.awaitOrNull() ?: emptyList() }
        (native + filtered).distinctBy { "${it.type}:${it.id}" }.take(MAX_SEARCH_RESULTS)
    }

    private suspend fun searchCatalog(addon: ConfiguredAddon, catalog: StremioCatalog, query: String): List<MetaRef> {
        val type = catalog.type ?: return emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = withQuery("${addon.base}/catalog/$type/${catalog.id}/search=$encoded.json", addon.querySuffix)
        return runCatching {
            app.get(url, timeout = 30L).parsedSafe<CatalogResponse>()?.metas.orEmpty()
        }.getOrDefault(emptyList()).mapNotNull { it.toRef(addon, type) }
    }

    suspend fun metaDetails(ref: LinkRef): MetaDetails? {
        val addons = configuredAddons()
        val origin = addons.firstOrNull { it.base == ref.base }
        if (origin != null) {
            fetchMeta(origin, ref.type, ref.id)?.toDetails()?.let { return it }
        }
        if (ref.id.matches(Regex("^tt\\d+"))) {
            cinemetaMeta(ref.type, ref.id)?.toDetails()?.let { return it }
        }
        return supervisorScope {
            addons.filter { it.hasMeta && it.base != ref.base }.map { addon ->
                async { fetchMeta(addon, ref.type, ref.id)?.toDetails() }
            }.mapNotNull { it.awaitOrNull() }.firstOrNull()
        }
    }

    suspend fun fetchMeta(addon: ConfiguredAddon, type: String, id: String): CatalogEntry? {
        val encoded = java.net.URLEncoder.encode(id, "UTF-8")
        val url = withQuery("${addon.base}/meta/$type/$encoded.json", addon.querySuffix)
        val body = runCatching { app.get(url, timeout = 20L).text }.getOrNull() ?: return null
        return extractMetaEntry(body, id)
    }

    private suspend fun cinemetaMeta(type: String, id: String): CatalogEntry? {
        val kind = if (type == "movie") "movie" else "series"
        return runCatching {
            app.get("https://v3-cinemeta.strem.io/meta/$kind/$id.json", timeout = 20L)
                .parsedSafe<CatalogResponse>()?.meta
        }.getOrNull()
    }

    suspend fun streamsFor(ref: LinkRef): StreamsResult = supervisorScope {
        val normalized = normalizeContentId(ref.id)
        val targets = configuredAddons().filter { addon ->
            addon.hasStream && (addon.idPrefixes.isEmpty() || addon.idPrefixes.any { prefix ->
                prefix.isNotEmpty() && (ref.id.startsWith(prefix) || normalized.startsWith(prefix))
            })
        }
        val perAddon = targets.map { addon ->
            async { addon to addonStreams(addon, ref) }
        }.mapNotNull { it.awaitOrNull() }
        val links = sortAndDedupe(
            perAddon.flatMap { (addon, streams) ->
                streams.mapNotNull { toStreamLink(it, addon.displayName, addon.order) }
            }
        )
        val raw = perAddon.flatMap { (_, streams) -> streams }
        StreamsResult(
            links = links,
            inlineSubtitles = raw.flatMap { it.subtitles }
                .mapNotNull { toRemoteSubtitle(it) }
                .distinctBy { it.url },
            youtubeIds = raw.mapNotNull { it.ytId }.distinct()
        )
    }

    private suspend fun addonStreams(addon: ConfiguredAddon, ref: LinkRef): List<StremioStream> {
        return streamTypesFor(ref.type).amap { kind ->
            val encoded = java.net.URLEncoder.encode(ref.id, "UTF-8")
            val url = withQuery("${addon.base}/stream/$kind/$encoded.json", addon.querySuffix)
            runCatching {
                app.get(url, timeout = 60L).parsedSafe<StreamsResponse>()?.streams.orEmpty()
            }.getOrDefault(emptyList())
        }.flatten()
    }

    suspend fun subtitlesFor(ref: LinkRef): List<RemoteSubtitle> = supervisorScope {
        configuredAddons().filter { it.hasSubtitles }.map { addon ->
            async {
                val encoded = java.net.URLEncoder.encode(ref.id, "UTF-8")
                val url = withQuery("${addon.base}/subtitles/${ref.type}/$encoded.json", addon.querySuffix)
                runCatching {
                    app.get(url, timeout = 15L).parsedSafe<SubsResponse>()?.subtitles.orEmpty()
                }.getOrDefault(emptyList()).mapNotNull(::toRemoteSubtitle)
            }
        }.flatMap { it.awaitOrNull() ?: emptyList() }
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
            year = year?.asText()?.toIntOrNull(),
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
