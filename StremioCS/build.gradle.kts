import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
version = 2
cloudstream {
    description = """
        StremioCS — every Stremio addon in one provider: all catalogues, all streams, search and subtitles, in your addon order. No built-in addons — add your own via Settings → Browse Addons (https://stremio-addons.net).
    """.trimIndent()
    authors = listOf("rougegz")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
    iconUrl = "https://raw.githubusercontent.com/Stremio/stremio-web/development/assets/images/stremio_symbol.png"
}
android {
    namespace = "com.stremiouniversal"
    compileSdk = 36
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
dependencies {
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.browser:browser:1.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20230618")
}
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll(listOf("-Xno-call-assertions","-Xno-param-assertions","-Xno-receiver-assertions"))
    }
}
