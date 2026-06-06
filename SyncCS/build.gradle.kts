version = 1

cloudstream {
    description = "Sync CloudStream"
    authors = listOf("hsp1020")
    language = "ko"
    
    status = 1 // 0: Down, 1: Ok, 2: Slow, 3: Beta
    tvTypes = listOf(
        "Other"
    )
    
    iconUrl = "https://raw.githubusercontent.com/hsp1020/TestPlugins/refs/heads/master/SyncCS/cloud-sync.png"
}

// ★★★ 이 부분이 추가되어야 XML 리소스가 정상적으로 연결됩니다 ★★★
android {
    namespace = "com.SyncCS"
}
