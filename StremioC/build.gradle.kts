


version = 1

android {
    namespace = "com.hsp1020" 


    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    compileOnly("io.coil-kt.coil3:coil:3.3.0")
    compileOnly("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    testImplementation("junit:junit:4.13.2")
    implementation("com.google.android.material:material:1.13.0")
    compileOnly("androidx.navigation:navigation-common:2.9.8")
    compileOnly("androidx.navigation:navigation-fragment:2.9.8")

    compileOnly("androidx.media3:media3-ui:1.9.3")
    compileOnly("androidx.media3:media3-extractor:1.9.3")
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
