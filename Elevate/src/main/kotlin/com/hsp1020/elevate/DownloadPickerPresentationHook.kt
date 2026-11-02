package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.lagradost.cloudstream3.mvvm.Resource
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.ui.result.EpisodeAdapter
import com.lagradost.cloudstream3.ui.result.EpisodeClickEvent
import com.lagradost.cloudstream3.ui.result.ACTION_DOWNLOAD_MIRROR
import com.lagradost.cloudstream3.ui.result.LinkProgress
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.SelectPopup
import com.lagradost.cloudstream3.ui.result.getTitle
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.txt

/** Present native download pickers without changing their source or selection objects. */
internal class DownloadPickerPresentationHook(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private val observers = HashMap<Fragment, Pair<ResultViewModel2, Observer<SelectPopup?>>>()
    private val mirror = HashMap<Fragment, MirrorWatch>()
    private val main = Handler(Looper.getMainLooper())

    private class MirrorWatch(
        val model: ResultViewModel2,
        val observer: Observer<LinkProgress?>,
    ) {
        var active = false
        var started = false
        var skipped = false
        var lastCount = 0
        var episode: ResultEpisode? = null
        var timeout: Runnable? = null
        var adapter: EpisodeAdapter? = null
        var original: ((EpisodeClickEvent) -> Unit)? = null
        var wrapped: ((EpisodeClickEvent) -> Unit)? = null
        var movieButton: View? = null
    }

    private fun clearTimeout(state: MirrorWatch) {
        state.timeout?.let(main::removeCallbacks)
        state.timeout = null
    }

    private fun clearPartialCache(episode: ResultEpisode?) {
        val key = episode?.let { it.apiName to it.id } ?: return
        synchronized(RepoLinkGenerator.cache) {
            RepoLinkGenerator.cache[key]?.let { cached ->
                synchronized(cached) { cached.saturated = false }
            }
        }
    }

    private fun beginMirror(state: MirrorWatch, episode: ResultEpisode?) {
        clearTimeout(state)
        state.active = true
        state.started = false
        state.skipped = false
        state.lastCount = 0
        state.episode = episode
        ExternalDownloads.trace("picker.mirror.begin id=" + episode?.id)
    }

    private fun attachMovieClick(fragment: Fragment, state: MirrorWatch) {
        val button = fragment.view?.findViewById<View>(R.id.download_movie_button) ?: return
        if (state.movieButton === button) return
        state.movieButton?.setOnTouchListener(null)
        state.movieButton = button
        var downAt = 0L
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> downAt = SystemClock.uptimeMillis()
                MotionEvent.ACTION_UP -> {
                    if (downAt > 0L && SystemClock.uptimeMillis() - downAt >=
                        ViewConfiguration.getLongPressTimeout()) {
                        val episode = (state.model.movie.value as? Resource.Success)?.value?.second
                        beginMirror(state, episode)
                    }
                    downAt = 0L
                }
                MotionEvent.ACTION_CANCEL -> downAt = 0L
            }
            false // Preserve the original native click and long-click handlers.
        }
    }

    private fun attachEpisodeClick(fragment: Fragment, state: MirrorWatch) {
        val adapter = fragment.view?.findViewById<RecyclerView>(R.id.result_episodes)
            ?.adapter as? EpisodeAdapter ?: return
        if (state.adapter === adapter) return
        val field = runCatching { adapter.javaClass.getDeclaredField("clickCallback").apply {
            isAccessible = true
        } }.getOrNull() ?: return
        @Suppress("UNCHECKED_CAST")
        val previous = runCatching { field.get(adapter) as? ((EpisodeClickEvent) -> Unit) }
            .getOrNull() ?: return
        val wrapped: (EpisodeClickEvent) -> Unit = { event ->
            if (event.action == ACTION_DOWNLOAD_MIRROR) beginMirror(state, event.data)
            else {
                clearTimeout(state)
                state.active = false
                state.skipped = false
                state.episode = null
            }
            previous(event)
        }
        if (runCatching { field.set(adapter, wrapped) }.isFailure) {
            ExternalDownloads.trace("picker.mirror.install.failed")
            return
        }
        state.adapter = adapter
        state.original = previous
        state.wrapped = wrapped
        ExternalDownloads.trace("picker.mirror.install.ok")
    }
    private val numericEntity = Regex("&#(?:x([0-9a-fA-F]+)|([0-9]+));")

    private fun displayName(raw: String): String {
        val input = raw.replace("&amp;#", "&#", ignoreCase = true)
        if (!input.contains("&#")) return raw
        return numericEntity.replace(input) { match ->
            val hex = match.groupValues[1]
            val cp = (if (hex.isNotEmpty()) hex.toIntOrNull(16)
                else match.groupValues[2].toIntOrNull())
            if (cp == null || !Character.isValidCodePoint(cp) || cp in 0xD800..0xDFFF)
                match.value else String(Character.toChars(cp))
        }
    }

    private fun capturedLinks(callback: Any, depth: Int = 4): List<ExtractorLink>? {
        if (callback is LinkLoadingResult) return callback.links
        if (depth == 0 || callback !is Function<*>) return null
        for (field in callback.javaClass.declaredFields) {
            val value = runCatching { field.isAccessible = true; field.get(callback) }.getOrNull()
            if (value == null) continue
            if (value is LinkLoadingResult) return value.links
            if (value is Function<*>) capturedLinks(value, depth - 1)?.let { return it }
        }
        return null
    }

    private fun updatedPopup(fragment: Fragment, popup: SelectPopup.SelectText): SelectPopup.SelectText? {
        val ctx = fragment.context ?: return null
        return when (popup.getTitle(ctx)) {
            ctx.getString(R.string.episode_action_download_subtitle) -> {
                val options = popup.options.map { item ->
                    val text = item.asString(ctx)
                    val display = displayName(text)
                    if (display == text) item else txt(display)
                }
                if (options == popup.options) null else {
                    ExternalDownloads.trace("picker.subtitle.labels restored=" +
                        options.indices.count { options[it] != popup.options[it] })
                    popup.copy(options = options)
                }
            }
            ctx.getString(R.string.episode_action_download_mirror) -> {
                val links = capturedLinks(popup.callback) ?: run {
                    ExternalDownloads.trace("picker.mirror.capture.unavailable")
                    return null
                }
                if (links.size != popup.options.size) {
                    ExternalDownloads.trace("picker.mirror.capture.countMismatch")
                    return null
                }
                if (links.indices.any { index ->
                    popup.options[index].asString(ctx) !=
                        links[index].name + " " + Qualities.getStringByInt(links[index].quality)
                }) {
                    ExternalDownloads.trace("picker.mirror.capture.labelsMismatch")
                    return null
                }
                val profile = QualityDataHelper.getProfiles().firstOrNull {
                    QualityDataHelper.QualityProfileType.Download in it.types
                } ?: return null
                val order = links.indices.sortedWith(compareByDescending<Int> {
                    QualityDataHelper.getLinkPriority(profile.id, links[it])
                }.thenBy { it })
                if (order.indices.all { order[it] == it }) return null
                ExternalDownloads.trace("picker.mirror.sorted count=" + order.size + " profile=" + profile.id)
                popup.copy(options = order.map { popup.options[it] }) { index ->
                    popup.callback(index?.let { order.getOrNull(it) })
                }
            }
            else -> null
        }
    }

    private fun watch(fragment: Fragment) {
        if (fragment.javaClass.name !in setOf(
                "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone",
                "com.lagradost.cloudstream3.ui.result.ResultFragmentTv"
            ) || observers.containsKey(fragment)) return
        val model = ViewModelProvider(fragment)[ResultViewModel2::class.java]
        var replacement: SelectPopup? = null
        val observer = Observer<SelectPopup?> { popup ->
            val watched = mirror[fragment]
            if (watched?.started == true && popup is SelectPopup.SelectText &&
                popup.getTitle(fragment.requireContext()) == fragment.getString(R.string.episode_action_download_mirror)) {
                clearTimeout(watched)
                watched.active = false
                if (watched.skipped) clearPartialCache(watched.episode)
                watched.skipped = false
                watched.episode = null
            }
            if (popup !is SelectPopup.SelectText || popup === replacement) return@Observer
            val revised = runCatching { updatedPopup(fragment, popup) }.getOrNull() ?: return@Observer
            replacement = revised
            (model.selectPopup as? MutableLiveData<SelectPopup?>)?.value = revised
        }
        observers[fragment] = model to observer
        model.selectPopup.observeForever(observer)
        val progress = Observer<LinkProgress?> { update ->
            val state = mirror[fragment] ?: return@Observer
            if (!state.active) return@Observer
            if (update == null) {
                if (state.started) {
                    clearTimeout(state)
                    state.active = false
                }
                return@Observer
            }
            state.started = true
            if (update.linksLoaded <= 0 || update.linksLoaded == state.lastCount) return@Observer
            state.lastCount = update.linksLoaded
            clearTimeout(state)
            val task = Runnable {
                if (state.active && model.loadedLinks.value?.linksLoaded == state.lastCount &&
                    state.lastCount > 0) {
                    ExternalDownloads.trace("picker.mirror.video.quietLimit id=" + state.episode?.id +
                        " count=" + state.lastCount)
                    state.active = false
                    state.skipped = true
                    model.skipLoading()
                }
            }
            state.timeout = task
            main.postDelayed(task, 4_000L)
        }
        mirror[fragment] = MirrorWatch(model, progress)
        model.loadedLinks.observeForever(progress)
        fragment.view?.post { mirror[fragment]?.let {
            attachEpisodeClick(fragment, it)
            attachMovieClick(fragment, it)
        } }
    }

    private fun release(fragment: Fragment) {
        observers.remove(fragment)?.let { (model, observer) ->
            model.selectPopup.removeObserver(observer)
        }
        mirror.remove(fragment)?.let { state ->
            clearTimeout(state)
            state.movieButton?.setOnTouchListener(null)
            state.movieButton = null
            state.model.loadedLinks.removeObserver(state.observer)
            val adapter = state.adapter
            if (adapter != null) runCatching {
                val field = adapter.javaClass.getDeclaredField("clickCallback").apply { isAccessible = true }
                if (field.get(adapter) === state.wrapped) field.set(adapter, state.original)
            }
        }
    }

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentCreated(fm: FragmentManager, f: Fragment, saved: Bundle?) = watch(f)
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: android.view.View, saved: Bundle?) {
            watch(f)
            v.post { mirror[f]?.let {
                attachEpisodeClick(f, it)
                attachMovieClick(f, it)
            } }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) = release(f)
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
        if (activity is MainActivity) use(activity)
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
