package com.hsp1020.elevate

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.StrictMode
import android.util.Log

/**
 * Manual Java-allocation-profile-only receiver leak evidence. Neither this tracer
 * nor its records retain Activity, Context, Receiver or ReceiverDispatcher objects.
 */
internal object ReceiverRegistrationTrace {
    private const val TAG = "ElevateReceiverLeak"
    private const val RUN_PROPERTY = "hsp1020.java.alloc.run"
    private var previousPolicy: StrictMode.VmPolicy? = null
    private var installedPolicy: StrictMode.VmPolicy? = null

    @Synchronized
    fun start(run: String) {
        if (installedPolicy != null) return
        try {
            val original = StrictMode.getVmPolicy()
            val diagnostic = StrictMode.VmPolicy.Builder(original)
                .detectLeakedRegistrationObjects().penaltyLog().build()
            StrictMode.setVmPolicy(diagnostic)
            previousPolicy = original
            installedPolicy = diagnostic
            Log.i(TAG, "run=$run phase=STRICTMODE_START detector=LEAKED_REGISTRATION " +
                "note=IntentReceiverLeaked_violations_include_registration_stack")
        } catch (error: Exception) {
            Log.w(TAG, "run=$run phase=STRICTMODE_UNAVAILABLE reason=${error.javaClass.simpleName}", error)
        }
    }

    @Synchronized
    fun stop() {
        val installed = installedPolicy ?: return
        try {
            // Do not overwrite another component's policy if it changed meanwhile.
            val restore = StrictMode.getVmPolicy() === installed
            if (restore) previousPolicy?.let { StrictMode.setVmPolicy(it) }
            Log.i(TAG, "phase=STRICTMODE_STOP restored=$restore")
        } catch (error: Exception) {
            Log.w(TAG, "phase=STRICTMODE_RESTORE_FAILED reason=${error.javaClass.simpleName}", error)
        } finally {
            previousPolicy = null
            installedPolicy = null
        }
    }

    /** Optional read-only LoadedApk snapshot; Android hidden API policy may block it. */
    fun snapshot(activity: Activity, phase: String) {
        val run = System.getProperty(RUN_PROPERTY) ?: return
        if (!activity.javaClass.name.endsWith(".DownloadedPlayerActivity")) return
        val activityId = System.identityHashCode(activity)
        try {
            val base = activity.baseContext ?: error("No base context")
            val loadedApk = runCatching { getField(base, "mPackageInfo") }.getOrNull()
                ?: getField(activity, "mLoadedApk") ?: error("No LoadedApk")
            val receiverContexts = getField(loadedApk, "mReceivers") as? Map<*, *>
                ?: error("No mReceivers map")
            data class ReceiverRecord(val klass: String, val id: Int, val contextId: Int,
                                      val stack: String)
            var matchingContexts = 0
            val records = synchronized(receiverContexts) {
                receiverContexts.entries.filter { entry ->
                    isActivityContext(entry.key, activity)
                }.flatMap { entry ->
                    matchingContexts++
                    val contextId = System.identityHashCode(entry.key)
                    val registrations = entry.value as? Map<*, *> ?: emptyMap<Any, Any>()
                    registrations.entries.map { reg ->
                        val receiver = reg.key
                        val stack = try {
                            val location = reg.value?.let { getField(it, "mLocation") } as? Throwable
                            location?.stackTrace?.take(18)?.joinToString(" <- ") { it.toString() }
                                ?.take(2700) ?: "UNAVAILABLE_NO_LOCATION"
                        } catch (error: Exception) {
                            "UNAVAILABLE_${error.javaClass.simpleName}"
                        }
                        ReceiverRecord(receiver?.javaClass?.name ?: "null",
                            System.identityHashCode(receiver), contextId, stack)
                    }
                }
            }
            Log.i(TAG, "run=$run phase=RECEIVER_SNAPSHOT step=$phase " +
                "activity=${activity.javaClass.simpleName} activityId=$activityId " +
                "contextCount=$matchingContexts receiverCount=${records.size} " +
                "destroyed=${activity.isDestroyed}")
            records.take(32).forEach { item ->
                Log.i(TAG, "run=$run phase=RECEIVER_REGISTRATION step=$phase " +
                    "activityId=$activityId contextId=${item.contextId} " +
                    "receiverClass=${item.klass} receiverId=${item.id} " +
                    "registrationStack=${item.stack}")
            }
            if (records.size > 32)
                Log.i(TAG, "run=$run phase=RECEIVER_TRUNCATED activityId=$activityId " +
                    "remaining=${records.size - 32}")
        } catch (error: Exception) {
            // Hidden framework fields are not SDK APIs: this outcome is explicit,
            // while StrictMode's public detector remains enabled until Stop.
            Log.w(TAG, "run=$run phase=RECEIVER_SNAPSHOT_UNAVAILABLE step=$phase " +
                "activityId=$activityId reason=${error.javaClass.simpleName}", error)
        }
    }

    private fun isActivityContext(value: Any?, activity: Activity): Boolean {
        if (value === activity || value === activity.baseContext) return true
        var context = value as? Context
        repeat(6) {
            if (context === activity || context === activity.baseContext) return true
            context = (context as? ContextWrapper)?.baseContext ?: return false
        }
        return false
    }

    private fun getField(target: Any, name: String): Any? {
        var klass: Class<*>? = target.javaClass
        while (klass != null) {
            try {
                return klass.getDeclaredField(name).apply { isAccessible = true }.get(target)
            } catch (_: NoSuchFieldException) {
                klass = klass.superclass
            }
        }
        throw NoSuchFieldException("${target.javaClass.name}.$name")
    }
}
