package it.dogior.hadEnough

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** QuickJS P0-0 through P0-3 diagnostics, isolated from Rhino scraper execution. */
internal object NuvioQuickJsProbe {
    private const val TAG = "NuvioQuickJsProbe"
    private const val LIB = "libquickjs.so"
    private const val PATH_KEY = "com.dokar.quickjs.library.path"
    private const val NAME_KEY = "com.dokar.quickjs.library.name"

    suspend fun run(context: Context, pluginFilePath: String?, adapterTest: Boolean = false,
        hostTest: Boolean = false, realTest: NuvioQuickJsEvaluator.RealScraper? = null,
        batchTest: List<NuvioQuickJsEvaluator.RealScraper>? = null,
        onBatchProgress: (suspend (Int, Int, String) -> Unit)? = null): String =
        withContext(Dispatchers.IO) {
            val report = ArrayList<String>(5)
            report.add("QuickJS 1.0.5 / Android ${Build.VERSION.SDK_INT}")
            try {
                val cs3 = File(requireNotNull(pluginFilePath) { "Plugin file path is unavailable" })
                require(cs3.isFile) { "Plugin archive is missing" }
                val nativeFile = ZipFile(cs3).use { zip ->
                    val abi = Build.SUPPORTED_ABIS.firstOrNull { abi ->
                        zip.getEntry("lib/${abi}/$LIB") != null
                    } ?: error("No compatible QuickJS .so in .cs3 for ${Build.SUPPORTED_ABIS.joinToString()}")
                    val entry = requireNotNull(zip.getEntry("lib/${abi}/$LIB"))
                    val directory = File(context.codeCacheDir, "nuvio-quickjs/${abi}/${entry.crc.toString(16)}")
                    check(directory.isDirectory || directory.mkdirs()) { "Cannot create JNI cache directory" }
                    val target = File(directory, LIB)
                    if (!target.isFile || target.length() != entry.size) {
                        val temp = File(directory, "$LIB.tmp")
                        try {
                            zip.getInputStream(entry).use { input ->
                                temp.outputStream().use { output -> input.copyTo(output) }
                            }
                            check(temp.setReadOnly()) { "Cannot mark JNI binary read-only" }
                            if (target.exists()) check(target.delete()) { "Cannot replace JNI binary" }
                            check(temp.renameTo(target)) { "Cannot stage JNI binary" }
                        } finally {
                            temp.delete()
                        }
                    }
                    report.add("PACKAGE: PASS (ABI $abi, native .so present)")
                    target
                }

                // Check DEX class availability without initializing the native runtime.
                val quickJsClass = Class.forName("com.dokar.quickjs.QuickJs", false, NuvioQuickJsProbe::class.java.classLoader)
                check(quickJsClass.classLoader === NuvioQuickJsProbe::class.java.classLoader) {
                    "QuickJs class was loaded by the host instead of the plugin DEX"
                }
                report.add("DEX: PASS (QuickJs class bundled with plugin)")
                if (realTest != null) {
                    report.add("SCRAPER: ${realTest.name}")
                    report.add("INPUT: ${realTest.mediaType} TMDB ${realTest.tmdbId}" +
                        if (realTest.season != null) " S${realTest.season}E${realTest.episode}" else "")
                }

                val previousPath = System.getProperty(PATH_KEY)
                val previousName = System.getProperty(NAME_KEY)
                try {
                    // quickjs-kt's Android loader honors these properties (v1.0.5).
                    System.setProperty(PATH_KEY, nativeFile.parentFile!!.absolutePath)
                    System.setProperty(NAME_KEY, LIB)
                    val outcome = when {
                        batchTest != null -> NuvioQuickJsEvaluator.evaluateBatch(batchTest) { done, total, name ->
                            onBatchProgress?.invoke(done, total, name)
                        }
                        realTest != null -> NuvioQuickJsEvaluator.evaluateReal(realTest)
                        hostTest -> NuvioQuickJsEvaluator.evaluateHost()
                        adapterTest -> NuvioQuickJsEvaluator.evaluateAdapter()
                        else -> NuvioQuickJsEvaluator.evaluate()
                    }
                    if (realTest != null || batchTest != null) report.add(outcome)
                    else {
                        val label = if (hostTest) "HOST" else if (adapterTest) "ADAPTER" else "EXECUTION"
                        report.add("$label: PASS ($outcome)")
                    }
                } finally {
                    if (previousPath == null) System.clearProperty(PATH_KEY)
                    else System.setProperty(PATH_KEY, previousPath)
                    if (previousName == null) System.clearProperty(NAME_KEY)
                    else System.setProperty(NAME_KEY, previousName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                report.add("${if (batchTest != null) "BATCH" else if (realTest != null) "REAL" else "TEST"}: FAIL: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            }
            report.joinToString("\n").also { Log.i(TAG, it) }
        }
}
