import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Zip
import java.util.zip.ZipFile
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    dependencies.create("io.github.dokar3:quickjs-kt-android:1.0.5@aar")
).apply { isTransitive = false }

val stageQuickJsProbe by tasks.registering(Copy::class) {
    from({ zipTree(quickJsProbeAar.singleFile) }) {
        include("classes.jar", "jni/**/*.so")
    }
    into(layout.buildDirectory.dir("quickjs-probe"))
}

tasks.matching { it.name == "compileDebugKotlin" }.configureEach {
    dependsOn(stageQuickJsProbe)
}

// Gradle's D8 (AGP 8.8.2) cannot rewrite all Kotlin 2.3.x library metadata.
// Keep the original AAR for Kotlin compilation; strip only the optional
// kotlin.Metadata annotations from the copy consumed by D8.
val quickJsDexJar = layout.buildDirectory.file("quickjs-probe/classes-dex.jar")
val prepareQuickJsDex by tasks.registering {
    dependsOn(stageQuickJsProbe)
    val inputJar = layout.buildDirectory.file("quickjs-probe/classes.jar")
    inputs.file(inputJar)
    outputs.file(quickJsDexJar)
    doLast {
        ZipFile(inputJar.get().asFile).use { source ->
            ZipOutputStream(quickJsDexJar.get().asFile.outputStream()).use { output ->
                val entries = source.entries()
                var classCount = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.name.endsWith(".class")) continue
                    val reader = ClassReader(source.getInputStream(entry).use { it.readBytes() })
                    val writer = ClassWriter(0)
                    reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? =
                            if (descriptor == "Lkotlin/Metadata;") null
                            else super.visitAnnotation(descriptor, visible)
                    }, 0)
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(writer.toByteArray())
                    output.closeEntry()
                    classCount++
                }
                check(classCount > 0) { "QuickJS AAR contains no classes" }
            }
        }
    }
}

tasks.named<CompileDexTask>("compileDex") {
    dependsOn(prepareQuickJsDex)
    input.from(quickJsDexJar)
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
