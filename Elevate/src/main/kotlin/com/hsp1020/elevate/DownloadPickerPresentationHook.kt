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

    /** Rebuild native link order from its own cache; this also contains links kept by Skip. */
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

        // This is the same filtered, quality-sorted set used by the native mirror popup.
        val candidates = synchronized(RepoLinkGenerator.cache) {
            RepoLinkGenerator.cache.mapNotNull { (key, cached) ->
                if (episodeKeys.isNotEmpty() && key !in episodeKeys) return@mapNotNull null
                val links = synchronized(cached) {
                    sortUrls(cached.linkCache.filterTo(LinkedHashSet<ExtractorLink>()) {
                        it.type in LOADTYPE_INAPP_DOWNLOAD
                    })
                }
                links.takeIf { it.size == options.size && links.indices.all { index ->
                    options[index] == links[index].name + " " +
                        Qualities.getStringByInt(links[index].quality)
                } }
            }
        }
        val orders = candidates.map { links ->
            links.indices.sortedWith(compareByDescending<Int> {
                QualityDataHelper.getLinkPriority(profileId, links[it])
            }.thenBy { it })
        }.distinct()
        return orders.singleOrNull().also {
            if (it == null) ExternalDownloads.trace(
                "picker.mirror.cache." + (if (orders.isEmpty()) "noMatch" else "ambiguous") +
                    " options=" + options.size + " candidates=" + candidates.size
            )
        }
    }

    private fun updatedPopup(fragment: Fragment, model: ResultViewModel2, popup: SelectPopup.SelectText): SelectPopup? {
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
                val profile = QualityDataHelper.getProfiles().firstOrNull {
                    QualityDataHelper.QualityProfileType.Download in it.types
                } ?: return null
                val labels = popup.options.map { it.asString(ctx) }
                // Read the actual ExtractorLinks without relying on compiler-generated lambda fields.
                // On native Skip, the popup and this cache both contain the partial link set.
                val order = cachedOrders(model, labels, profile.id)
                    ?: capturedLinks(popup.callback)?.takeIf { links ->
                        links.size == labels.size && links.indices.all { index ->
                            labels[index] == links[index].name + " " +
                                Qualities.getStringByInt(links[index].quality)
                        }
                    }?.let { links ->
                        ExternalDownloads.trace("picker.mirror.callback.fallback count=" + links.size)
                        links.indices.sortedWith(compareByDescending<Int> {
                            QualityDataHelper.getLinkPriority(profile.id, links[it])
                        }.thenBy { it })
                    } ?: return null
                if (order.indices.all { order[it] == it }) return null
                ExternalDownloads.trace("picker.mirror.sorted count=" + order.size + " profile=" + profile.id)
                // Native SelectArray already maps displayed row -> original callback index.
                // Leave the original callback and native selection/download queue unchanged.
                SelectPopup.SelectArray(
                    popup.text, order.map { popup.options[it] to it }, popup.callback
                )
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
            if (popup !is SelectPopup.SelectText || popup === replacement) return@Observer
            val revised = runCatching { updatedPopup(fragment, model, popup) }.getOrNull() ?: return@Observer
            replacement = revised
            (model.selectPopup as? MutableLiveData<SelectPopup?>)?.value = revised
        }
        observers[fragment] = model to observer
        model.selectPopup.observeForever(observer)
    }

    private fun release(fragment: Fragment) {
        observers.remove(fragment)?.let { (model, observer) ->
            model.selectPopup.removeObserver(observer)
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
