package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.icu.util.ULocale
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.search.SearchSuggestionAdapter
import com.lagradost.cloudstream3.ui.search.SearchViewModel
import com.lagradost.cloudstream3.ui.settings.Globals
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
            if (fragment.javaClass.name == SEARCH_CLASS) attachSearch(fragment, view)
        }

        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            if (search?.fragment === fragment) search?.resume()
        }

        override fun onFragmentPaused(fm: FragmentManager, fragment: Fragment) {
            if (search?.fragment === fragment) search?.pause()
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            if (search?.fragment === fragment) {
                search?.close()
                search = null
            }
        }
    }

    fun start() {
        if (started || tmdbApiKey == null || queryListenerField == null ||
            suggestionField == null || suggestionJobField == null) return
        started = true
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? FragmentActivity)?.let(::attachActivity)
    }

    fun stop() {
        if (!started) return
        started = false
        application.unregisterActivityLifecycleCallbacks(this)
        detachActivity()
        scope.cancel()
    }

    private fun attachActivity(activity: FragmentActivity) {
        if (!started || host === activity) return
        detachActivity()
        host = activity
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
        search?.close()
        search = null
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        host = null
    }

    private fun attachSearch(fragment: Fragment, root: android.view.View) {
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
        private val originalListHeight = suggestionList.layoutParams.height
        private var pendingFirstPosition: List<String>? = null
        private var layoutObserver: ViewTreeObserver? = null
        private var lastImeVisible: Boolean? = null
        private val viewportListener = ViewTreeObserver.OnGlobalLayoutListener {
            updateSuggestionViewport()
        }

        private fun updateSuggestionViewport() {
            if (!started || search !== this@SearchBinding) return

            if (Globals.isLayout(Globals.PHONE)) {
                val imeVisible = ViewCompat.getRootWindowInsets(root)
                    ?.isVisible(WindowInsetsCompat.Type.ime())
                if (imeVisible != null) {
                    val imeClosed = lastImeVisible == true && !imeVisible
                    lastImeVisible = imeVisible
                    if (imeClosed && resumed && view.query.isNotBlank()) {
                        dismissSuggestions()
                    }
                }
            }

            val params = suggestionList.layoutParams
            if (suggestionList.visibility == View.VISIBLE) {
                val frame = Rect()
                root.getWindowVisibleDisplayFrame(frame)
                val rootLocation = IntArray(2)
                val listLocation = IntArray(2)
                root.getLocationOnScreen(rootLocation)
                suggestionList.getLocationOnScreen(listLocation)
                val maxHeight = (minOf(frame.bottom, rootLocation[1] + root.height) -
                    listLocation[1]).coerceAtLeast(0)
                if (maxHeight > 0) {
                    val height = if (suggestionList.computeVerticalScrollRange() > maxHeight)
                        maxHeight else originalListHeight
                    if (params.height != height) {
                        params.height = height
                        suggestionList.layoutParams = params
                    }
                }

                // AsyncListDiffer can retain the footer as the scroll anchor.
                // Reset only after the submitted TMDB list has actually been committed.
                val pending = pendingFirstPosition
                if (pending != null &&
                    (suggestionList.adapter as? SearchSuggestionAdapter)?.immutableCurrentList == pending) {
                    pendingFirstPosition = null
                    (suggestionList.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(0, 0)
                }
            } else if (params.height != originalListHeight) {
                params.height = originalListHeight
                suggestionList.layoutParams = params
            }
        }

        private val replacement = object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                cancelPending()
                submittedQuery = query
                editedSinceResume = false
                return native.onQueryTextSubmit(query)
            }

            override fun onQueryTextChange(query: String): Boolean {
                // A restored submitted query is not a new edit; preserve native layout updates.
                if (!resumed || (!editedSinceResume && query.isNotBlank() &&
                        (query == submittedQuery || query == model.lastQuery))) {
                    val handled = native.onQueryTextChange(query)
                    dismissSuggestions()
                    return handled
                }
                editedSinceResume = true
                // Native fetchSuggestions() posts an empty list for a one-character query.
                // Keep the existing suggestions visible while the new result is loading.
                if (query.length == 1 && query.isNotBlank()) {
                    cancelNativeRequest()
                    showTypingUi()
                    schedule(query)
                    return true
                }
                val handled = native.onQueryTextChange(query)
                if (query.isNotBlank()) cancelNativeRequest()
                schedule(query)
                return handled
            }
        }

        private fun cancelNativeRequest() {
            runCatching { (suggestionJobField?.get(model) as? Job)?.cancel() }
        }

        private fun showTypingUi() {
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
            originalAnimator = suggestionList.itemAnimator
            suggestionList.itemAnimator = null
            view.setOnQueryTextListener(replacement)
            if (resumed) resume()
            layoutObserver = root.viewTreeObserver.apply {
                addOnGlobalLayoutListener(viewportListener)
            }
        }

        fun pause() {
            resumed = false
            lastImeVisible = null
            dismissSuggestions()
        }

        fun resume() {
            resumed = true
            editedSinceResume = false
            dismissSuggestions()
        }

        private fun dismissSuggestions() {
            cancelPending()
            model.clearSuggestions()
            suggestionList.visibility = View.GONE
        }

        private fun cancelPending() {
            generation++
            pendingFirstPosition = null
            pending?.let(main::removeCallbacks)
            pending = null
            request?.cancel()
            request = null
        }

        private fun schedule(query: String) {
            cancelPending()
            if (query.isBlank()) return
            val serial = generation
            val language = tmdbLanguage(view.context)
            val task = Runnable {
                pending = null
                request = scope.launch {
                    val result = fetchSuggestions(query, language)
                    main.post {
                        if (started && search === this@SearchBinding && serial == generation &&
                            resumed && fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            fragment.view === root && view.query.toString() == query) {
                            pendingFirstPosition = result.takeIf { it.isNotEmpty() }
                            suggestions.value = result
                            suggestionList.post { updateSuggestionViewport() }
                        }
                    }
                }
            }
            pending = task
            main.postDelayed(task, 120L)
        }

        fun close() {
            layoutObserver?.takeIf { it.isAlive }
                ?.removeOnGlobalLayoutListener(viewportListener)
            layoutObserver = null
            if (suggestionList.layoutParams.height != originalListHeight) {
                suggestionList.layoutParams = suggestionList.layoutParams.apply {
                    height = originalListHeight
                }
            }
            cancelPending()
            if (runCatching { queryListenerField?.get(view) }.getOrNull() === replacement) {
                view.setOnQueryTextListener(native)
                model.clearSuggestions()
            }
            if (suggestionList.itemAnimator == null) {
                suggestionList.itemAnimator = originalAnimator
            }
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
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is FragmentActivity) attachActivity(activity)
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (activity === host) detachActivity()
    }

    private companion object {
        const val SEARCH_CLASS = "com.lagradost.cloudstream3.ui.search.SearchFragment"
    }
}
