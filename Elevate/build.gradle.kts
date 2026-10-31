version = 3

cloudstream {
    description = "Enhance CloudStream"
    authors = listOf("hsp1020")
    language = "en"
    status = 1
    tvTypes = listOf("Other")
}

dependencies {
    compileOnly("io.coil-kt.coil3:coil:3.3.0")
    compileOnly("androidx.navigation:navigation-common:2.9.8")
    compileOnly("androidx.navigation:navigation-fragment:2.9.8")
    compileOnly("androidx.media3:media3-ui:1.9.3")
    compileOnly("androidx.media3:media3-exoplayer:1.9.3")
    compileOnly("androidx.constraintlayout:constraintlayout:2.0.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.4.0")
}
