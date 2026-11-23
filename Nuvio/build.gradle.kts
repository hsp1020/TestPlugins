import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Zip
import java.util.zip.ZipFile

// use an integer for version numbers
version = 1

android {
    namespace = "com.hsp1020"
    ndkVersion = "27.2.12479018"

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    // Compile against the QuickJS runtime packaged into the plugin.
    compileOnly(files(layout.buildDirectory.file("quickjs-runtime/classes.jar")))
}

// Keep the original 1.0.5 Kotlin classes, but build the fixed JNI library from
// the pinned QuickJS-KT source. Do not ship the broken native .so from the AAR.
val quickJsRuntimeAar = configurations.detachedConfiguration(
    dependencies.create("io.github.dokar3:quickjs-kt-android:1.0.5@aar")
).apply { isTransitive = false }

val stageQuickJsRuntime by tasks.registering(Copy::class) {
    from({ zipTree(quickJsRuntimeAar.singleFile) }) {
        include("classes.jar")
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

// Only Nuvio's make task rebuilds the patched native code (all original ABIs).
val nativeJniDir = layout.buildDirectory.dir("quickjs-patched/jni")
val buildPatchedQuickJs by tasks.registering(Exec::class) {
    val script = file("native/build_quickjs.py")
    inputs.file(script)
    outputs.dir(nativeJniDir)
    commandLine("python3", script.absolutePath,
        layout.buildDirectory.dir("quickjs-patched").get().asFile.absolutePath)
}

tasks.named<Zip>("make") {
    dependsOn(stageQuickJsRuntime, buildPatchedQuickJs)
    from(nativeJniDir) {
        into("lib")
    }
    doLast {
        ZipFile(archiveFile.get().asFile).use { zip ->
            for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
                check(zip.getEntry("lib/$abi/libquickjs.so") != null) {
                    "Patched QuickJS native library missing for $abi"
                }
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
