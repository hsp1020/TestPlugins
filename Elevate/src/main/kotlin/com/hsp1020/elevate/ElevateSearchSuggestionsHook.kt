package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.icu.util.ULocale
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.search.SearchViewModel
import com.lagradost.cloudstream3.ui.settings.getCurrentLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * Overrides only the native SearchFragment suggestion request, not search submission/history.
 * Uses the host's TMDB credentials and suggestion LiveData; restores the original listener on OFF.
 */
internal class ElevateSearchSuggestionsHook(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val iso639Languages = Locale.getISOLanguages().toSet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var host: FragmentActivity? = null
    private var search: SearchBinding? = null

    private fun trace(event: String) {
        Log.i("ElevateSearchTrace", "pid=${android.os.Process.myPid()} ${event}")
    }

    private val queryListenerField by lazy {
        runCatching {
            SearchView::class.java.getDeclaredField("mOnQueryChangeListener").apply {
                isAccessible = true
            }
        }.getOrNull()
    }
    private val suggestionField by lazy {
        runCatching {
            SearchViewModel::class.java.getDeclaredField("_searchSuggestions").apply {
                isAccessible = true
            }
        }.getOrNull()
    }
    private val suggestionJobField by lazy {
        runCatching {
            SearchViewModel::class.java.getDeclaredField("suggestionJob").apply {
                isAccessible = true
            }
        }.getOrNull()
    }
    private val tmdbApiKey: String? by lazy {
        runCatching {
            SearchViewModel::class.java.classLoader
                ?.loadClass("com.lagradost.cloudstream3.ui.search.SearchSuggestionApi")
                ?.getDeclaredField("TMDB_API_KEY")
                ?.apply { isAccessible = true }
                ?.get(null) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: android.view.View, saved: Bundle?
        ) {
            if (fragment.javaClass.name == SEARCH_CLASS) {
                trace("fragment_view_created id=${System.identityHashCode(fragment)}")
                attachSearch(fragment, view)
            }
        }

        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            if (fragment.javaClass.name == SEARCH_CLASS) {
                trace("fragment_resumed id=${System.identityHashCode(fragment)} bound=${search?.fragment === fragment}")
            }
            if (search?.fragment === fragment) search?.resume()
        }

        override fun onFragmentPaused(fm: FragmentManager, fragment: Fragment) {
            if (fragment.javaClass.name == SEARCH_CLASS) {
                trace("fragment_paused id=${System.identityHashCode(fragment)} bound=${search?.fragment === fragment}")
            }
            if (search?.fragment === fragment) search?.pause()
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            if (fragment.javaClass.name == SEARCH_CLASS) {
                trace("fragment_view_destroyed id=${System.identityHashCode(fragment)} bound=${search?.fragment === fragment}")
            }
            if (search?.fragment === fragment) {
                search?.close()
                search = null
            }
        }
    }

    fun start() {
        if (started || tmdbApiKey == null || queryListenerField == null ||
            suggestionField == null || suggestionJobField == null) {
            trace("start_skipped started=$started keyAvailable=${tmdbApiKey != null} queryField=${queryListenerField != null} suggestionsField=${suggestionField != null} jobField=${suggestionJobField != null}")
            return
        }
        trace("start")
        started = true
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? FragmentActivity)?.let(::attachActivity)
    }

    fun stop() {
        if (!started) return
        trace("stop")
        started = false
        application.unregisterActivityLifecycleCallbacks(this)
        detachActivity()
        scope.cancel()
    }

    private fun attachActivity(activity: FragmentActivity) {
        if (!started || host === activity) return
        detachActivity()
        host = activity
        trace("attach_activity id=${System.identityHashCode(activity)}")
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                fragment.view?.let { view ->
                    if (fragment.javaClass.name == SEARCH_CLASS) attachSearch(fragment, view)
                }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun detachActivity() {
        if (host != null) trace("detach_activity id=${System.identityHashCode(host)}")
        search?.close()
        search = null
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        host = null
    }

    private fun attachSearch(fragment: Fragment, root: android.view.View) {
        trace("attach_search_attempt started=$started fragment=${System.identityHashCode(fragment)} root=${System.identityHashCode(root)} alreadyBound=${search?.fragment === fragment && search?.root === root}")
        if (!started || (search?.fragment === fragment && search?.root === root)) return
        search?.close()
        search = null
        val context = fragment.context ?: return
        if (!PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("search_suggestions_enabled", true)) return
        val searchView = root.findViewById<SearchView>(R.id.main_search) ?: return
        val suggestionList = root.findViewById<RecyclerView>(R.id.search_suggestions_recycler) ?: return
        val original = runCatching {
            queryListenerField?.get(searchView) as? SearchView.OnQueryTextListener
        }.getOrNull() ?: return
        val model = fragment.activity?.let {
            ViewModelProvider(it)[SearchViewModel::class.java]
        } ?: return
        @Suppress("UNCHECKED_CAST")
        val suggestions = runCatching {
            suggestionField?.get(model) as? MutableLiveData<List<String>>
        }.getOrNull() ?: return
        trace("attach_search_success fragment=${System.identityHashCode(fragment)} model=${System.identityHashCode(model)}")
        search = SearchBinding(fragment, root, searchView, suggestionList, original, model, suggestions).also {
            it.open()
        }
    }

    /** Derive a TMDB language-region tag from Android's CLDR locale data, without a language map. */
    private fun tmdbLanguage(context: Context): String? {
        val locale = Locale.forLanguageTag(getCurrentLocale(context).replace('_', '-'))
        val language = locale.language
        if (language !in iso639Languages) return null
        val region = locale.country.ifBlank {
            // CloudStream distinguishes pt (Portugal) from pt-BR (Brazil).
            if (language == "pt") "PT"
            else ULocale.addLikelySubtags(ULocale.forLocale(locale)).country
        }
        return if (region.length == 2) "${language}-${region}" else language
    }

    private inner class SearchBinding(
        val fragment: Fragment,
        val root: android.view.View,
        private val view: SearchView,
        private val suggestionList: RecyclerView,
        private val native: SearchView.OnQueryTextListener,
        private val model: SearchViewModel,
        private val suggestions: MutableLiveData<List<String>>
    ) {
        private var generation = 0
        private var pending: Runnable? = null
        private var request: Job? = null
        private var originalAnimator: RecyclerView.ItemAnimator? = null
        private var resumed = fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        private var editedSinceResume = false
        private var submittedQuery: String? = null
        private val input = view.findViewById<View>(androidx.appcompat.R.id.search_src_text)
        private var lastIme: Boolean? = null
        private var lastListVisibility = -1
        private var lastAdapterCount = -1
        private var lastSearchFocus: Boolean? = null
        private var lastInputFocus: Boolean? = null

        private fun snapshot(reason: String) {
            val ime = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime())
            trace("state reason=$reason binding=${System.identityHashCode(root)} resumed=$resumed lifecycle=${fragment.lifecycle.currentState} viewAttached=${fragment.view === root} queryLength=${view.query.length} generation=$generation ime=$ime searchFocus=${view.hasFocus()} inputFocus=${input?.hasFocus()} windowFocus=${root.hasWindowFocus()} listVisibility=${suggestionList.visibility} listShown=${suggestionList.isShown} adapterCount=${suggestionList.adapter?.itemCount} liveCount=${suggestions.value?.size} requestActive=${request?.isActive} pending=${pending != null}")
        }

        private val focusTrace = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
            snapshot("focus ${oldFocus?.javaClass?.simpleName}:${oldFocus?.id} -> ${newFocus?.javaClass?.simpleName}:${newFocus?.id}")
            main.post { if (search === this@SearchBinding) snapshot("after_focus_dispatch") }
        }

        private val layoutTrace = ViewTreeObserver.OnGlobalLayoutListener {
            val ime = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime())
            val listVisibility = suggestionList.visibility
            val adapterCount = suggestionList.adapter?.itemCount ?: -1
            val searchFocus = view.hasFocus()
            val inputFocus = input?.hasFocus()
            if (ime != lastIme || listVisibility != lastListVisibility ||
                adapterCount != lastAdapterCount || searchFocus != lastSearchFocus ||
                inputFocus != lastInputFocus) {
                snapshot("layout_changed")
                lastIme = ime
                lastListVisibility = listVisibility
                lastAdapterCount = adapterCount
                lastSearchFocus = searchFocus
                lastInputFocus = inputFocus
            }
        }

        private val dataTrace = Observer<List<String>> { list ->
            snapshot("suggestions_observed count=${list?.size}")
        }

        private val replacement = object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                snapshot("submit_before queryLength=${query.length}")
                cancelPending()
                submittedQuery = query
                editedSinceResume = false
                val handled = native.onQueryTextSubmit(query)
                snapshot("submit_after_native handled=$handled")
                return handled
            }

            override fun onQueryTextChange(query: String): Boolean {
                snapshot("query_change queryLength=${query.length} editedSinceResume=$editedSinceResume")
                // A restored submitted query is not a new edit; preserve native layout updates.
                if (!resumed || (!editedSinceResume && query.isNotBlank() &&
                        (query == submittedQuery || query == model.lastQuery))) {
                    trace("query_suppressed resumed=$resumed submittedMatch=${query == submittedQuery} lastQueryMatch=${query == model.lastQuery}")
                    val handled = native.onQueryTextChange(query)
                    snapshot("query_suppressed_after_native")
                    dismissSuggestions()
                    return handled
                }
                editedSinceResume = true
                // Native fetchSuggestions() posts an empty list for a one-character query.
                // Keep the existing suggestions visible while the new result is loading.
                if (query.length == 1 && query.isNotBlank()) {
                    trace("query_single_character")
                    cancelNativeRequest()
                    showTypingUi()
                    schedule(query)
                    return true
                }
                val handled = native.onQueryTextChange(query)
                snapshot("query_after_native handled=$handled")
                if (query.isNotBlank()) cancelNativeRequest()
                schedule(query)
                return handled
            }
        }

        private fun cancelNativeRequest() {
            trace("cancel_native_request active=${runCatching { (suggestionJobField?.get(model) as? Job)?.isActive }.getOrNull()}")
            runCatching { (suggestionJobField?.get(model) as? Job)?.cancel() }
        }

        private fun showTypingUi() {
            snapshot("show_typing_ui")
            // Mirror the native SearchFragment's nonempty-query visibility changes.
            val advanced = PreferenceManager.getDefaultSharedPreferences(view.context)
                .getBoolean("advanced_search", true)
            root.findViewById<View>(R.id.search_history_recycler)?.visibility = View.GONE
            root.findViewById<View>(R.id.search_master_recycler)?.visibility =
                if (advanced) View.VISIBLE else View.GONE
            root.findViewById<View>(R.id.search_autofit_results)?.visibility =
                if (advanced) View.GONE else View.VISIBLE
            // Do not change the suggestion overlay; the observer shows the new list.
        }

        fun open() {
            snapshot("open_before")
            originalAnimator = suggestionList.itemAnimator
            suggestionList.itemAnimator = null
            view.setOnQueryTextListener(replacement)
            root.viewTreeObserver.addOnGlobalFocusChangeListener(focusTrace)
            root.viewTreeObserver.addOnGlobalLayoutListener(layoutTrace)
            suggestions.observe(fragment.viewLifecycleOwner, dataTrace)
            snapshot("open_after")
            if (resumed) resume()
        }

        fun pause() {
            snapshot("pause_before")
            resumed = false
            dismissSuggestions()
            snapshot("pause_after")
        }

        fun resume() {
            snapshot("resume_before")
            resumed = true
            editedSinceResume = false
            dismissSuggestions()
            snapshot("resume_after")
        }

        private fun dismissSuggestions() {
            snapshot("dismiss_before")
            cancelPending()
            trace("clear_suggestions_from_elevate")
            model.clearSuggestions()
            suggestionList.visibility = View.GONE
            snapshot("dismiss_after")
        }

        private fun cancelPending() {
            trace("cancel_pending generation=$generation pending=${pending != null} requestActive=${request?.isActive}")
            generation++
            pending?.let(main::removeCallbacks)
            pending = null
            request?.cancel()
            request = null
        }

        private fun schedule(query: String) {
            cancelPending()
            if (query.isBlank()) {
                trace("schedule_skipped_blank")
                return
            }
            val serial = generation
            val language = tmdbLanguage(view.context)
            trace("schedule generation=$serial queryLength=${query.length} language=$language delayMs=120")
            val task = Runnable {
                pending = null
                trace("request_launched generation=$serial queryLength=${query.length}")
                request = scope.launch {
                    val result = fetchSuggestions(query, language)
                    trace("request_completed generation=$serial resultCount=${result.size}")
                    main.post {
                        val accepted = started && search === this@SearchBinding && serial == generation &&
                            resumed && fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            fragment.view === root && view.query.toString() == query
                        snapshot("request_delivery generation=$serial accepted=$accepted resultCount=${result.size}")
                        if (accepted) {
                            suggestions.value = result
                        }
                    }
                }
            }
            pending = task
            main.postDelayed(task, 120L)
        }

        fun close() {
            snapshot("close_before")
            cancelPending()
            suggestions.removeObserver(dataTrace)
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusTrace)
                root.viewTreeObserver.removeOnGlobalLayoutListener(layoutTrace)
            }
            if (runCatching { queryListenerField?.get(view) }.getOrNull() === replacement) {
                view.setOnQueryTextListener(native)
                trace("close_restoring_native_and_clearing_suggestions")
                model.clearSuggestions()
            }
            if (suggestionList.itemAnimator == null) {
                suggestionList.itemAnimator = originalAnimator
            }
            snapshot("close_after")
        }
    }

    private suspend fun fetchSuggestions(query: String, language: String?): List<String> {
        val key = tmdbApiKey ?: return emptyList()
        return try {
            val response = app.get(
                "https://api.themoviedb.org/3/search/multi",
                params = mutableMapOf("api_key" to key, "query" to query).apply {
                    language?.let { put("language", it) }
                },
                cacheTime = 60 * 24,
                timeout = 10L
            )
            val results = JSONObject(response.text).optJSONArray("results") ?: return emptyList()
            val titles = ArrayList<String>(20)
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                if (item.optString("media_type") !in listOf("tv", "movie")) continue
                val title = item.optString("title").takeIf { it.isNotBlank() }
                    ?: item.optString("name").takeIf { it.isNotBlank() }
                    ?: continue
                if (title !in titles) titles.add(title)
                if (titles.size == 20) break
            }
            titles
        } catch (cancel: CancellationException) {
            trace("tmdb_fetch_cancelled")
            throw cancel
        } catch (error: Exception) {
            trace("tmdb_fetch_failed type=${error.javaClass.simpleName} message=${error.message?.take(100)}")
            emptyList()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is FragmentActivity) attachActivity(activity)
        if (activity === host) trace("activity_resumed id=${System.identityHashCode(activity)}")
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) {
        if (activity === host) trace("activity_paused id=${System.identityHashCode(activity)}")
    }
    override fun onActivityStopped(activity: Activity) {
        if (activity === host) trace("activity_stopped id=${System.identityHashCode(activity)}")
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (activity === host) {
            trace("activity_destroyed id=${System.identityHashCode(activity)}")
            detachActivity()
        }
    }

    private companion object {
        const val SEARCH_CLASS = "com.lagradost.cloudstream3.ui.search.SearchFragment"
    }
}
