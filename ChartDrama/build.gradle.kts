version = 1
cloudstream {
    description = """
        ChartDrama — 35 short-drama platforms via chartdrama.com.
        Home: Reelshort, Dramabox, GoodShort, DramaWave, NetShort, ShortMax, StardustTV, FreeReels, StarShort, ShotShort, DramaTV, 4Drama, FlexTV, Shorts, NovaFilck, ThisReels, SodaTV, KalosTV, MuVpix, Toonory, AuraReels, VenixTV, StarReel, LeapReels, TasteLife, FlareFlow, JoyReels, ZiptaleTV, Vyntage, SanpPlay, SwoopReels, Flikso, Plotify, Myrelle, Nebuluxe, Lunory — each endless.
        Domain switch + Cloudflare solve in Settings > Plugins > ChartDrama.
    """.trimIndent()
    authors = listOf("rougegz")
    status = 1
    tvTypes = listOf("AsianDrama", "Movie")
    language = "en"
    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/2/2f/Korduene_Logo.png"
}
android { buildFeatures { buildConfig = true } }
