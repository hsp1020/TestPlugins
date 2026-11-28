version = 10

cloudstream {
    description = "애니메이션 한글 자막 제공"
    authors = listOf("hsp1020")
    language = "ko"
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://anilife01.tv/favicon-32x32.png"
}

dependencies {
    compileOnly("io.coil-kt.coil3:coil:3.3.0")
}
