package com.stremiouniversal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class NormalizeAddonUrlTest {
    @Test fun `null and blank return null`() {
        assertNull(normalizeAddonUrl(null))
        assertNull(normalizeAddonUrl(""))
        assertNull(normalizeAddonUrl("   "))
    }
    @Test fun `stremio scheme converts and appends manifest`() {
        assertEquals("https://example.com/addon/manifest.json", normalizeAddonUrl("stremio://example.com/addon"))
        assertEquals("https://example.com/addon/manifest.json", normalizeAddonUrl("STREMIO://example.com/addon"))
        assertEquals("https://example.com/manifest.json", normalizeAddonUrl("stremio://example.com/manifest.json"))
        assertEquals("https://example.com/manifest.json?token=abc", normalizeAddonUrl("stremio://example.com/manifest.json?token=abc"))
    }
    @Test fun `bare host gets manifest appended`() {
        assertEquals("https://example.com/manifest.json", normalizeAddonUrl("https://example.com"))
        assertEquals("https://example.com/manifest.json", normalizeAddonUrl("https://example.com/"))
        assertEquals("https://example.com/addon/manifest.json", normalizeAddonUrl("https://example.com/addon"))
        assertEquals("http://localhost:8080/manifest.json", normalizeAddonUrl("http://localhost:8080"))
    }
    @Test fun `pipe prefix stripped`() {
        assertEquals("https://example.com/manifest.json", normalizeAddonUrl("My Addon|https://example.com/manifest.json"))
        assertEquals("https://example.com/addon/manifest.json", normalizeAddonUrl("My Addon|https://example.com/addon"))
    }
    @Test fun `query preserved`() {
        assertEquals("https://example.com/manifest.json?token=abc", normalizeAddonUrl("https://example.com/manifest.json?token=abc"))
        assertEquals("https://example.com/addon/manifest.json?token=abc", normalizeAddonUrl("https://example.com/addon?token=abc"))
    }
    @Test fun `non-http rejected`() {
        assertNull(normalizeAddonUrl("ftp://example.com/manifest.json"))
        assertNull(normalizeAddonUrl("example.com/manifest.json"))
        assertNull(normalizeAddonUrl("https://example.com/my addon/manifest.json"))
        assertTrue(normalizeAddonUrl("https://example.com") != null)
    }
    @Test fun `display host extracts bare host`() {
        assertEquals("v3-cinemeta.strem.io", addonDisplayHost("https://v3-cinemeta.strem.io/manifest.json"))
        assertEquals("example.com", addonDisplayHost("https://example.com/base/manifest.json?token=abc"))
    }
    @Test fun `dedupe by base`() {
        assertEquals(addonBaseKey("https://host.example/addon"), addonBaseKey("https://host.example/addon/manifest.json"))
    }
}
