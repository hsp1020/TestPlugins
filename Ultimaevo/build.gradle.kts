version = 1

cloudstream {
    description = "All in one Catalogs"
    authors = listOf("hsp1020") // 본인 계정명으로 변경
    language = "ko"
    
    status = 1 // 0: Down, 1: Ok, 2: Slow, 3: Beta
    tvTypes = listOf(
        "All",
        "Other"
    )
    
    iconUrl = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/Icons/UltimaBeta.png"
}

dependencies {
    val cloudstreamApi = "com.github.recloudstream:cloudstream:pre-release"
    compileOnly(cloudstreamApi)
    
    // [UI 컴파일을 위한 필수 의존성 추가]
    compileOnly("androidx.appcompat:appcompat:1.6.1")
    compileOnly("com.google.android.material:material:1.11.0")
}
