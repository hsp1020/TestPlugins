// Use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Watch Youtube in Cloudstream"
    authors = listOf("hsp1020")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Other", "Live", "TvSeries")
    iconUrl = "https://raw.githubusercontent.com/hsp1020/TestPlugins/refs/heads/master/Youtube/Youtube_logo.png"

    isCrossPlatform = false
}
