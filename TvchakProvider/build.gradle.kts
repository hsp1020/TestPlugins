version = 2

cloudstream {
    description = "한국 드라마 및 TV 프로그램 다시보기 제공"
    authors = listOf("hsp1020") // 본인 계정명으로 변경
    language = "ko"
    
    status = 1 // 0: Down, 1: Ok, 2: Slow, 3: Beta
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "AsianDrama",
        "Anime"
    )
    
    iconUrl = "https://raw.githubusercontent.com/hsp1020/TestPlugins/refs/heads/master/TvchakProvider/logo.png"
}

android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("SharedPiP/src/main/kotlin"))
}

dependencies {
    // Provided at runtime by CloudStream; do not bundle another copy.
    compileOnly("androidx.media3:media3-ui:1.9.3")
}
