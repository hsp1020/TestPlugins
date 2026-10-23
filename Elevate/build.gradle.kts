version = 1

cloudstream {
    description = "Enhance CloudStream with native detail search and global PiP subtitles"
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
    compileOnly("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.4.0")
}
