package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.icu.util.ULocale
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.lifecycle.Observer
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

        // Temporary read-only tracing; native focus and IME handlers are not replaced.
        private val queryInput = view.findViewById<View>(androidx.appcompat.R.id.search_src_text)
        private val nativeFocusCallback = runCatching {
            SearchView::class.java.getDeclaredField("mOnQueryTextFocusChangeListener")
                .apply { isAccessible = true }.get(view) != null
        }.getOrNull()
        private var focusObserverTree: ViewTreeObserver? = null
        private var observedAdapter: RecyclerView.Adapter<*>? = null
        private var lastLayoutSnapshot: String? = null
        private val focusObserver = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
            traceSearch("focus_from=${focusName(oldFocus)}_to=${focusName(newFocus)}", true)
        }
        private val suggestionObserver = Observer<List<String>> { current ->
            traceSearch("suggestion_emitted_count=${current.size}", true)
        }
        private val adapterObserver = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                traceSearch("adapter_changed", true)
            }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                traceSearch("adapter_insert_at=$positionStart,count=$itemCount", true)
            }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                traceSearch("adapter_remove_at=$positionStart,count=$itemCount", true)
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                traceSearch("adapter_update_at=$positionStart,count=$itemCount", true)
            }
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                traceSearch("adapter_move_from=$fromPosition,to=$toPosition,count=$itemCount", true)
            }
        }

        private fun focusName(target: View?): String = when (target) {
            null -> "none"
            queryInput -> "search_input"
            view -> "search_view"
            suggestionList -> "suggestions"
            else -> "${target.javaClass.simpleName}#${target.id}"
        }

        private fun traceSearch(event: String, force: Boolean = false) {
            val frame = Rect()
            root.getWindowVisibleDisplayFrame(frame)
            val insets = ViewCompat.getRootWindowInsets(root)
            val imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime())
            val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom
            val list = (suggestionList.adapter as? SearchSuggestionAdapter)?.immutableCurrentList
            val snapshot = listOf(
                imeVisible, imeBottom, frame.bottom, root.height, suggestionList.height,
                view.hasFocus(), queryInput?.hasFocus(), focusName(root.rootView.findFocus()),
                suggestions.value?.size, list?.size, suggestionList.adapter?.itemCount,
                suggestionList.visibility, suggestionList.canScrollVertically(1),
                fragment.lifecycle.currentState
            ).joinToString("|")
            if (!force && snapshot == lastLayoutSnapshot) return
            lastLayoutSnapshot = snapshot
            Log.i(TRACE_TAG,
                "event=$event ime=$imeVisible imeBottom=$imeBottom windowBottom=${frame.bottom} " +
                    "rootHeight=${root.height} listHeight=${suggestionList.height} " +
                    "listVisibility=${suggestionList.visibility} dataCount=${suggestions.value?.size} " +
                    "adapterDataCount=${list?.size} adapterItemCount=${suggestionList.adapter?.itemCount} " +
                    "canScrollDown=${suggestionList.canScrollVertically(1)} " +
                    "searchHasFocus=${view.hasFocus()} inputHasFocus=${queryInput?.hasFocus()} " +
                    "focused=${focusName(root.rootView.findFocus())} " +
                    "queryLength=${view.query.length} resumed=$resumed lifecycle=${fragment.lifecycle.currentState} " +
                    "pending=${pending != null} requestActive=${request?.isActive} generation=$generation"
            )
        }

        private val viewportListener = ViewTreeObserver.OnGlobalLayoutListener {
            updateSuggestionViewport()
            traceSearch("layout")
        }

        private fun updateSuggestionViewport() {
            if (!started || search !== this@SearchBinding) return

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
                traceSearch("query_submitted_length=${query.length}", true)
                cancelPending()
                submittedQuery = query
                editedSinceResume = false
                return native.onQueryTextSubmit(query)
            }

            override fun onQueryTextChange(query: String): Boolean {
                traceSearch("query_changed_length=${query.length}", true)
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
            focusObserverTree = root.viewTreeObserver.apply {
                addOnGlobalFocusChangeListener(focusObserver)
            }
            observedAdapter = suggestionList.adapter?.also {
                it.registerAdapterDataObserver(adapterObserver)
            }
            suggestions.observe(fragment.viewLifecycleOwner, suggestionObserver)
            val layoutPreference = PreferenceManager.getDefaultSharedPreferences(view.context)
                .getInt(view.context.getString(R.string.app_layout_key), -1)
            traceSearch(
                "open_layoutPref=$layoutPreference,phone=${Globals.isLayout(Globals.PHONE)}," +
                    "tv=${Globals.isLayout(Globals.TV)},emulator=${Globals.isLayout(Globals.EMULATOR)}," +
                    "nativeFocusCallback=$nativeFocusCallback," +
                    "softInputMode=${fragment.activity?.window?.attributes?.softInputMode}," +
                    "orientation=${root.resources.configuration.orientation}",
                true
            )
        }

        fun pause() {
            traceSearch("fragment_pause", true)
            resumed = false
            dismissSuggestions()
        }

        fun resume() {
            traceSearch("fragment_resume", true)
            resumed = true
            editedSinceResume = false
            dismissSuggestions()
        }

        private fun dismissSuggestions() {
            traceSearch("dismiss_before", true)
            cancelPending()
            model.clearSuggestions()
            suggestionList.visibility = View.GONE
            traceSearch("dismiss_after", true)
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
                            traceSearch("tmdb_result_accepted_count=${result.size}", true)
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
            traceSearch("close", true)
            focusObserverTree?.takeIf { it.isAlive }
                ?.removeOnGlobalFocusChangeListener(focusObserver)
            focusObserverTree = null
            observedAdapter?.unregisterAdapterDataObserver(adapterObserver)
            observedAdapter = null
            suggestions.removeObserver(suggestionObserver)
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
        const val TRACE_TAG = "ElevateSearchImeTrace"
        const val SEARCH_CLASS = "com.lagradost.cloudstream3.ui.search.SearchFragment"
    }
}
