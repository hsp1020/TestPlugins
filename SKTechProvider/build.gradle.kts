// use an integer for version numbers
version = 21

android {
    buildFeatures {
        buildConfig = true
        viewBinding = false // 불필요해진 설정 화면 뷰바인딩을 비활성화하여 빌드 경량화
    }
}
dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "ta"
    description = "Watch Live sports via SK Tech"
    authors = listOf("HSP1020")
    status = 1 
    tvTypes = listOf(
        "Live",
    )
    requiresResources = false // 불필요한 레이아웃 리소스를 참조하지 않도록 변경
    iconUrl = "https://appteka.store/get/a20p17EuCKFD08AaFsfH3JSJ6OU30ccp18WrKaNDze4LqOsDArhKj13-f-SjAayiWJH6P6pv9AlzhcfzEXlmRokU-I4=/726bbb91e36da425f55efce72cccb3a82df1d265.png"
}
