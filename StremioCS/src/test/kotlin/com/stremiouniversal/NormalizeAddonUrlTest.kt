package com.stremiouniversal

import org.junit.Assert.*
import org.junit.Test

class NormalizeAddonUrlTest {

    @Test fun nullAndBlankAreRejected() {
        assertNull(normalizeAddonUrl(null))
        assertNull(normalizeAddonUrl(""))
        assertNull(normalizeAddonUrl("   "))
    }

    @Test fun surroundingWhitespaceIsTrimmed() {
        assertEquals("https://foo.com/manifest.json", normalizeAddonUrl("  https://foo.com/manifest.json  "))
    }

    @Test fun stremioSchemeBecomesHttpsCaseInsensitive() {
        assertEquals("https://foo.com/manifest.json", normalizeAddonUrl("stremio://foo.com/manifest.json"))
        assertEquals("https://foo.com/manifest.json", normalizeAddonUrl("STREMIO://foo.com/manifest.json"))
    }

    @Test fun pipeSeparatedPicksTheUrlPart() {
        assertEquals("https://foo.com/manifest.json", normalizeAddonUrl("My Addon | https://foo.com/manifest.json"))
        assertEquals("https://foo.com/manifest.json", normalizeAddonUrl("https://foo.com/manifest.json | My Addon"))
        assertEquals("https://foo.com/manifest.json", normalizeAddonUrl("Name|stremio://foo.com"))
    }

    @Test fun pipeWithoutUrlIsRejected() {
        assertNull(normalizeAddonUrl("foo|bar"))
    }

    @Test fun bareBaseGetsManifestAppended() {
        assertEquals("https://foo.com/manifest.json", normalizeAddonUrl("https://foo.com"))
        assertEquals("https://foo.com/manifest.json", normalizeAddonUrl("https://foo.com/"))
    }

    @Test fun manifestAndConfigureAreLeftAlone() {
        assertEquals("https://foo.com/manifest.json", normalizeAddonUrl("https://foo.com/manifest.json"))
        assertEquals("https://foo.com/configure", normalizeAddonUrl("https://foo.com/configure"))
    }

    @Test fun queryIsPreserved() {
        assertEquals("https://foo.com/manifest.json?token=abc", normalizeAddonUrl("https://foo.com/manifest.json?token=abc"))
        assertEquals("https://foo.com/manifest.json?token=abc", normalizeAddonUrl("https://foo.com?token=abc"))
    }

    @Test fun nonHttpIsRejected() {
        assertNull(normalizeAddonUrl("ftp://foo.com/manifest.json"))
        assertNull(normalizeAddonUrl("foo.com/manifest.json"))
        assertNull(normalizeAddonUrl("magnet:?xt=urn:btih:abc"))
    }

    @Test fun upperCaseSchemeIsAccepted() {
        assertEquals("https://foo.com/manifest.json", normalizeAddonUrl("HTTPS://foo.com/manifest.json"))
    }

    @Test fun innerWhitespaceIsRejected() {
        assertNull(normalizeAddonUrl("https://foo .com/manifest.json"))
        assertNull(normalizeAddonUrl("https://foo.com/mani fest.json"))
    }

    @Test fun over2048BaseIsRejected() {
        assertNull(normalizeAddonUrl("https://a.com/" + "x".repeat(2100)))
    }

    @Test fun baseKeyIgnoresCaseAndQuery() {
        assertEquals(
            addonBaseKey("https://foo.com/manifest.json?token=a"),
            addonBaseKey("https://FOO.com/manifest.json?token=b")
        )
    }

    @Test fun manifestBaseStripsOnlyManifest() {
        assertEquals("https://foo.com", manifestBase("https://foo.com/manifest.json"))
        assertEquals("https://foo.com", manifestBase("stremio://foo.com/manifest.json"))
        assertEquals("https://foo.com", manifestBase("https://foo.com/manifest.json?token=x"))
        // Must NOT strip legit /stream or /catalog suffixes.
        assertEquals("https://foo.com/stream", manifestBase("https://foo.com/stream"))
    }

    @Test fun manifestQueryRoundtrip() {
        assertEquals("", manifestQuery("https://foo.com/manifest.json"))
        assertEquals("?token=x", manifestQuery("https://foo.com/manifest.json?token=x"))
    }

    @Test fun compatAliasesDelegate() {
        assertTrue(isValidManifestUrl("https://foo.com/manifest.json"))
        assertFalse(isValidManifestUrl("not a url"))
        assertEquals("https://foo.com/manifest.json", parseAddonUrl("https://foo.com"))
        assertNull(parseAddonUrl("foo|bar"))
    }

    @Test fun querySuffixValidation() {
        assertTrue("".isValidQuerySuffix())
        assertTrue("?token=abc".isValidQuerySuffix())
        assertFalse("token=abc".isValidQuerySuffix())
        assertFalse("?has space".isValidQuerySuffix())
    }
}
