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
import com.lagradost.cloudstream3.ui.result.ResultEpisode
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

/** Converts all foreground native queue requests into external app handoffs. */
internal class ExternalQueueBridge(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = Channel<DownloadObjects.DownloadQueueWrapper>(Channel.UNLIMITED)
    private val resumes = Channel<Unit>(Channel.CONFLATED)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var host: FragmentActivity? = null

    private fun hostInfo(): String {
        val observed = host
        val current = CommonActivity.activity
        return "host=" + (observed?.let { System.identityHashCode(it) } ?: "null") +
            " state=" + (observed?.lifecycle?.currentState ?: "null") +
            " destroyed=" + (observed?.isDestroyed ?: "null") +
            " current=" + (current?.let { System.identityHashCode(it) } ?: "null") +
            " currentType=" + (current?.javaClass?.simpleName ?: "null")
    }

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        host = (CommonActivity.activity as? FragmentActivity)?.takeIf {
            it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        ExternalDownloads.trace("bridge.start " + hostInfo())
        scope.launch(Dispatchers.Unconfined) {
            DownloadQueueManager.queue.collect { queued ->
                val target = if (queued.isNotEmpty()) ExternalDownloads.selected(app) else null
                val enabled = ElevateFeaturePrefs.enabled(app, ElevateFeature.EXTERNAL_DOWNLOADS)
                ExternalDownloads.trace("queue.emit count=" + queued.size + " enabled=" + enabled +
                    " target=" + (target?.label ?: "none") + " ids=" + queued.joinToString(",") { it.id.toString() })
                if (queued.isEmpty() || target == null || !enabled) {
                    ExternalDownloads.trace("queue.skip count=" + queued.size)
                    return@collect
                }
                // Drain synchronously on StateFlow emission, before the download service
                // normally receives and processes the newly queued request.
                ExternalDownloads.trace("queue.remove.begin count=" + queued.size)
                DownloadQueueManager.removeAllFromQueue()
                ExternalDownloads.trace("queue.remove.end remaining=" + DownloadQueueManager.queue.value.size)
                queued.forEach {
                    val sent = jobs.trySend(it)
                    ExternalDownloads.trace("queue.channel.send id=" + it.id + " ok=" + sent.isSuccess +
                        " directLinks=" + (it.downloadItem?.links?.size ?: -1) +
                        " savedLinks=" + (it.resumePackage?.item?.links?.size ?: -1))
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
                            ExternalDownloads.trace("queue.status.reset id=" + id)
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
                val started = android.os.SystemClock.elapsedRealtime()
                ExternalDownloads.trace("worker.receive id=" + first.id + " " + hostInfo())
                try {
                    // Collect the entire native batch before launching the external activity.
                    val batch = ArrayList<DownloadObjects.DownloadQueueWrapper>()
                    batch.add(first)
                    while (true) {
                        val next = withTimeoutOrNull(750L) { jobs.receive() } ?: break
                        val duplicate = batch.any { it.id == next.id }
                        ExternalDownloads.trace("batch.collect id=" + next.id + " duplicate=" + duplicate)
                        if (!duplicate) batch.add(next)
                    }
                    ExternalDownloads.trace("batch.ready count=" + batch.size + " ids=" +
                        batch.joinToString(",") { it.id.toString() } + " elapsedMs=" +
                        (android.os.SystemClock.elapsedRealtime() - started) + " " + hostInfo())
                    if (host?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) {
                        ExternalDownloads.trace("host.wait.enter " + hostInfo())
                    }
                    val ready = withTimeoutOrNull(2500L) {
                        while (host?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) {
                            resumes.receive()
                            ExternalDownloads.trace("host.wait.wakeup " + hostInfo())
                        }
                        true
                    } == true
                    ExternalDownloads.trace("host.wait.end ready=" + ready + " " + hostInfo())
                    // Bound simultaneous source lookups; keep all requests even if one fails.
                    val resolved = coroutineScope {
                        val limit = Semaphore(3)
                        batch.map { queued ->
                            async(Dispatchers.IO) {
                                ExternalDownloads.trace("resolve.semaphore.wait id=" + queued.id)
                                limit.acquire()
                                val resolvedAt = android.os.SystemClock.elapsedRealtime()
                                ExternalDownloads.trace("resolve.begin id=" + queued.id)
                                try {
                                    val result = prepare(queued)
                                    ExternalDownloads.trace("resolve.end id=" + queued.id +
                                        " hasSource=" + (result != null) + " elapsedMs=" +
                                        (android.os.SystemClock.elapsedRealtime() - resolvedAt))
                                    result?.let { queued to it.second }
                                } catch (error: Exception) {
                                    ExternalDownloads.trace("resolve.error id=" + queued.id + " " +
                                        ExternalDownloads.errorInfo(error))
                                    throw error
                                } finally {
                                    limit.release()
                                    ExternalDownloads.trace("resolve.semaphore.release id=" + queued.id)
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }
                    ExternalDownloads.trace("batch.resolved requested=" + batch.size + " count=" + resolved.size)
                    val activity = ((CommonActivity.activity as? FragmentActivity)
                        ?.takeIf { it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            !it.isFinishing && !it.isDestroyed })
                        ?: host?.takeIf { it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            !it.isFinishing && !it.isDestroyed }
                    val target = activity ?: app
                    ExternalDownloads.trace("handoff.call count=" + resolved.size +
                        " target=" + (if (activity == null) "application" else "activity") + " " + hostInfo())
                    if (resolved.isNotEmpty()) ExternalDownloads.handoffBatch(target, resolved)
                    ExternalDownloads.trace("worker.end id=" + first.id + " elapsedMs=" +
                        (android.os.SystemClock.elapsedRealtime() - started))
                } catch (cancel: CancellationException) {
                    ExternalDownloads.trace("worker.cancel id=" + first.id + " " + ExternalDownloads.errorInfo(cancel))
                    throw cancel
                } catch (error: Exception) {
                    ExternalDownloads.trace("worker.error id=" + first.id + " " + ExternalDownloads.errorInfo(error))
                    ExternalDownloads.toast(app, "External download failed: ${error.message}")
                }
            }
        }
    }

    private suspend fun prepare(
        queued: DownloadObjects.DownloadQueueWrapper
    ): Pair<ResultEpisode, ExtractorLink>? {
        val item = queued.downloadItem
        val saved = queued.resumePackage?.item
        ExternalDownloads.trace("prepare.input id=" + queued.id + " item=" + (item != null) +
            " saved=" + (saved != null) + " itemLinks=" + (item?.links?.size ?: -1) +
            " savedLinks=" + (saved?.links?.size ?: -1))
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
            ExternalDownloads.trace("prepare.noEpisode id=" + queued.id)
            return null
        }

        val links = when {
            item?.links?.isNotEmpty() == true -> {
                ExternalDownloads.trace("prepare.links.item id=" + queued.id + " count=" + item.links?.size)
                item.links
            }
            saved?.links?.isNotEmpty() == true -> {
                ExternalDownloads.trace("prepare.links.saved id=" + queued.id + " count=" + saved.links.size)
                saved.links
            }
            item != null -> {
                ExternalDownloads.trace("prepare.generate.begin id=" + queued.id)
                val found = java.util.Collections.synchronizedMap(LinkedHashMap<String, ExtractorLink>())
                withContext(CoroutineName("ElevateDownloadVideoOnly")) {
                    RepoLinkGenerator(listOf(episode)).generateLinks(
                    clearCache = false,
                    sourceTypes = LOADTYPE_INAPP_DOWNLOAD,
                    callback = { (link, _) ->
                        if (link != null) {
                            found[link.url] = link
                            ExternalDownloads.trace("prepare.generate.link id=" + queued.id +
                                " quality=" + link.quality + " hls=" + link.isM3u8)
                        } else ExternalDownloads.trace("prepare.generate.nullLink id=" + queued.id)
                    },
                    subtitleCallback = {},
                    offset = 0,
                    isCasting = false
                    )
                }
                synchronized(found) { found.values.toList() }.also {
                    ExternalDownloads.trace("prepare.generate.end id=" + queued.id + " unique=" + it.size)
                }
            }
            else -> {
                ExternalDownloads.trace("prepare.noLinkSource id=" + queued.id)
                emptyList()
            }
        }.orEmpty()
        if (links.isEmpty()) {
            ExternalDownloads.trace("prepare.emptyLinks id=" + queued.id)
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
        ExternalDownloads.trace("prepare.selected id=" + queued.id + " choices=" + links.size +
            " quality=" + selected.quality + " hls=" + selected.isM3u8)
        return episode to selected
    }

    fun stop() {
        ExternalDownloads.trace("bridge.stop " + hostInfo())
        app.unregisterActivityLifecycleCallbacks(this)
        scope.cancel()
        jobs.close()
        resumes.close()
        host = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) {
            ExternalDownloads.trace("activity.resumed id=" + System.identityHashCode(activity) +
                " before=" + hostInfo())
            host = activity
            val sent = resumes.trySend(Unit)
            ExternalDownloads.trace("activity.resumed.signal ok=" + sent.isSuccess + " " + hostInfo())
        }
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (activity is MainActivity) ExternalDownloads.trace("activity.destroyed id=" +
            System.identityHashCode(activity) + " isHost=" + (host === activity))
        if (host === activity) host = null
    }
    override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {
        if (a is MainActivity) ExternalDownloads.trace("activity.created id=" +
            System.identityHashCode(a) + " saved=" + (b != null))
    }
    override fun onActivityStarted(a: Activity) {
        if (a is MainActivity) ExternalDownloads.trace("activity.started id=" + System.identityHashCode(a))
    }
    override fun onActivityPaused(a: Activity) {
        if (a is MainActivity) ExternalDownloads.trace("activity.paused id=" + System.identityHashCode(a))
    }
    override fun onActivityStopped(a: Activity) {
        if (a is MainActivity) ExternalDownloads.trace("activity.stopped id=" + System.identityHashCode(a))
    }
    override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {
        if (a is MainActivity) ExternalDownloads.trace("activity.saveState id=" + System.identityHashCode(a))
    }
}
