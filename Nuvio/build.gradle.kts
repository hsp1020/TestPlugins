import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Zip
import java.util.zip.ZipFile

// use an integer for version numbers
version = 1

android {
    namespace = "com.hsp1020"

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    // Compile against the bundled QuickJS runtime; preserve the Rhino fallback.
    compileOnly(files(layout.buildDirectory.file("quickjs-runtime/classes.jar")))
}

// CloudStream make packages DEX and resources, not dependencies' JNI binaries.
// Package QuickJS Kotlin classes and Android .so files into this module's .cs3.
val quickJsRuntimeAar = configurations.detachedConfiguration(
    dependencies.create("io.github.dokar3:quickjs-kt-android:1.0.5@aar")
).apply { isTransitive = false }

val stageQuickJsRuntime by tasks.registering(Copy::class) {
    from({ zipTree(quickJsRuntimeAar.singleFile) }) {
        include("classes.jar", "jni/**/*.so")
    }
    into(layout.buildDirectory.dir("quickjs-runtime"))
}

tasks.matching { it.name == "compileDebugKotlin" }.configureEach {
    dependsOn(stageQuickJsRuntime)
}

// CloudStream's DEX task closes JAR inputs before D8 reads their classes.
// Unpack only the .class files so D8 reads regular files instead of a closed ZIP.
val unpackQuickJsRuntimeClasses by tasks.registering(Copy::class) {
    dependsOn(stageQuickJsRuntime)
    from({ zipTree(layout.buildDirectory.file("quickjs-runtime/classes.jar").get().asFile) }) {
        include("**/*.class")
    }
    into(layout.buildDirectory.dir("quickjs-runtime-dex"))
}

tasks.named<CompileDexTask>("compileDex") {
    dependsOn(unpackQuickJsRuntimeClasses)
    input.from(layout.buildDirectory.dir("quickjs-runtime-dex"))
}

tasks.named<Zip>("make") {
    dependsOn(stageQuickJsRuntime)
    from(layout.buildDirectory.dir("quickjs-runtime/jni")) {
        into("lib")
    }
    doLast {
        ZipFile(archiveFile.get().asFile).use { zip ->
            check(zip.getEntry("lib/arm64-v8a/libquickjs.so") != null) {
                "QuickJS native library is missing from Nuvio.cs3"
            }
        }
    }
}

cloudstream {
    language = "en"
    description = "Use Nuvio repository scrapers (JavaScript) in CloudStream"
    authors = listOf("DieGon")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3

    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )

    // The settings dialog is built in code, no XML resources needed
    requiresResources = true
    iconUrl = "https://raw.githubusercontent.com/hsp1020/TestPlugins/refs/heads/master/Nuvio/nuvio_icon.png"
}
