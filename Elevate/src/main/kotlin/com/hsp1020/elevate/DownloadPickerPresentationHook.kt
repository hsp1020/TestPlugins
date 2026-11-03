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
import com.lagradost.cloudstream3.ui.result.LinkProgress
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.SelectPopup
import com.lagradost.cloudstream3.ui.result.ResultFragmentPhone
import com.lagradost.cloudstream3.ui.result.ResultFragmentTv
import com.lagradost.cloudstream3.ui.result.getTitle
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.txt

/** Present native download pickers without changing their source or selection objects. */
internal class DownloadPickerPresentationHook(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private data class Binding(
        val model: ResultViewModel2,
        val popup: Observer<SelectPopup?>,
        val progress: Observer<LinkProgress?>
    )
    private val observers = HashMap<Fragment, Binding>()

    private fun brief(text: String): String = text.replace('\n', ' ').replace('\r', ' ').take(120)

    // skipLoading() clears this job; a normally completed lookup retains the completed job.
    // Diagnostic only: neither the state nor reflection changes the selection.
    private fun loadJobState(model: ResultViewModel2): String = runCatching {
        val field = model.javaClass.getDeclaredField("currentLoadLinkJob")
        field.isAccessible = true
        val job = field.get(model) as? kotlinx.coroutines.Job
        if (job == null) "null(skip/cancel candidate)" else
            "active=" + job.isActive + " completed=" + job.isCompleted +
                " cancelled=" + job.isCancelled
    }.getOrElse { "unavailable:" + ExternalDownloads.errorInfo(it) }
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

    private fun capturedLinks(callback: Any, depth: Int = 6): List<ExtractorLink>? {
        if (callback is LinkLoadingResult) {
            ExternalDownloads.trace("picker.mirror.capture.found count=" + callback.links.size)
            return callback.links
        }
        if (depth == 0 || callback !is Function<*>) return null
        val fields = callback.javaClass.declaredFields
        ExternalDownloads.trace("picker.mirror.capture.scan depth=" + depth +
            " type=" + callback.javaClass.name + " fields=" + fields.size)
        for (field in fields) {
            val value = runCatching {
                field.isAccessible = true
                field.get(callback)
            }.onFailure {
                ExternalDownloads.trace("picker.mirror.capture.field.error name=" + field.name +
                    " " + ExternalDownloads.errorInfo(it))
            }.getOrNull() ?: continue
            ExternalDownloads.trace("picker.mirror.capture.field name=" + field.name +
                " type=" + value.javaClass.name)
            if (value is LinkLoadingResult) {
                ExternalDownloads.trace("picker.mirror.capture.found count=" + value.links.size)
                return value.links
            }
            if (value is Function<*>) capturedLinks(value, depth - 1)?.let { return it }
        }
        return null
    }

    private fun labelOf(link: ExtractorLink): String =
        link.name + " " + Qualities.getStringByInt(link.quality)

    /** Log exactly the values CloudStream uses: provider score + resolution score. */
    private fun scoreOrder(
        profileId: Int, links: List<ExtractorLink>, origin: String
    ): List<Int> {
        val scores = links.mapIndexed { index, link ->
            val provider = QualityDataHelper.getSourcePriority(profileId, link.source)
            val total = QualityDataHelper.getLinkPriority(profileId, link)
            ExternalDownloads.trace("picker.mirror.score origin=" + origin +
                " row=" + index + " source=" + brief(link.source) +
                " name=" + brief(link.name) + " quality=" + link.quality +
                " resolution=" + Qualities.getStringByInt(link.quality) +
                " providerScore=" + provider + " resolutionScore=" + (total - provider) +
                " total=" + total)
            total
        }
        val order = links.indices.sortedWith(
            compareByDescending<Int> { scores[it] }.thenBy { it }
        )
        ExternalDownloads.trace("picker.mirror.order origin=" + origin +
            " oldRows=" + links.size + " newToOriginal=" + order.joinToString(",") +
            " sortedScores=" + order.joinToString(",") { scores[it].toString() })
        return order
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
        ExternalDownloads.trace("picker.mirror.cache.begin options=" + options.size +
            " episodeKeys=" + episodeKeys.size + " snapshots=" + snapshots.size)
        val required = options.groupingBy { it }.eachCount()
        val candidates = snapshots.mapNotNull { (key, links) ->
            val grouped = links.groupBy(::labelOf)
            val missing = required.entries.firstOrNull {
                grouped[it.key].orEmpty().size < it.value
            }
            if (missing != null) {
                ExternalDownloads.trace("picker.mirror.cache.reject key=" + key +
                    " cached=" + links.size + " reason=missing label=" + brief(missing.key) +
                    " wanted=" + missing.value + " found=" +
                    grouped[missing.key].orEmpty().size)
                return@mapNotNull null
            }
            val conflict = required.keys.firstOrNull { label ->
                grouped[label].orEmpty().map {
                    QualityDataHelper.getLinkPriority(profileId, it)
                }.distinct().size > 1
            }
            if (conflict != null) {
                ExternalDownloads.trace("picker.mirror.cache.reject key=" + key +
                    " reason=ambiguousScores label=" + brief(conflict))
                return@mapNotNull null
            }
            ExternalDownloads.trace("picker.mirror.cache.match key=" + key +
                " cached=" + links.size + " popup=" + options.size +
                " subset=" + (links.size != options.size))
            key to options.map { grouped.getValue(it).first() }
        }
        val orders = candidates.map { (key, links) ->
            scoreOrder(profileId, links, "cache:" + key)
        }.distinct()
        return orders.singleOrNull().also {
            if (it == null) ExternalDownloads.trace("picker.mirror.cache." +
                (if (orders.isEmpty()) "noMatch" else "ambiguous") +
                " options=" + options.size + " candidates=" + candidates.size +
                " uniqueOrders=" + orders.size)
            else ExternalDownloads.trace("picker.mirror.cache.resolved candidates=" +
                candidates.size + " orderSize=" + it.size)
        }
    }

    private fun updatedPopup(
        fragment: Fragment, model: ResultViewModel2, popup: SelectPopup.SelectText
    ): SelectPopup? {
        val ctx = fragment.context ?: run {
            ExternalDownloads.trace("picker.popup.noContext")
            return null
        }
        val title = popup.getTitle(ctx)
        ExternalDownloads.trace("picker.popup.inspect title=" + brief(title) +
            " rows=" + popup.options.size + " job=" + loadJobState(model))
        return when (title) {
            ctx.getString(R.string.episode_action_download_subtitle) -> {
                val options = popup.options.map { item ->
                    val text = item.asString(ctx)
                    val display = displayName(text)
                    if (display == text) item else txt(display)
                }
                if (options == popup.options) {
                    ExternalDownloads.trace("picker.subtitle.unchanged count=" + options.size)
                    null
                } else {
                    ExternalDownloads.trace("picker.subtitle.labels restored=" +
                        options.indices.count { options[it] != popup.options[it] })
                    popup.copy(options = options)
                }
            }
            ctx.getString(R.string.episode_action_download_mirror) -> {
                val profiles = QualityDataHelper.getProfiles()
                val profile = profiles.firstOrNull {
                    QualityDataHelper.QualityProfileType.Download in it.types
                } ?: run {
                    ExternalDownloads.trace("picker.mirror.noDownloadProfile profiles=" +
                        profiles.size)
                    return null
                }
                val labels = popup.options.map { it.asString(ctx) }
                ExternalDownloads.trace("picker.mirror.begin profile=" + profile.id +
                    " rows=" + labels.size + " callbackType=" + popup.callback.javaClass.name)
                labels.forEachIndexed { index, label ->
                    ExternalDownloads.trace("picker.mirror.option row=" + index +
                        " label=" + brief(label))
                }
                val captured = capturedLinks(popup.callback)
                ExternalDownloads.trace("picker.mirror.capture.result count=" +
                    (captured?.size ?: -1) + " expected=" + labels.size)
                val direct = captured?.takeIf { links ->
                    val mismatch = links.indices.firstOrNull {
                        it >= labels.size || labels[it] != labelOf(links[it])
                    }
                    val valid = links.size == labels.size && mismatch == null
                    if (!valid) ExternalDownloads.trace("picker.mirror.capture.reject count=" +
                        links.size + " mismatchIndex=" + (mismatch ?: -1) +
                        " expectedLabel=" + brief(labels.getOrNull(mismatch ?: -1).orEmpty()) +
                        " actualLabel=" + brief(links.getOrNull(mismatch ?: -1)?.let(::labelOf).orEmpty()))
                    valid
                }
                val order = if (direct != null) {
                    ExternalDownloads.trace("picker.mirror.source=callback")
                    scoreOrder(profile.id, direct, "callback")
                } else {
                    ExternalDownloads.trace("picker.mirror.source=cacheFallback")
                    cachedOrders(model, labels, profile.id)
                } ?: run {
                    ExternalDownloads.trace("picker.mirror.unresolved rows=" + labels.size +
                        " originalPopupPreserved=true")
                    return null
                }
                if (order.indices.all { order[it] == it }) {
                    ExternalDownloads.trace("picker.mirror.identity rows=" + order.size +
                        " profile=" + profile.id)
                    return null
                }
                ExternalDownloads.trace("picker.mirror.sorted count=" + order.size +
                    " profile=" + profile.id + " mapping=" + order.joinToString(","))
                // SelectArray.transformResult maps visual rows to these original indices.
                SelectPopup.SelectArray(
                    popup.text, order.map { popup.options[it] to it }
                ) { originalIndex ->
                    ExternalDownloads.trace("picker.mirror.select originalIndex=" +
                        originalIndex + " orderedRows=" + order.size)
                    popup.callback(originalIndex)
                }
            }
            else -> {
                ExternalDownloads.trace("picker.popup.unhandled title=" + brief(title))
                null
            }
        }
    }

    private fun watch(fragment: Fragment) {
        val type = fragment.javaClass.name
        if (fragment !is ResultFragmentPhone && fragment !is ResultFragmentTv) return
        if (observers.containsKey(fragment)) {
            ExternalDownloads.trace("picker.watch.alreadyBound fragment=" +
                System.identityHashCode(fragment))
            return
        }
        val model = ViewModelProvider(fragment)[ResultViewModel2::class.java]
        val id = System.identityHashCode(fragment)
        ExternalDownloads.trace("picker.watch.attach fragment=" + id + " type=" + type +
            " model=" + System.identityHashCode(model))
        var replacement: SelectPopup? = null
        val progress = Observer<LinkProgress?> { value ->
            ExternalDownloads.trace("picker.load.progress fragment=" + id +
                " links=" + (value?.linksLoaded ?: -1) +
                " subs=" + (value?.subsLoaded ?: -1) +
                " job=" + loadJobState(model))
        }
        val observer = Observer<SelectPopup?> { popup ->
            ExternalDownloads.trace("picker.popup.observed fragment=" + id +
                " type=" + (popup?.javaClass?.simpleName ?: "null") +
                " sameReplacement=" + (popup === replacement) +
                " job=" + loadJobState(model))
            if (popup !is SelectPopup.SelectText || popup === replacement) return@Observer
            val revised = runCatching { updatedPopup(fragment, model, popup) }
                .onFailure {
                    ExternalDownloads.trace("picker.popup.error fragment=" + id +
                        " " + ExternalDownloads.errorInfo(it) +
                        " message=" + brief(it.message.orEmpty()))
                }.getOrNull() ?: run {
                    ExternalDownloads.trace("picker.popup.preserved fragment=" + id)
                    return@Observer
                }
            val mutable = model.selectPopup as? MutableLiveData<SelectPopup?>
            if (mutable == null) {
                ExternalDownloads.trace("picker.popup.notMutable fragment=" + id)
                return@Observer
            }
            replacement = revised
            ExternalDownloads.trace("picker.popup.replace fragment=" + id +
                " type=" + revised.javaClass.simpleName)
            mutable.value = revised
        }
        observers[fragment] = Binding(model, observer, progress)
        model.loadedLinks.observeForever(progress)
        model.selectPopup.observeForever(observer)
    }

    private fun release(fragment: Fragment) {
        observers.remove(fragment)?.let { binding ->
            ExternalDownloads.trace("picker.watch.release fragment=" +
                System.identityHashCode(fragment))
            binding.model.selectPopup.removeObserver(binding.popup)
            binding.model.loadedLinks.removeObserver(binding.progress)
        }
    }

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentCreated(fm: FragmentManager, f: Fragment, saved: Bundle?) = watch(f)
        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) = release(f)
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        ExternalDownloads.trace("picker.host.attach activity=" + System.identityHashCode(activity))
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
        ExternalDownloads.trace("picker.lifecycle.start")
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? FragmentActivity)?.let(::use)
    }

    fun stop() {
        ExternalDownloads.trace("picker.lifecycle.stop")
        app.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        observers.keys.toList().forEach(::release)
        host = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) {
            ExternalDownloads.trace("picker.activity.resumed activity=" +
                System.identityHashCode(activity))
            use(activity)
        }
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) {
            ExternalDownloads.trace("picker.host.destroyed activity=" +
                System.identityHashCode(activity))
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
