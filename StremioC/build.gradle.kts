
// v2.0
import java.util.Properties

// use an integer for version numbers
version = 17

android {
    namespace = "com.hsp1020" // Gradle 8.0+ 필수 추가 (이 코드가 없으면 BuildConfig가 생성되지 않음)
    // Include the same extractor source in StremioC's own classes.dex.
    sourceSets.getByName("main").java.srcDir(rootProject.file("CommonLogic/src/main/kotlin"))

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val propFile = project.rootProject.file("local.properties")
        
        val tmdbApiKey = if (propFile.exists()) {
            val properties = Properties()
            properties.load(propFile.inputStream())
            properties.getProperty("TMDB_API") ?: ""
        } else {
            System.getenv("TMDB_API") ?: ""
        }
        
        android.buildFeatures.buildConfig = true
        buildConfigField("String", "TMDB_API", "\"$tmdbApiKey\"")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    compileOnly("androidx.navigation:navigation-common:2.9.8")
    compileOnly("androidx.navigation:navigation-fragment:2.9.8")
    // Compile against the host's Media3 SubtitleView without packaging another copy.
    compileOnly("androidx.media3:media3-ui:1.9.3")
    compileOnly("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.4.0")
    // Supplied by the CloudStream host; do not bundle another runtime copy.
    compileOnly("com.facebook.shimmer:shimmer:0.5.0")
}

cloudstream {
    language = "en"
   
 // All of these properties are optional, you can safely remove them

     description = "StremioC Allow you to use all addons"
     authors = listOf("hsp1020")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 
// will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )
    requiresResources = true
    iconUrl = "https://raw.githubusercontent.com/hsp1020/TestPlugins/0dfe09d8d6df0f32fd8369530187538114e94fd7/StremioC/stremio.png"
}
