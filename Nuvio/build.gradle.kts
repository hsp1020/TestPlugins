import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Zip
import java.util.zip.ZipFile

// use an integer for version numbers
version = 1

android {
    namespace = "it.dogior.hadEnough"

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    // Only for the isolated P0-0 probe. No QuickJS calls in the Rhino scraper path.
    compileOnly(files(layout.buildDirectory.file("quickjs-probe/classes.jar")))
}

// CloudStream make packages DEX and resources, not dependencies' JNI binaries.
// Stage QuickJS Kotlin classes and Android .so files into this module's .cs3.
val quickJsProbeAar = configurations.detachedConfiguration(
    dependencies.create("io.github.dokar3:quickjs-kt-android:1.0.5@aar"
).apply { isTransitive = false }

val stageQuickJsProbe by tasks.registering(Copy::class) {
    from({ zipTree(quickJsProbeAar.singleFile) }) {
        include("classes.jar", "jni/**/*.so")
    }
    into(layout.buildDirectory.dir("quickjs-probe"))
}

tasks.named("compileDebugKotlin") {
    dependsOn(stageQuickJsProbe)
}

tasks.named<CompileDexTask>("compileDex") {
    dependsOn(stageQuickJsProbe)
    input.from(layout.buildDirectory.file("quickjs-probe/classes.jar"))
}

tasks.named<Zip>("make") {
    dependsOn(stageQuickJsProbe)
    from(layout.buildDirectory.dir("quickjs-probe/jni")) {
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
