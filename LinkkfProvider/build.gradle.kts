version = 2

cloudstream {
    description = "애니메이션 한글 자막 제공"
    authors = listOf("hsp1020") // 본인 계정명으로 변경
    language = "ko"
    
    status = 1 // 0: Down, 1: Ok, 2: Slow, 3: Beta
    tvTypes = listOf(
        "Anime"
    )
    
    iconUrl = "https://raw.githubusercontent.com/hsp1020/TestPlugins/refs/heads/master/LinkkfProvider/linktv.png"
}

android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("SharedPiP/src/main/kotlin"))
}

dependencies {
    // Provided at runtime by CloudStream; do not bundle another copy.
    compileOnly("androidx.media3:media3-ui:1.9.3")
}
