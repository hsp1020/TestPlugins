// 버전 정보: v1.2 (AGP 8.8.2 및 Kotlin 2.3.0 상향, 디버깅 로그 유지)
import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

println("[Debug] build.gradle.kts 스크립트 실행 - v1.2")

buildscript {
    println("[Debug] buildscript 저장소 설정 시작")
    repositories {
        google()
        mavenCentral()
        // Shitpack repo which contains our tools and dependencies
        maven("https://jitpack.io")
    }

    dependencies {
        println("[Debug] buildscript 의존성 설정 시작")
        // v1.2 수정: 최신 Kotlin 지원 및 R8 버그 수정을 위해 AGP 상향
        classpath("com.android.tools.build:gradle:8.8.2")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        
        // v1.2 수정: cloudstream.jar(2.3.0) 및 기타 의존성의 메타데이터 버전에 맞춰 Kotlin 상향
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    println("[Debug] allprojects 저장소 설정 시작")
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    println("[Debug] ${project.name} 하위 프로젝트 설정 시작")
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        println("[Debug] ${project.name} cloudstream 확장 설정")
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "user/repo")
    }

    android {
        println("[Debug] ${project.name} android 확장 설정")
        namespace = "com.example"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            println("[Debug] ${project.name} compileOptions 설정")
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            println("[Debug] KotlinJvmCompile 태스크 옵션 설정")
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8) // Required
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        println("[Debug] ${project.name} dependencies 설정")
        val cloudstream by configurations
        val implementation by configurations

        // Stubs for all cloudstream classes
        cloudstream("com.github.recloudstream:cloudstream:pre-release")

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.16")
        implementation("org.jsoup:jsoup:1.22.1")
        implementation("androidx.annotation:annotation:1.9.1")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        implementation("org.mozilla:rhino:1.9.0")
        implementation("me.xdrop:fuzzywuzzy:1.4.0")
        implementation("com.google.code.gson:gson:2.13.2")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        implementation("com.github.vidstige:jadb:v1.2.1")
        implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
        implementation("com.github.teamnewpipe:NewPipeExtractor:v0.25.2") // NewPipe Extractor
    }
}

task<Delete>("clean") {
    println("[Debug] clean 태스크 구성")
    delete(rootProject.layout.buildDirectory)
}
