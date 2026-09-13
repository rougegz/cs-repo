package com.stremiouniversal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioUtilsTest {

    @Test
    fun `resolution detects common tags`() {
        assertEquals("1080p" to 3, resolutionOf("1080p HEVC 2.3GB"))
        assertEquals("4K" to 5, resolutionOf("2160p WEB-DL"))
        assertEquals("720p" to 2, resolutionOf("720p HDTV"))
        assertEquals("CAM" to 0, resolutionOf("HDCAM hindi"))
    }

    @Test
    fun `unknown resolution ranks below sd`() {
        val (tag, rank) = resolutionOf("some random release")
        assertNull(tag)
        assertEquals(1, rank)
    }

    @Test
    fun `magnet requires 40 hex chars`() {
        val hash = "a".repeat(40)
        val magnet = buildMagnet(hash, "Big Buck Bunny", listOf("tracker:udp://t.example:1337/announce"))
        assertTrue(magnet!!.startsWith("magnet:?xt=urn:btih:$hash"))
        assertTrue(magnet.contains("dn=Big+Buck+Bunny"))
        assertTrue(magnet.contains("udp%3A%2F%2Ft.example%3A1337%2Fannounce"))
        assertNull(buildMagnet("xyz", "x", emptyList()))
        assertNull(buildMagnet(null, "x", emptyList()))
    }

    @Test
    fun `credential headers are stripped and ua added once`() {
        val out = sanitizeHeaders(
            mapOf("Authorization" to "Bearer s3cret", "Referer" to "https://cdn.example/"),
            mapOf("user-agent" to "Custom/1.0", "Cookie" to "a=b")
        )
        assertFalse(out.keys.any { it.equals("authorization", ignoreCase = true) })
        assertFalse(out.keys.any { it.equals("cookie", ignoreCase = true) })
        assertEquals("https://cdn.example/", out["Referer"])
        assertEquals(1, out.keys.count { it.equals("user-agent", ignoreCase = true) })
    }

    @Test
    fun `sort prefers resolution then seeders then addon order`() {
        fun link(url: String, rank: Int, seeders: Int, order: Int) =
            StreamLink(url, "s", null, emptyMap(), rank, seeders, order)
        val sorted = sortAndDedupe(
            listOf(
                link("https://c.example/low.mp4", 1, 999, 0),
                link("https://a.example/high.mp4", 3, 1, 2),
                link("https://b.example/high.mp4", 3, 50, 1)
            )
        ).map { it.url }
        assertEquals(
            listOf("https://b.example/high.mp4", "https://a.example/high.mp4", "https://c.example/low.mp4"),
            sorted
        )
    }

    @Test
    fun `dedupe keeps multi-file torrents apart and drops exact dups`() {
        fun link(url: String, fileIdx: Int? = null) =
            StreamLink(url, "s", null, emptyMap(), 2, 0, 0, fileIdx)
        val hash = "b".repeat(40)
        val out = sortAndDedupe(
            listOf(
                link("magnet:?xt=urn:btih:$hash", 0),
                link("magnet:?xt=urn:btih:$hash", 0),
                link("magnet:?xt=urn:btih:$hash", 1),
                link("https://c.example/f.mp4"),
                link("https://c.example/f.mp4#frag")
            )
        )
        assertEquals(3, out.size)
    }

    @Test
    fun `query matcher handles tokens and glued titles`() {
        val entry = CatalogEntry(
            name = "The Shawshank Redemption",
            id = "tt0111161",
            description = "A banker is sentenced.",
            genres = listOf("Drama"),
            cast = listOf("Tim Robbins")
        )
        assertTrue(matchesQuery(entry, "shawshank"))
        assertTrue(matchesQuery(entry, "tim robbins drama"))
        assertFalse(matchesQuery(entry, "zzzqqqnomatch"))
        assertFalse(matchesQuery(entry, ""))
    }

    @Test
    fun `manifest base and query split`() {
        assertEquals(
            "https://a.example/addon",
            manifestBase("https://a.example/addon/manifest.json")
        )
        assertEquals(
            "?token=secret",
            manifestQuery("https://a.example/addon/manifest.json?token=secret")
        )
        assertEquals("", manifestQuery("https://a.example/addon/manifest.json"))
    }

    @Test
    fun `content ids normalize`() {
        assertEquals("tt0111161", normalizeContentId("tt0111161"))
        assertEquals("tmdb:550", normalizeContentId("550"))
        assertEquals("ymzk-ep-120", normalizeContentId("ymzk-ep-120"))
    }

    @Test
    fun `stream types expand per content kind`() {
        assertEquals(listOf("movie"), streamTypesFor("movie"))
        assertEquals(listOf("series"), streamTypesFor("series"))
        assertEquals(listOf("other", "movie", "series"), streamTypesFor("other"))
    }
}
