import com.lagradost.cloudstream3.gradle.CloudstreamExtension

plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    description = "무비킹 - 최신영화, 드라마, 예능, 애니 다시보기"
    authors = listOf("User") // 작성자 이름
    language = "ko"
    
    status = 1 // 1: Ok
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "AsianDrama",
        "Anime"
    )
    
    iconUrl = "https://mvking6.org/resource/favicon.ico"
}

android {
    namespace = "com.movieking"
    defaultConfig {
        minSdk = 21
    }
}
