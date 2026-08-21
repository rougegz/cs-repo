// Use an integer for version numbers
version = 1

cloudstream {
    description = """
        Short-drama aggregator powered by v-drama.net.
        Home categories: DramaBox, ReelShort, FreeReels, Youdrama, Hishort, Meloshort,
        Sodareels, Dramamax, NetShort, MoboReels, iDrama, Pinedrama, ShortMax, DramaBite,
        Flareflow, WeTV, iQIYI, DramaNova, Melolo, StarShort — each with endless scrolling.
        Change the site domain from Settings > Plugins > VDrama (gear icon).
    """.trimIndent()
    authors = listOf("vdrama-cloudstream")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf(
        "AsianDrama",
        "Movie",
    )

    language = "en"

    // Replace with your own icon if you want
    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/2/2f/Korduene_Logo.png"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
