import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Narto is a separate extension that shares the same root build logic
// cloudstream block is defined here per-extension

cloudstream {
    description = """
        Narto Drama — edge.narto-drama.com aggregator.
        35 providers: BibiShort, BiliTV, CubeTV, DotDrama, Dramabite, DramaBox, DramaNova, DramaWave, FlareFlow, FlexTV, FlickReels, FreeReels, FunDrama, GoodShort, HappyShort, iDrama, JoyReels, KalosTV, Melolo, MicroDrama, MoboReels, NetShort, PineDrama, RapidTV, ReelBuzz, Reelife, ReelShort, Sereal+, Shortical, ShortMax, StardustTV, StarShort, Velolo, Vigloo, Vyntage — each with unlimited scroll, combined subcategories (For You + Feed), quality/subs/audio, Cloudflare + domain switch.
    """.trimIndent()
    authors = listOf("rougegz")
    status = 1
    tvTypes = listOf("AsianDrama", "Movie")
    language = "en"
    iconUrl = "https://edge.narto-drama.com/favicon.ico"
}

android {
    namespace = "com.narto"
    compileSdk = 36
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll(listOf("-Xno-call-assertions","-Xno-param-assertions","-Xno-receiver-assertions"))
    }
}
