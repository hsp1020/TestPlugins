version = 10

cloudstream {
    description = "Encrypt and synchronize CloudStream settings and watch history through a private GitHub repository"
    authors = listOf("hsp1020")
    language = "ko"

    status = 1
    tvTypes = listOf("Other")
    iconUrl = "https://raw.githubusercontent.com/hsp1020/TestPlugins/refs/heads/master/SyncCS/cloud-sync.png"
    requiresResources = false
}

android {
    namespace = "com.SyncCS"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
