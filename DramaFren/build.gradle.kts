// Use an integer for version numbers
version = 1

cloudstream {
    description = """
        DramaFren aggregator — api.dramafren.org + reelfren.dramafren.org (Cloudflare) — English.
        28 home categories: Melolo, Sereal+, PineDrama, Shorten, HappyShort, Vigloo, RaptDrama, CubeTV, JoyReels, AnyReel, MiniTV, Bstation, GoldDrama, Reelife, ReelShort, DramaBox, DramaNova, KalosTV, VibeShort, FreeReels, WeTV, StoryReel, MovieBox, MovieBox Shorts, MyDrama, FlareFlow, PlayLet, ShortMax — each endless, subcategories merged. Quality, subtitles, audio labels. Domain & Cloudflare solvable from settings.
    """.trimIndent()
    authors = listOf("rougegz")

    status = 1
    tvTypes = listOf(
        "AsianDrama",
        "Movie",
    )
    language = "en"
    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/2/2f/Korduene_Logo.png"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
