// v1.4
plugins {
    id("com.android.application")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    name = "Ultima Evo"
    description = "Advanced custom catalog manager for Cloudstream"
    authors = listOf("Developer")
    versionName = "1.0.0"
    versionCode = 1
}

android {
    namespace = "com.example.ultimaevo"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    val cloudstreamApi = "com.github.recloudstream:cloudstream:pre-release"
    compileOnly(cloudstreamApi)
    
    implementation("androidx.preference:preference-ktx:1.2.1")
}
