package com.stremiouniversal

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class FakePrefs : SharedPreferences {
    private val data = HashMap<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = HashMap(data)
    @Suppress("UNCHECKED_CAST")
    override fun getString(key: String, defValue: String?): String? = (data[key] as? String) ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (data[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(listener)
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    inner class FakeEditor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { removals.add(key); return this }
        override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearAll) data.clear()
            removals.forEach { data.remove(it) }
            data.putAll(pending)
        }
    }
}

class AddonStoreTest {

    private fun repoWith(vararg pairs: Pair<String, String>): StremioRepository {
        val prefs = FakePrefs()
        pairs.forEach { (k, v) -> prefs.edit().putString(k, v).apply() }
        return StremioRepository(prefs)
    }

    @Test fun emptyPrefsGivesEmptyList() {
        val repo = StremioRepository(FakePrefs())
        assertTrue(repo.loadConfiguredAddons().isEmpty())
        assertEquals(0, repo.addonCount())
        assertTrue(repo.loadEnabledAddons().isEmpty())
    }

    @Test fun corruptJsonFallsBackToEmpty() {
        val repo = repoWith(StremioConstants.KEY_ADDONS to "{not json")
        assertTrue(repo.loadConfiguredAddons().isEmpty())
    }

    @Test fun addAndDedupeByBaseKey() {
        val repo = StremioRepository(FakePrefs())
        assertTrue(repo.addAddon("https://foo.com/manifest.json"))
        assertFalse(repo.addAddon("https://FOO.com/manifest.json?token=x"))
        assertFalse(repo.addAddon("not a url"))
        assertEquals(1, repo.addonCount())
    }

    @Test fun removeToggleMoveClear() {
        val repo = StremioRepository(FakePrefs())
        repo.addAddon("https://a.com/manifest.json")
        repo.addAddon("https://b.com/manifest.json")
        repo.addAddon("https://c.com/manifest.json")
        assertEquals(3, repo.addonCount())

        repo.setAddonEnabled(1, false)
        assertEquals(2, repo.loadEnabledAddons().size)

        repo.moveAddon(0, 2)
        val after = repo.loadConfiguredAddons()
        assertEquals("https://c.com/manifest.json", after[2].manifestUrl.substringBefore("?"))

        repo.moveAddon(5, 0) // out of bounds: no-op
        repo.moveAddon(0, 0) // same: no-op
        assertEquals(3, repo.addonCount())

        repo.removeAddonAt(10) // no-op
        repo.removeAddonAt(0)
        assertEquals(2, repo.addonCount())

        repo.clearAll()
        assertTrue(repo.loadConfiguredAddons().isEmpty())
    }

    @Test fun exportImportRoundtrip() {
        val prefs = FakePrefs()
        val repo = StremioRepository(prefs)
        repo.addAddon("https://a.com/manifest.json")
        repo.addAddon("https://b.com/manifest.json")
        val json = repo.exportJson()
        assertTrue(json.contains("a.com"))

        val fresh = StremioRepository(FakePrefs())
        assertEquals(2, fresh.importJson(json))
        assertEquals(2, fresh.addonCount())
        assertEquals(0, fresh.importJson(json)) // re-import: nothing new
    }

    @Test fun importRejectsOversizeAndMalformed() {
        val repo = StremioRepository(FakePrefs())
        assertEquals(-1, repo.importJson("{bad"))
        assertEquals(0, repo.importJson("[]"))
        assertEquals(0, repo.importJson("""[{"url":"not a url"}]"""))
        val big = "x".repeat(256 * 1024 + 1)
        assertEquals(-1, repo.importJson(big))
        assertTrue(repo.loadConfiguredAddons().isEmpty())
    }

    @Test fun importCapsAt500() {
        val repo = StremioRepository(FakePrefs())
        val many = (0 until 501).joinToString(",", "[", "]") { """{"url":"https://h$it.com/manifest.json"}""" }
        assertEquals(-1, repo.importJson(many))
        assertTrue(repo.loadConfiguredAddons().isEmpty())
    }

    @Test fun singleObjectIsAutoWrapped() {
        val repo = StremioRepository(FakePrefs())
        assertEquals(1, repo.importJson("""{"url":"https://solo.com/manifest.json","name":"Solo"}"""))
        assertEquals("Solo", repo.loadConfiguredAddons().first().name)
    }

    @Test fun legacySequentialKeysMigrate() {
        val prefs = FakePrefs()
        prefs.edit().putString("stremio_addon", "https://old.com/manifest.json").apply()
        prefs.edit().putString("stremio_addon2", "https://old2.com").apply()
        val repo = StremioRepository(prefs)
        val loaded = repo.loadConfiguredAddons()
        assertEquals(2, loaded.size)
        // Legacy keys cleared after migration.
        assertFalse(prefs.contains("stremio_addon"))
    }

    @Test fun manifestUrlFieldFallback() {
        val prefs = FakePrefs()
        prefs.edit().putString(StremioConstants.KEY_ADDONS, """[{"manifestUrl":"https://f.com/manifest.json","enabled":true}]""").apply()
        val repo = StremioRepository(prefs)
        assertEquals(1, repo.loadConfiguredAddons().size)
    }
}
