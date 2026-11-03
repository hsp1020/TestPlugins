package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.sortUrls
import com.lagradost.cloudstream3.ui.player.LOADTYPE_INAPP_DOWNLOAD
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.EpisodeClickEvent
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.SelectPopup
import com.lagradost.cloudstream3.ui.result.ResultFragmentPhone
import com.lagradost.cloudstream3.ui.result.ResultFragmentTv
import com.lagradost.cloudstream3.ui.result.getTitle
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.txt

/** Present native download pickers without changing their source or selection objects. */
internal class DownloadPickerPresentationHook(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private data class Binding(
        val model: ResultViewModel2,
        val popup: Observer<SelectPopup?>
    )
    private val observers = HashMap<Fragment, Binding>()
    private fun capturedResult(callback: Any, depth: Int = 7): LinkLoadingResult? {
        if (callback is LinkLoadingResult) return callback
        if (depth == 0 || callback !is Function<*>) return null
        for (field in callback.javaClass.declaredFields) {
            val value = runCatching {
                field.isAccessible = true
                field.get(callback)
            }.getOrNull() ?: continue
            if (value is LinkLoadingResult) return value
            if (value is Function<*>) capturedResult(value, depth - 1)?.let { return it }
        }
        return null
    }

    private fun capturedEpisode(callback: Any, depth: Int = 8): ResultEpisode? {
        if (callback is ResultEpisode) return callback
        if (callback is EpisodeClickEvent) return callback.data
        if (depth == 0 || callback !is Function<*>) return null
        for (field in callback.javaClass.declaredFields) {
            val value = runCatching {
                field.isAccessible = true
                field.get(callback)
            }.getOrNull() ?: continue
            if (value is ResultEpisode) return value
            if (value is EpisodeClickEvent) return value.data
            if (value is Function<*>) capturedEpisode(value, depth - 1)?.let { return it }
        }
        return null
    }

    private fun labelOf(link: ExtractorLink): String =
        link.name + " " + Qualities.getStringByInt(link.quality)

    private fun scoreOrder(profileId: Int, links: List<ExtractorLink>): List<Int> {
        val scores = links.map { QualityDataHelper.getLinkPriority(profileId, it) }
        return links.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
    }

    /** A skipped popup may be a strict subset of the cached links. Never guess on score conflicts. */
    private fun cachedOrders(
        model: ResultViewModel2, options: List<String>, profileId: Int
    ): List<Int>? {
        val episodeKeys = HashSet<Pair<String, Int>>()
        val episodes = model.episodes.value
        if (episodes is Resource.Success) episodes.value.forEach {
            episodeKeys.add(it.apiName to it.id)
        }
        val movie = model.movie.value
        if (movie is Resource.Success) movie.value.second.let {
            episodeKeys.add(it.apiName to it.id)
        }

        val snapshots = synchronized(RepoLinkGenerator.cache) {
            RepoLinkGenerator.cache.mapNotNull { (key, cached) ->
                if (episodeKeys.isNotEmpty() && key !in episodeKeys) return@mapNotNull null
                key to synchronized(cached) {
                    sortUrls(cached.linkCache.filterTo(LinkedHashSet<ExtractorLink>()) {
                        it.type in LOADTYPE_INAPP_DOWNLOAD
                    })
                }
            }
        }
        val required = options.groupingBy { it }.eachCount()
        val candidates = snapshots.mapNotNull { (key, links) ->
            val grouped = links.groupBy(::labelOf)
            val missing = required.entries.firstOrNull {
                grouped[it.key].orEmpty().size < it.value
            }
            if (missing != null) {
                return@mapNotNull null
            }
            val conflict = required.keys.firstOrNull { label ->
                grouped[label].orEmpty().map {
                    QualityDataHelper.getLinkPriority(profileId, it)
                }.distinct().size > 1
            }
            if (conflict != null) {
                return@mapNotNull null
            }
            key to options.map { grouped.getValue(it).first() }
        }
        val orders = candidates.map { (key, links) ->
            scoreOrder(profileId, links)
        }.distinct()
        return orders.singleOrNull()
    }

    private fun updatedPopup(
        fragment: Fragment, model: ResultViewModel2, popup: SelectPopup.SelectText
    ): SelectPopup? {
        val ctx = fragment.context ?: return null
        val title = popup.getTitle(ctx)
        return when (title) {
            ctx.getString(R.string.episode_action_download_subtitle) -> {
                val subs = capturedResult(popup.callback)?.subs
                    ?.takeIf { it.size == popup.options.size }
                val episode = capturedEpisode(popup.callback)
                    ?: (model.movie.value as? Resource.Success)?.value?.second
                    ?: (model.episodes.value as? Resource.Success)?.value?.singleOrNull()
                if (subs == null || episode == null) {
                    val options = popup.options.mapIndexedNotNull { index, label ->
                        val raw = label.asString(ctx)
                        if (!SubtitleLanguageNormalizer.isSelectableLabel(raw)) null
                        else {
                            val fixed = ExternalDownloads.displayName(raw)
                            (if (raw == fixed) label else txt(fixed)) to index
                        }
                    }
                    if (options.size == popup.options.size &&
                        options.indices.all { options[it].first == popup.options[it] }) null
                    else SelectPopup.SelectArray(popup.text, options, popup.callback)
                } else {
                    val options = ExternalDownloads.subtitleChoices(ctx, subs)
                    SelectPopup.SelectArray(
                        popup.text, options.map { txt(it.title) to it.originalIndex }
                    ) { originalIndex ->
                        if (originalIndex == null) {
                            popup.callback(null)
                        } else {
                            val subtitle = subs.getOrNull(originalIndex)
                            val label = options.firstOrNull { it.originalIndex == originalIndex }?.title
                            if (subtitle == null || label == null) {
                                popup.callback(originalIndex)
                            } else {
                                val title = (model.page.value as? Resource.Success)?.value?.title
                                    ?: episode.headerName
                                if (!ExternalDownloads.handoffManualSubtitle(
                                        ctx, episode, title, subtitle, label
                                    )) ExternalDownloads.toast(ctx, "Cannot open external downloader")
                                popup.callback(null)
                            }
                        }
                    }
                }
            }
            ctx.getString(R.string.episode_action_download_mirror) -> {
                val profile = QualityDataHelper.getProfiles().firstOrNull {
                    QualityDataHelper.QualityProfileType.Download in it.types
                } ?: run {
                    return null
                }
                val labels = popup.options.map { it.asString(ctx) }
                val captured = capturedResult(popup.callback)?.links
                val direct = captured?.takeIf { links ->
                    val mismatch = links.indices.firstOrNull {
                        it >= labels.size || labels[it] != labelOf(links[it])
                    }
                    val valid = links.size == labels.size && mismatch == null
                    valid
                }
                val order = if (direct != null) {
                    scoreOrder(profile.id, direct)
                } else {
                    cachedOrders(model, labels, profile.id)
                } ?: run {
                    return null
                }
                if (order.indices.all { order[it] == it }) {
                    return null
                }
                // SelectArray.transformResult maps visual rows to these original indices.
                SelectPopup.SelectArray(
                    popup.text, order.map { popup.options[it] to it }, popup.callback
                )
            }
            else -> {
                null
            }
        }
    }

    private fun watch(fragment: Fragment) {
        if (fragment !is ResultFragmentPhone && fragment !is ResultFragmentTv) return
        if (observers.containsKey(fragment)) {
            return
        }
        val model = ViewModelProvider(fragment)[ResultViewModel2::class.java]
        var replacement: SelectPopup? = null
        val observer = Observer<SelectPopup?> { popup ->
            if (popup !is SelectPopup.SelectText || popup === replacement) return@Observer
            val revised = runCatching { updatedPopup(fragment, model, popup) }
                .getOrNull() ?: return@Observer
            val mutable = model.selectPopup as? MutableLiveData<SelectPopup?>
            if (mutable == null) {
                return@Observer
            }
            replacement = revised
            mutable.value = revised
        }
        observers[fragment] = Binding(model, observer)
        model.selectPopup.observeForever(observer)
    }

    private fun release(fragment: Fragment) {
        observers.remove(fragment)?.let { binding ->
            binding.model.selectPopup.removeObserver(binding.popup)
        }
    }

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentCreated(fm: FragmentManager, f: Fragment, saved: Bundle?) = watch(f)
        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) = release(f)
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        observers.keys.toList().forEach(::release)
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                if (fragment.isAdded) watch(fragment)
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? FragmentActivity)?.let(::use)
    }

    fun stop() {
        app.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        observers.keys.toList().forEach(::release)
        host = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) {
            use(activity)
        }
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) {
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
            observers.keys.toList().forEach(::release)
            host = null
        }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
