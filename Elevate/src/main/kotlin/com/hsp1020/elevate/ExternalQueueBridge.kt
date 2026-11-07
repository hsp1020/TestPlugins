package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.player.LOADTYPE_INAPP_DOWNLOAD
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Converts all foreground native queue requests into external app handoffs. */
internal class ExternalQueueBridge(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = Channel<DownloadObjects.DownloadQueueWrapper>(Channel.UNLIMITED)
    private val resumes = Channel<Unit>(Channel.CONFLATED)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var host: FragmentActivity? = null

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        host = (CommonActivity.activity as? FragmentActivity)?.takeIf {
            it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        scope.launch(Dispatchers.Unconfined) {
            DownloadQueueManager.queue.collect { queued ->
                val target = if (queued.isNotEmpty()) ExternalDownloads.selected(app) else null
                val enabled = ElevateFeaturePrefs.enabled(app, ElevateFeature.EXTERNAL_DOWNLOADS)
                if (queued.isNotEmpty()) android.util.Log.i("ElevateExternal",
                    "QUEUE_OBSERVED count=${queued.size} ids=${queued.map { it.id }} enabled=$enabled app=${target?.label}")
                if (queued.isEmpty() || target == null || !enabled) {
                    return@collect
                }
                // Drain synchronously on StateFlow emission, before the download service
                // normally receives and processes the newly queued request.
                DownloadQueueManager.removeAllFromQueue()
                android.util.Log.i("ElevateExternal", "QUEUE_DRAINED count=${queued.size}")
                queued.forEach {
                    jobs.trySend(it)
                }
                // addToQueue can emit IsPending once more after this collector returns.
                // Reset that transient status without touching running downloads.
                main.post {
                    queued.forEach { request ->
                        val id = request.id
                        val manager = com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
                        if (DownloadQueueManager.queue.value.none { it.id == id } &&
                            com.lagradost.cloudstream3.services.DownloadQueueService
                                .downloadInstances.value.none { it.downloadQueueWrapper.id == id }) {
                            manager.downloadStatus.remove(id)
                            manager.downloadStatusEvent.invoke(
                                id to com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType.IsStopped
                            )
                        }
                    }
                }
            }
        }
        scope.launch {
            for (first in jobs) {
                try {
                    // Collect the entire native batch before launching the external activity.
                    val batch = ArrayList<DownloadObjects.DownloadQueueWrapper>()
                    batch.add(first)
                    while (true) {
                        val next = withTimeoutOrNull(750L) { jobs.receive() } ?: break
                        val duplicate = batch.any { it.id == next.id }
                        android.util.Log.i("ElevateExternal", "QUEUE_BATCH_ITEM id=${next.id} duplicate=$duplicate")
                        if (!duplicate) batch.add(next)
                    }
                    android.util.Log.i("ElevateExternal", "QUEUE_BATCH_READY ids=${batch.map { it.id }}")
                    withTimeoutOrNull(2500L) {
                        while (host?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) {
                            resumes.receive()
                        }
                        true
                    } == true
                    // Bound simultaneous source lookups; keep all requests even if one fails.
                    val resolved = coroutineScope {
                        val limit = Semaphore(3)
                        batch.map { queued ->
                            async(Dispatchers.IO) {
                                limit.acquire()
                                try {
                                    android.util.Log.i("ElevateExternal", "SOURCE_LOOKUP_BEGIN id=${queued.id}")
                                    val result = prepare(queued)
                                    android.util.Log.i("ElevateExternal", "SOURCE_LOOKUP_END id=${queued.id} found=${result != null} subtitles=${result?.second?.size ?: 0}")
                                    result?.let { queued to it }
                                } catch (error: CancellationException) {
                                    if (!currentCoroutineContext().isActive) throw error
                                    null
                                } catch (error: Exception) {
                                    android.util.Log.w("ElevateExternal", "Download source lookup failed", error)
                                    null
                                } finally {
                                    limit.release()
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }
                    val activity = ((CommonActivity.activity as? FragmentActivity)
                        ?.takeIf { it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            !it.isFinishing && !it.isDestroyed })
                        ?: host?.takeIf { it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            !it.isFinishing && !it.isDestroyed }
                    val target = activity ?: app
                    android.util.Log.i("ElevateExternal", "QUEUE_HANDOFF_READY resolved=${resolved.size} requested=${batch.size} context=${target.javaClass.simpleName}")
                    if (resolved.isNotEmpty()) ExternalDownloads.handoffBatch(
                        target, resolved.map { it.first to it.second.first },
                        resolved.associate { it.first.id to it.second.second }
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    ExternalDownloads.toast(app, "External download failed: ${error.message}")
                }
            }
        }
    }

    private suspend fun prepare(
        queued: DownloadObjects.DownloadQueueWrapper
    ): Pair<ExtractorLink, List<SubtitleData>>? {
        val item = queued.downloadItem
        val saved = queued.resumePackage?.item
        val episode = item?.episode ?: saved?.ep?.let { info ->
            buildResultEpisode(
                headerName = info.mainName,
                name = info.name,
                poster = info.poster,
                episode = info.episode ?: 0,
                season = info.season,
                data = "",
                apiName = info.sourceApiName.orEmpty(),
                id = info.id,
                index = 0,
                tvType = info.type ?: TvType.Others,
                parentId = info.parentId
            )
        } ?: run {
            return null
        }

        val collectedSubs = java.util.Collections.synchronizedSet(LinkedHashSet<SubtitleData>())
        item?.subs?.let(collectedSubs::addAll)
        val links = when {
            item?.links?.isNotEmpty() == true -> {
                item.links
            }
            saved?.links?.isNotEmpty() == true -> {
                saved.links
            }
            item != null -> {
                val found = java.util.Collections.synchronizedMap(LinkedHashMap<String, ExtractorLink>())
                try {
                    withContext(CoroutineName("ElevateDownloadVideoOnly")) {
                        RepoLinkGenerator(listOf(episode)).generateLinks(
                            clearCache = false,
                            sourceTypes = LOADTYPE_INAPP_DOWNLOAD,
                            callback = { (link, _) ->
                                if (link != null) found[link.url] = link
                            },
                            subtitleCallback = { collectedSubs.add(it) },
                            offset = 0,
                            isCasting = false
                        )
                    }
                } catch (error: Exception) {
                    // Native provider timeouts can arrive after valid links were emitted.
                    // Do not discard those links or cancel the entire download batch.
                    if (!currentCoroutineContext().isActive || found.isEmpty()) throw error
                }
                synchronized(found) { found.values.toList() }
            }
            else -> {
                emptyList()
            }
        }.orEmpty()
        if (links.isEmpty()) {
            ExternalDownloads.toast(app, "No downloadable source found")
            return null
        }

        // A manually chosen download mirror is supplied as exactly one link.
        // For automatic downloads reuse CloudStream's own Download quality profile.
        val selected = if (links.size == 1) links.first() else {
            val profile = QualityDataHelper.getProfiles().firstOrNull {
                QualityDataHelper.QualityProfileType.Download in it.types
            }
            if (profile == null) links.first()
            else links.maxBy { QualityDataHelper.getLinkPriority(profile.id, it) }
        }
        return selected to synchronized(collectedSubs) { collectedSubs.toList() }
    }

    fun stop() {
        app.unregisterActivityLifecycleCallbacks(this)
        scope.cancel()
        jobs.close()
        resumes.close()
        host = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) {
            host = activity
            resumes.trySend(Unit)
        }
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) host = null
    }
    override fun onActivityCreated(a: Activity, b: android.os.Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivityPaused(a: Activity) = Unit
    override fun onActivityStopped(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) = Unit
}
