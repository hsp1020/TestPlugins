package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.icu.util.ULocale
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
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

        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            if (search?.fragment === fragment) {
                search?.close()
                search = null
            }
        }
    }

    fun start() {
        if (started || tmdbApiKey == null || queryListenerField == null || suggestionField == null) return
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
        search = SearchBinding(fragment, root, searchView, original, model, suggestions).also {
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
        private val native: SearchView.OnQueryTextListener,
        private val model: SearchViewModel,
        private val suggestions: MutableLiveData<List<String>>
    ) {
        private var generation = 0
        private var pending: Runnable? = null
        private var request: Job? = null

        private val replacement = object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                cancelPending()
                return native.onQueryTextSubmit(query)
            }

            override fun onQueryTextChange(query: String): Boolean {
                val handled = native.onQueryTextChange(query)
                // Cancel the original 300ms English-language request after native UI handling.
                model.clearSuggestions()
                schedule(query)
                return handled
            }
        }

        fun open() {
            view.setOnQueryTextListener(replacement)
        }

        private fun cancelPending() {
            generation++
            pending?.let(main::removeCallbacks)
            pending = null
            request?.cancel()
            request = null
        }

        private fun schedule(query: String) {
            cancelPending()
            if (query.isBlank() || query.length < 2) return
            val serial = generation
            val language = tmdbLanguage(view.context)
            val task = Runnable {
                pending = null
                request = scope.launch {
                    val result = fetchSuggestions(query, language)
                    main.post {
                        if (started && search === this@SearchBinding && serial == generation &&
                            fragment.view === root && view.query.toString() == query) {
                            suggestions.value = result
                        }
                    }
                }
            }
            pending = task
            main.postDelayed(task, 120L)
        }

        fun close() {
            cancelPending()
            if (runCatching { queryListenerField?.get(view) }.getOrNull() === replacement) {
                view.setOnQueryTextListener(native)
                model.clearSuggestions()
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
