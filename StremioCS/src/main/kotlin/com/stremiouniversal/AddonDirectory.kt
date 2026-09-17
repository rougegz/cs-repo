package com.stremiouniversal

import com.lagradost.cloudstream3.app
import org.json.JSONArray
data class DirectoryAddon(
    val name: String,
    val transportUrl: String,
    val description: String,
    val id: String
)

object AddonDirectory {
    private var cached: List<DirectoryAddon>? = null
    private var cachedAt: Long = 0L
    private const val TTL_MS = 24L * 60L * 60L * 1000L

    suspend fun load(force: Boolean = false): List<DirectoryAddon>? {
        val now = System.currentTimeMillis()
        if (!force) cached?.let { if (now - cachedAt < TTL_MS) return it }
        val parsed = resultOr(null) {
            val text = app.get(StremioConstants.ADDON_DIRECTORY_URL, timeout = 20L).text
            parse(text)
        }
        if (parsed != null) {
            cached = parsed
            cachedAt = now
        }
        return parsed ?: cached
    }

    fun parse(text: String): List<DirectoryAddon>? = runCatching {
        val arr = JSONArray(text)
        val out = ArrayList<DirectoryAddon>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val transportUrl = obj.optString("transportUrl").trim()
            if (transportUrl.isEmpty()) continue
            val manifest = obj.optJSONObject("manifest")
            val name = (manifest?.optString("name").orEmpty().trim())
                .ifEmpty { addonDisplayHost(transportUrl) }
            out.add(
                DirectoryAddon(
                    name = name,
                    transportUrl = transportUrl,
                    description = manifest?.optString("description").orEmpty().trim(),
                    id = manifest?.optString("id").orEmpty().trim()
                )
            )
        }
        out.sortedBy { it.name.lowercase() }
    }.getOrNull()

    fun filter(all: List<DirectoryAddon>, query: String): List<DirectoryAddon> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return all.filter { addon ->
            val hay = (addon.name + " " + addon.description + " " + addon.id).lowercase()
            tokens.all { hay.contains(it) }
        }
    }
}
