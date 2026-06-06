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
    
    // ★★★ 이 옵션이 있어야 Cloudstream이 XML 리소스를 읽어옵니다! ★★★
    requiresResources = true 
}

android {
    namespace = "com.SyncCS"
}
