

import java.util.Properties

version = 17

android {
    namespace = "com.hsp1020" 

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

        buildConfigField("String", "TMDB_API", "\"$tmdbApiKey\"")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    compileOnly("androidx.navigation:navigation-common:2.9.8")
    compileOnly("androidx.navigation:navigation-fragment:2.9.8")

    compileOnly("androidx.media3:media3-ui:1.9.3")
    compileOnly("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.4.0")

    compileOnly("com.facebook.shimmer:shimmer:0.5.0")
}

cloudstream {
    language = "en"

     description = "StremioC Allow you to use all addons"
     authors = listOf("hsp1020")

    status = 1 

    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )
    requiresResources = true
    iconUrl = "https://raw.githubusercontent.com/hsp1020/TestPlugins/0dfe09d8d6df0f32fd8369530187538114e94fd7/StremioC/stremio.png"
}
