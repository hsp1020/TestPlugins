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
    // UI 생성을 위한 Material 라이브러리 추가 (부모 파일에는 없으므로 여기서만 추가)
    implementation("com.google.android.material:material:1.13.0")

    val cloudstream by configurations
    
}
