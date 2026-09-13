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
    fun `stream text stays verbatim like stremio clients`() {
        val same = toStreamLink(
            StremioStream(name = "DesiFlix", title = "480p • Server 1", url = "https://cdn.example/x.mp4"),
            "DesiFlix",
            0
        )!!
        assertEquals("DesiFlix", same.source)
        assertEquals("480p • Server 1", same.title)
        val headed = toStreamLink(
            StremioStream(name = "1080p", title = "Affordable encode", url = "https://cdn.example/y.mp4"),
            "SomeAddon",
            0
        )!!
        assertEquals("1080p • Affordable encode", headed.title)
    }

    @Test
    fun `sort prefers resolution then seeders then addon order`() {
        fun link(url: String, rank: Int, seeders: Int, order: Int) =
            StreamLink(url, "s", "t", null, emptyMap(), rank, seeders, order)
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
    fun `dedupe is per addon like clients group streams`() {
        fun link(url: String, order: Int) =
            StreamLink(url, "s", "t", null, emptyMap(), 2, 0, order, null)
        val out = sortAndDedupe(
            listOf(
                link("https://c.example/f.mp4", 0),
                link("https://c.example/f.mp4", 0),
                link("https://c.example/f.mp4", 1)
            )
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `transport urls normalize like clients`() {
        assertEquals("https://a.example/addon", manifestBase("stremio://a.example/addon/manifest.json"))
        assertEquals("https://a.example/addon", manifestBase("https://a.example/addon/stream/"))
        assertEquals("https://a.example/addon", manifestBase("https://a.example/addon/catalog?token=x"))
        assertEquals(listOf("series"), streamTypesFor("tv"))
        assertEquals(listOf("series"), streamTypesFor("show"))
    }

    @Test
    fun `headers merge with proxy winning and kodi suffix parsed`() {
        val link = toStreamLink(
            StremioStream(
                url = "https://cdn.example/x.mp4|Referer=https://kodi.example/&X-A=1",
                headers = mapOf("Referer" to "https://stream.example/", "X-A" to "0"),
                behaviorHints = BehaviorHints(headers = mapOf("X-B" to "2"))
            ),
            "A",
            0
        )!!
        assertEquals("https://cdn.example/x.mp4", link.url)
        assertEquals("https://kodi.example/", link.headers["Referer"])
        assertEquals("1", link.headers["X-A"])
        assertEquals("2", link.headers["X-B"])
        val kept = toStreamLink(
            StremioStream(url = "https://cdn.example/a|b/c.mp4"),
            "A",
            0
        )!!
        assertEquals("https://cdn.example/a|b/c.mp4", kept.url)
        assertTrue(kept.headers.isEmpty())
    }

    @Test
    fun `dht sources join magnets like core`() {
        val magnet = buildMagnet("c".repeat(40), "N", listOf("dht:aaaabbbbcccc", "tracker:udp://t.example:1/x"))
        assertTrue(magnet!!.contains("tr=aaaabbbbcccc"))
        assertTrue(magnet.contains("tr=udp%3A%2F%2Ft.example%3A1%2Fx"))
    }
    @Test
    fun `dedupe keeps multi-file torrents apart and drops same-addon dups`() {
        fun link(url: String, fileIdx: Int? = null) =
            StreamLink(url, "s", "t", null, emptyMap(), 2, 0, 0, fileIdx)
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
        val entry = extractMetaEntry(
            """{"id":"tt0111161","name":"The Shawshank Redemption","description":"A banker is sentenced.",
               "genres":"Drama, Crime","cast":["Tim Robbins"],"imdbRating":9.3,"year":1994}""",
            "tt0111161"
        )!!
        assertTrue(matchesQuery(entry, "shawshank"))
        assertTrue(matchesQuery(entry, "tim robbins drama"))
        assertFalse(matchesQuery(entry, "zzzqqqnomatch"))
        assertFalse(matchesQuery(entry, ""))
    }

    @Test
    fun `bare meta objects and string fields parse`() {
        val bare = extractMetaEntry(
            """{"id":"tmdb:537061","type":"movie","name":"Steven Universe: The Movie",
               "genres":"Animation, Family","cast":"Zach Callison, Estelle","imdbRating":8.196,"year":2019}""",
            "tmdb:537061"
        )!!
        assertEquals("Steven Universe: The Movie", bare.name)
        assertEquals(listOf("Animation", "Family"), stringList(bare.genres))
        assertEquals(listOf("Zach Callison", "Estelle"), stringList(bare.cast))
        assertEquals(8.196, bare.imdbRating?.asText()?.toDoubleOrNull() ?: 0.0, 0.001)
        assertNull(extractMetaEntry("""{"error":{"message":"nope"}}""", "x"))
        assertNull(extractMetaEntry("not json", "x"))
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
    fun `link ref survives json round trip`() {
        val ref = LinkRef("https://addon.example", "series", "dsx:e:abc:1:2")
        val back = parseLinkRef(ref.toJsonString())
        assertEquals(ref, back)
        assertNull(parseLinkRef("not json"))
    }

    @Test
    fun `addon lines accept bare urls and legacy names`() {
        val parsed = parseAddonLines(
            listOf(
                "https://manifest.desitvhub.eu.org/manifest.json",
                "DesiFlix|https://manifest.desitvhub.eu.org/manifest.json",
                "  ",
                "notaurl"
            )
        )
        assertEquals(2, parsed.size)
        assertEquals("", parsed[0].name)
        assertEquals("https://manifest.desitvhub.eu.org/manifest.json", parsed[0].manifestUrl)
        assertEquals("DesiFlix", parsed[1].name)
        assertEquals("https://manifest.desitvhub.eu.org/manifest.json", displayAddonLine(parsed[0]))
        assertEquals(
            "DesiFlix|https://manifest.desitvhub.eu.org/manifest.json",
            displayAddonLine(parsed[1])
        )
    }

    @Test
    fun `addon headers pass through verbatim without injection`() {
        val stream = StremioStream(
            name = "DesiFlix",
            title = "480p",
            url = "https://cdn.example/x.mp4",
            behaviorHints = BehaviorHints(
                proxyHeaders = ProxyHeaders(
                    mapOf("Referer" to "https://www.gillitv.xyz/", "Authorization" to "Bearer abc")
                )
            )
        )
        val link = toStreamLink(stream, "DesiFlix", 0)!!
        assertEquals("https://www.gillitv.xyz/", link.headers["Referer"])
        assertEquals("Bearer abc", link.headers["Authorization"])
        assertFalse(link.headers.keys.any { it.equals("User-Agent", ignoreCase = true) })
    }

    @Test
    fun `stream types expand per content kind`() {
        assertEquals(listOf("movie"), streamTypesFor("movie"))
        assertEquals(listOf("series"), streamTypesFor("series"))
        assertEquals(listOf("other", "movie", "series"), streamTypesFor("other"))
    }
}
