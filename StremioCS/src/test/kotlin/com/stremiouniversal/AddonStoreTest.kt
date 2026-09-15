package com.stremiouniversal
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
class AddonStoreTest {
    private lateinit var prefs: FakePrefs
    private lateinit var repo: StremioRepository
    @Before fun setUp() {
        prefs = FakePrefs()
        repo = StremioRepository(prefs)
    }
    @Test fun `missing key loads empty`() {
        assertTrue(repo.loadConfiguredAddons().isEmpty())
    }
    @Test fun `corrupt json loads empty`() {
        prefs.edit().putString(StremioConstants.KEY_ADDONS, "not-json{{{").apply()
        assertTrue(repo.loadConfiguredAddons().isEmpty())
    }
    @Test fun `fresh install loads empty`() {
        assertTrue(repo.loadConfiguredAddons().isEmpty())
        assertEquals(0, repo.addonCount())
    }
    @Test fun `add normalizes and dedupes`() {
        assertTrue(repo.addAddon("https://one.example/addon"))
        assertEquals("https://one.example/addon/manifest.json", repo.loadConfiguredAddons()[0].manifestUrl)
        assertFalse(repo.addAddon("https://one.example/addon/manifest.json"))
        assertFalse(repo.addAddon("ftp://bad.example/manifest.json"))
        assertEquals(1, repo.loadConfiguredAddons().size)
    }
    @Test fun `toggle filters enabled`() {
        repo.addAddon("https://one.example/manifest.json")
        repo.addAddon("https://two.example/manifest.json")
        repo.setAddonEnabled(0, false)
        assertEquals(1, repo.loadEnabledAddons().size)
        repo.setAddonEnabled(0, true)
        assertEquals(2, repo.loadEnabledAddons().size)
    }
    @Test fun `move reorders`() {
        repo.addAddon("https://a.example/manifest.json")
        repo.addAddon("https://b.example/manifest.json")
        repo.addAddon("https://c.example/manifest.json")
        repo.moveAddon(0, 2)
        assertEquals(
            listOf("https://b.example/manifest.json", "https://c.example/manifest.json", "https://a.example/manifest.json"),
            repo.loadConfiguredAddons().map { it.manifestUrl }
        )
    }
    @Test fun `export import roundtrip`() {
        repo.saveAddons(
            listOf(
                AddonConfig("One", "https://one.example/manifest.json", true),
                AddonConfig("Two", "https://two.example/manifest.json", false)
            )
        )
        val json = repo.exportJson()
        repo.clearAll()
        assertEquals(2, repo.importJson(json))
        assertEquals(-1, repo.importJson("{not json"))
        assertEquals(0, repo.importJson("[]"))
    }
    private class FakePrefs : SharedPreferences {
        private val data = HashMap<String, Any?>()
        override fun getAll(): Map<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            if (key == null || !data.containsKey(key)) defValue else data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int =
            if (key == null || !data.containsKey(key)) defValue else (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            if (key == null || !data.containsKey(key)) defValue else (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = key != null && data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(k: String?, v: String?): SharedPreferences.Editor { if (k != null) data[k] = v; return this }
            override fun putStringSet(k: String?, v: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(k: String?, v: Int): SharedPreferences.Editor { if (k != null) data[k] = v; return this }
            override fun putLong(k: String?, v: Long): SharedPreferences.Editor = this
            override fun putFloat(k: String?, v: Float): SharedPreferences.Editor = this
            override fun putBoolean(k: String?, v: Boolean): SharedPreferences.Editor { if (k != null) data[k] = v; return this }
            override fun remove(k: String?): SharedPreferences.Editor { data.remove(k); return this }
            override fun clear(): SharedPreferences.Editor { data.clear(); return this }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }
}
