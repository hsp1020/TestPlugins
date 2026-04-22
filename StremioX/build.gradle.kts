// v2.0
import java.util.Properties

// use an integer for version numbers
version = 13

android {
    namespace = "com.hsp1020" // Gradle 8.0+ 필수 추가 (이 코드가 없으면 BuildConfig가 생성되지 않음)

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
}

cloudstream {
    language = "en"
   
 // All of these properties are optional, you can safely remove them

     description = "[!] Requires Setup \n- StremioX allows you to use stream addons \n- StremioC allows you to use catalog addons"
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
    iconUrl = "https://raw.githubusercontent.com/hexated/cloudstream-extensions-hexated/master/StremioX/icon.png"
}
