// use an integer for version numbers
version = 1


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Live TV from DaddyLive. You might need a VPN"
    authors = listOf("hsp1020")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf("Live")

    requiresResources = false

    iconUrl = "https://raw.githubusercontent.com/hsp1020/TestPlugins/refs/heads/master/DaddyLive/live-streaming.png"
}
//dependencies{
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
//}
