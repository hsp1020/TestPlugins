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

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        host = (CommonActivity.activity as? FragmentActivity)?.takeIf {
            it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        scope.launch(Dispatchers.Unconfined) {
            DownloadQueueManager.queue.collect { queued ->
                if (queued.isEmpty() || ExternalDownloads.selected(app) == null ||
                    !ElevateFeaturePrefs.enabled(app, ElevateFeature.EXTERNAL_DOWNLOADS)) return@collect
                // Drain synchronously on StateFlow emission, before the download service
                // normally receives and processes the newly queued request.
                DownloadQueueManager.removeAllFromQueue()
                queued.forEach { jobs.trySend(it) }
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
                        if (batch.none { it.id == next.id }) batch.add(next)
                    }
                    while (host?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) {
                        resumes.receive()
                    }
                    // Bound simultaneous source lookups; keep all requests even if one fails.
                    val resolved = coroutineScope {
                        val limit = Semaphore(3)
                        batch.map { queued ->
                            async(Dispatchers.IO) {
                                limit.acquire()
                                try {
                                    prepare(queued)?.let { queued to it.second }
                                } finally {
                                    limit.release()
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }
                    val activity = host ?: continue
                    if (resolved.isNotEmpty()) ExternalDownloads.handoffBatch(activity, resolved)
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
    ): Pair<ResultEpisode, ExtractorLink>? {
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
        } ?: return null

        val links = when {
            item?.links?.isNotEmpty() == true -> item.links
            saved?.links?.isNotEmpty() == true -> saved.links
            item != null -> {
                val found = java.util.Collections.synchronizedMap(LinkedHashMap<String, ExtractorLink>())
                RepoLinkGenerator(listOf(episode)).generateLinks(
                    clearCache = false,
                    sourceTypes = LOADTYPE_INAPP_DOWNLOAD,
                    callback = { (link, _) -> if (link != null) found[link.url] = link },
                    subtitleCallback = {},
                    offset = 0,
                    isCasting = false
                )
                synchronized(found) { found.values.toList() }
            }
            else -> emptyList()
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
        return episode to selected
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
