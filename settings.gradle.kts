// Kotlin 2.3 requires D8/R8 8.13.19+; keep the existing AGP version.
pluginManagement {
    buildscript {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools:r8:8.13.19")
        }
    }
}

rootProject.name = "CloudstreamPlugins"

// 모든 자동 감지 로직을 지우고 딱 님의 플러그인 폴더만 지정합니다.
include("TVMONProvider")
include("MoviekingProvider")
include("TVWikiProvider")
include("KotbcProvider")
include("TvchakProvider")
include("LinkkfProvider")
include("KKTVProvider")
include("CUTVProvider")
include("StremioC")
include("Elevate")
include("YouTube")
include("AnilifeProvider")
include("OHLI24")
include("Ultima Evo")
include("DaddyLive")
include("SyncCS")
include("Streamed")
include("Nuvio")
