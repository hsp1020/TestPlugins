// 버전 정보: v1.4 (res.apk 누락 에러 원인 설명 정정 및 우회 로직 유지)
import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

println("[Debug] build.gradle.kts 스크립트 실행 - v1.4")

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
        // 기존부터 사용하던 AGP 8.x 환경 유지 (R8 버그 방지를 위해 8.8.2로 상향한 상태 유지)
        classpath("com.android.tools.build:gradle:8.8.2")
        classpath("com.github.recloudstream:gradle:81b1d424d2")
        
        // 메타데이터 컴파일 에러 해결을 위해 맞춘 Kotlin 2.3.0 유지
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
        setRepo("hsp1020/TestPlugins-build", "main") // 뒤에 쉼표를 찍고 "main"을 강제로 지정해 줍니다!setRepo("hsp1020/TestPlugins-build") 
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

        // UI 컴파일 에러 해결을 위한 AndroidX 및 Material 라이브러리 추가
        implementation("androidx.core:core-ktx:1.12.0")
        implementation("androidx.appcompat:appcompat:1.6.1")
        implementation("androidx.fragment:fragment-ktx:1.6.2")
        implementation("androidx.recyclerview:recyclerview:1.3.2")
        implementation("com.google.android.material:material:1.11.0")
        implementation("androidx.preference:preference-ktx:1.2.1")

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
        implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.2") // NewPipe Extractor
    }

    // v1.4 우회 로직: 컴파일이 정상적으로 뚫린 후 Cloudstream make 태스크에서 발생하는 AGP 8.x 종특(res.apk 누락) 에러 방지
    tasks.configureEach {
        if (name.contains("make")) {
            doFirst {
                val resApk = file("build/intermediates/res.apk")
                if (!resApk.exists()) {
                    println("[Debug] ${project.name} - make 태스크 진입 전 res.apk 존재 여부 확인: 없음")
                    println("[Debug] ${project.name} - Cloudstream 압축 해제 에러 우회를 위한 빈 Zip 파일 임시 생성 (${resApk.absolutePath})")
                    resApk.parentFile.mkdirs()
                    java.util.zip.ZipOutputStream(java.io.FileOutputStream(resApk)).use { zout ->
                        zout.putNextEntry(java.util.zip.ZipEntry("dummy.txt"))
                        zout.write("dummy".toByteArray())
                        zout.closeEntry()
                    }
                } else {
                    println("[Debug] ${project.name} - make 태스크 진입 전 res.apk 존재 여부 확인: 정상 존재함")
                }
            }
        }
    }
}

task<Delete>("clean") {
    println("[Debug] clean 태스크 구성")
    delete(rootProject.layout.buildDirectory)
}
