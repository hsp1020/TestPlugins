package com.hsp1020

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.StaticLayout
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import androidx.cardview.widget.CardView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.appcompat.app.AppCompatDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.result.ResultData
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class ActorPersonRef(
    val id: Int?,
    val name: String,
    val photo: String?,
    val preferCrew: Boolean
)

internal object ActorInformation {
    private data class Snapshot(
        val providerUrl: String,
        val detailUrl: String,
        val actors: List<ActorData>,
        val people: List<ActorPersonRef>,
        val customPosterSource: String?,
        val metadataHost: String?
    )

    private val snapshots = object : LinkedHashMap<String, Snapshot>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>) = size > 100
    }
    private fun key(source: String, detail: String) = stremioManifestUrl(source) + "|" + detail

    @Synchronized
    fun register(
        provider: StremioC, url: String, actors: List<ActorData>?,
        people: List<ActorPersonRef>, customPosterSource: String?, metadataHost: String?
    ) {
        if (actors.isNullOrEmpty() || actors.size != people.size) return
        snapshots[key(provider.mainUrl, url)] =
            Snapshot(stremioManifestUrl(provider.mainUrl), url, actors, people,
                customPosterSource, metadataHost)
    }

    @Synchronized
    fun alias(provider: StremioC, from: String, to: String) {
        if (from == to) return
        val original = snapshots[key(provider.mainUrl, to)] ?: return
        snapshots[key(provider.mainUrl, from)] = original
    }

    @Synchronized
    private fun resolve(
        url: String?,
        repo: StremioC?,
        page: ResultData,
        position: Int,
        item: ActorData
    ): Pair<String, ActorPersonRef>? {
        if (position < 0 || page.actors?.getOrNull(position) !== item) return null

        val matches = snapshots.values.distinct().filter { snapshot ->
            snapshot.actors === page.actors && snapshot.actors.getOrNull(position) === item &&
                snapshot.people.getOrNull(position)?.name == item.actor.name &&
                (url == null || snapshot.detailUrl == url || page.url == snapshot.detailUrl ||
                    snapshots[key(snapshot.providerUrl, url)] === snapshot)
        }
        val matched = matches.singleOrNull()
            ?: return null
        if (repo != null && stremioManifestUrl(repo.mainUrl) != matched.providerUrl &&
            matches.size != 1) return null
        return matched.providerUrl to matched.people[position]
    }

    @Synchronized
    private fun posterFor(source: String?, detailUrl: String, credit: Credit): String? {
        val snapshot = source?.let { snapshots[key(it, detailUrl)] }
        return StremioC.actorPosterUrl(
            snapshot?.customPosterSource, credit.id, credit.media == "movie",
            credit.poster, snapshot?.metadataHost
        )
    }

    private data class CacheEntry(val bytes: ByteArray, val time: Long)
    private val cache = LinkedHashMap<String, CacheEntry>(50, 0.75f, true)
    private var cacheBytes = 0L
    private const val MAX_CACHE_ENTRIES = 50
    private const val MAX_CACHE_BYTES = 8L * 1024 * 1024
    private val flights = mutableMapOf<String, Deferred<JSONObject>>()
    private val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val TTL = 12L * 60 * 60 * 1000

    private fun configuration(context: Context): Pair<String, String> {
        val prefs = context.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
        return (prefs.getString("tmdb_api_key", "") ?: "") to
            (prefs.getString("tmdb_language_code", "en-US") ?: "en-US")
    }

    private suspend fun request(
        personId: Int, endpoint: String, language: String, apiKey: String
    ): JSONObject {
        if (apiKey.isBlank()) throw IllegalStateException("TMDB API key is not configured")
        val url = android.net.Uri.parse("https://api.themoviedb.org/3/person/" + personId + endpoint)
            .buildUpon().appendQueryParameter("language", language)
            .appendQueryParameter("api_key", apiKey).build().toString()
        val response = StremioC.customSession.get(url, timeout = 15L)
        if (response.code !in 200..299) {
            throw IllegalStateException(when (response.code) {
                401 -> "TMDB API key was rejected (401)"
                429 -> "TMDB rate limit reached (429). Retry later"
                else -> "TMDB HTTP " + response.code
            })
        }
        return JSONObject(response.text)
    }

    private suspend fun cached(personId: Int, endpoint: String, lang: String, apiKey: String): JSONObject {
        val cacheKey = personId.toString() + "|" + endpoint + "|" + lang + "|" + apiKey
        val hit = synchronized(cache) {
            val entry = cache[cacheKey]
            if (entry != null && System.currentTimeMillis() - entry.time < TTL) {
                entry.bytes
            } else {
                cache.remove(cacheKey)?.let { cacheBytes -= it.bytes.size.toLong() }
                null
            }
        }
        if (hit != null) {
            runCatching { JSONObject(String(hit, Charsets.UTF_8)) }.getOrNull()?.let {
                return it
            }
            synchronized(cache) {
                cache.remove(cacheKey)?.let { cacheBytes -= it.bytes.size.toLong() }
            }
        }
        val task = synchronized(flights) {
            flights[cacheKey] ?: sharedScope.async {
                request(personId, endpoint, lang, apiKey)
            }.also {
                flights[cacheKey] = it
            }
        }
        try {
            val result = task.await()
            val bytes = result.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size.toLong() <= MAX_CACHE_BYTES) {
                synchronized(cache) {
                    cache.remove(cacheKey)?.let { cacheBytes -= it.bytes.size.toLong() }
                    cache[cacheKey] = CacheEntry(bytes, System.currentTimeMillis())
                    cacheBytes += bytes.size.toLong()
                    val iterator = cache.entries.iterator()
                    while ((cache.size > MAX_CACHE_ENTRIES || cacheBytes > MAX_CACHE_BYTES) &&
                        iterator.hasNext()) {
                        val evicted = iterator.next()
                        cacheBytes -= evicted.value.bytes.size.toLong()
                        iterator.remove()
                    }
                }
            } else {
            }
            return result
        } catch (error: Throwable) {
            throw error
        } finally {
            if (task.isCompleted) synchronized(flights) {
                if (flights[cacheKey] === task) flights.remove(cacheKey)
            }
        }
    }

    private data class Credit(
        val id: Int,
        val media: String,
        val title: String,
        val poster: String?,
        val date: String?,
        val popularity: Double
    )
    private fun credits(json: JSONObject, preferCrew: Boolean): List<Credit> {
        val primary = json.optJSONArray(if (preferCrew) "crew" else "cast") ?: JSONArray()
        val fallback = json.optJSONArray(if (preferCrew) "cast" else "crew") ?: JSONArray()
        val selected = if (primary.length() > 0) primary else fallback
        val unique = LinkedHashMap<String, Credit>()
        for (i in 0 until selected.length()) {
            val value = selected.optJSONObject(i) ?: continue
            val id = value.optInt("id")
            val media = value.optString("media_type")
            if (id <= 0 || media != "movie" && media != "tv") continue
            val title = value.optString(if (media == "movie") "title" else "name")
                .ifBlank { value.optString(if (media == "movie") "original_title" else "original_name") }
            if (title.isBlank()) continue
            val poster = value.optString("poster_path").takeIf { it.startsWith("/") }
            val date = value.optString(if (media == "movie") "release_date" else "first_air_date")
                .takeIf { it.length == 10 && it[4] == '-' && it[7] == '-' }
            val credit = Credit(id, media, title, poster, date, value.optDouble("popularity", 0.0))
            val k = media + ":" + id
            if (unique[k] == null || credit.popularity > unique[k]!!.popularity) unique[k] = credit
        }
        return unique.values.toList()
    }

    internal class Controller(private val fragment: Fragment, private val sourceUrl: String?) {
        private val model by lazy { ViewModelProvider(fragment)[ResultViewModel2::class.java] }
        private val stateKey = "stremioc_actor_info_return"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var dialog: AppCompatDialog? = null
        private var detailHandoff: StremioDetailHandoff? = null
        private var originState: androidx.lifecycle.SavedStateHandle? = null
        private var requestJob: kotlinx.coroutines.Job? = null
        private var preserve = false
        private var currentPerson: ActorPersonRef? = null
        private var currentSource: String? = null
        private var currentOrigin: String? = null
        private var scroll: ScrollView? = null
        private var bioExpanded = false
        private val creditScroll = mutableMapOf<String, Int>()
        private var closed = false
        private val resumeObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) maybeRestore()
        }
        private val pageObserver = Observer<Resource<ResultData>?> { maybeRestore() }

        fun start() {
            fragment.viewLifecycleOwner.lifecycle.addObserver(resumeObserver)
            model.page.observe(fragment.viewLifecycleOwner, pageObserver)
        }

        fun click(position: Int, item: ActorData) {
            val page = (model.page.value as? Resource.Success<ResultData>)?.value ?: return
            val provider = model.currentRepo?.api as? StremioC
            val actualUrl = sourceUrl ?: page.url
            val ref = resolve(actualUrl, provider, page, position, item)
            bioExpanded = false
            creditScroll.clear()
            if (ref == null) {
                show(null, ActorPersonRef(null, item.actor.name, item.actor.image, false), page.url)
            } else show(ref.first, ref.second, page.url)
        }

        private fun state(): androidx.lifecycle.SavedStateHandle? =
            originState ?: runCatching {
                fragment.findNavController().currentBackStackEntry?.savedStateHandle
            }.getOrNull()

        private fun save() {
            val actor = currentPerson ?: return
            val origin = currentOrigin ?: return
            state()?.set(stateKey, JSONObject().apply {
                put("origin", origin)
                put("source", currentSource)
                put("id", actor.id)
                put("name", actor.name)
                put("photo", actor.photo)
                put("crew", actor.preferCrew)
                put("scroll", scroll?.scrollY ?: 0)
                put("creditScroll", JSONObject(creditScroll.toMap()))
                put("expanded", bioExpanded)
            }.toString())
        }

        private fun clearState() {
            state()?.remove<String>(stateKey)
        }

        private fun maybeRestore() {
            if (closed || dialog?.isShowing == true || detailHandoff?.isActive == true ||
                !fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            val page = (model.page.value as? Resource.Success<ResultData>)?.value ?: return
            val raw = state()?.get<String>(stateKey) ?: return
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
            if (obj.optString("origin") != page.url) return
            val ref = ActorPersonRef(
                obj.optInt("id").takeIf { it > 0 },
                obj.optString("name"),
                obj.optString("photo").takeIf { it.isNotBlank() && it != "null" },
                obj.optBoolean("crew")
            )
            bioExpanded = obj.optBoolean("expanded")
            creditScroll.clear()
            obj.optJSONObject("creditScroll")?.let { saved ->
                saved.keys().forEach { name -> creditScroll[name] = saved.optInt(name) }
            }
            show(obj.optString("source").takeIf { it.isNotBlank() && it != "null" },
                ref, page.url, obj.optInt("scroll"))
        }

        private fun dp(n: Int): Int = (n * fragment.resources.displayMetrics.density).toInt()
        private fun label(text: String, size: Float = 15f, strong: Boolean = false) =
            TextView(fragment.requireContext()).apply {
                this.text = text
                textSize = size
                setTextColor(fragment.requireContext().colorFromAttribute(R.attr.textColor))
                if (strong) typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }

        private fun column() = LinearLayout(fragment.requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        private fun show(originProvider: String?, actor: ActorPersonRef, origin: String, resumeY: Int = 0) {
            if (!fragment.isAdded || closed) return
            if (dialog?.isShowing == true && currentPerson == actor && currentSource == originProvider) return
            originState = runCatching {
                fragment.findNavController().currentBackStackEntry?.savedStateHandle
            }.getOrNull()
            requestJob?.cancel()
            detailHandoff?.close()
            detailHandoff = null
            preserve = true
            dialog?.dismiss()
            currentPerson = actor
            currentSource = originProvider
            currentOrigin = origin
            preserve = false

            val activity = fragment.activity ?: return
            val windowDialog = AppCompatDialog(activity, R.style.DialogFullscreen)
            val scrollListeners = mutableListOf<Pair<View, ViewTreeObserver.OnScrollChangedListener>>()
            val navigation = (activity.supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)?.navController
            val handoff = StremioDetailHandoff(
                activity, windowDialog, navigation,
                onLeave = {
                    if (dialog === windowDialog) {
                        preserve = false
                        windowDialog.dismiss()
                    }
                },
                onReturn = {
                    if (closed) {
                        preserve = true
                        windowDialog.dismiss()
                    } else windowDialog.show()
                },
                onReady = {

                    if (closed && dialog === windowDialog) {
                        preserve = true
                        windowDialog.dismiss()
                    }
                }
            )
            detailHandoff = handoff
            val originalBack = fragment.view?.findViewById<View>(R.id.result_back)
                ?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
            val backScreen = originalBack?.let { original ->
                IntArray(2).also { original.getLocationOnScreen(it) }
            }
            val root = column().apply {
                setBackgroundColor(activity.colorFromAttribute(R.attr.primaryBlackBackground))
                setPadding(0, 0, 0, dp(20))
            }
            val heading = FrameLayout(activity)
            val back = ImageView(activity).apply {
                setImageResource(R.drawable.ic_baseline_arrow_back_24)
                imageTintList = ColorStateList.valueOf(activity.colorFromAttribute(R.attr.white))
                contentDescription = activity.getString(R.string.go_back)
                isFocusable = true
                isClickable = true
                val ripple = TypedValue()
                if (activity.theme.resolveAttribute(
                        android.R.attr.selectableItemBackgroundBorderless, ripple, true
                    ) && ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
                setOnClickListener { windowDialog.dismiss() }
            }
            val loadingName = label(actor.name, 20f, true)
            heading.addView(back, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.START))
            heading.addView(loadingName,
                FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            root.addView(heading, LinearLayout.LayoutParams(-1, dp(50)))

            val scroller = ScrollView(activity).apply {
                isFillViewport = true
                setPadding(dp(10), 0, dp(10), 0)
            }
            scroll = scroller
            val wide = fragment.resources.configuration.screenWidthDp >= 900
            val body = LinearLayout(activity).apply {
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            }
            val bodyFrame = FrameLayout(activity)
            bodyFrame.addView(body, FrameLayout.LayoutParams(-1, -2))
            val skeleton = StremioLoading(activity, 3)
            bodyFrame.addView(skeleton, FrameLayout.LayoutParams(-1, -1))
            body.visibility = View.INVISIBLE
            skeleton.loading(true)
            scroller.addView(bodyFrame)
            root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
            fun revealBody() {
                body.doOnPreDraw {
                    if (dialog === windowDialog) {
                        body.visibility = View.VISIBLE
                        loadingName.visibility = View.GONE
                        skeleton.loading(false)
                    }
                }
            }
            val profileRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }

            val portraitHolder = CardView(activity).apply {
                radius = dp(35).toFloat()
                setCardBackgroundColor(activity.colorFromAttribute(R.attr.primaryGrayBackground))
                addView(ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    foreground = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(2), androidx.core.content.ContextCompat.getColor(
                            activity, R.color.white_attr_20))
                    }
                    actor.photo?.let { loadImage(it) }
                }, ViewGroup.LayoutParams(-1, -1))
            }

            profileRow.addView(portraitHolder, LinearLayout.LayoutParams(dp(70), dp(70)).apply {
                topMargin = dp(12)
            })
            fun fact(text: String) = label(text, 16f).apply {
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }
            val details = column()

            details.addView(label(actor.name, 24f, true))
            profileRow.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            var profileLoaded = false
            details.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (!profileLoaded || dialog !== windowDialog) return@addOnLayoutChangeListener

                val maxDiameter = (profileRow.width - dp(140)).coerceAtLeast(dp(70))

                val lastPadding = (details.getChildAt(details.childCount - 1) as? TextView)
                    ?.paddingBottom ?: 0
                val diameter = (details.height - dp(12) - lastPadding)
                    .coerceIn(dp(70), maxDiameter)
                val params = portraitHolder.layoutParams
                if (params.width != diameter || params.height != diameter) {
                    params.width = diameter
                    params.height = diameter
                    portraitHolder.layoutParams = params
                    portraitHolder.radius = diameter / 2f
                }
            }
            val aboutPane = column()
            aboutPane.addView(profileRow)
            val biography = label("Loading biography…")
            val toggle = label("More", 13f, true).apply {
                visibility = View.GONE
                isClickable = true
                isFocusable = true
                foreground = themedPressedForeground(this)
            }

            var biographyReady = false
            fun updateBiographyToggle() {
                if (!biographyReady || dialog !== windowDialog) return
                val contentWidth = biography.width - biography.compoundPaddingLeft -
                    biography.compoundPaddingRight
                if (contentWidth <= 0) return
                val text = biography.text
                val fullLines = StaticLayout.Builder.obtain(
                    text, 0, text.length, biography.paint, contentWidth
                ).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(biography.includeFontPadding)
                    .setLineSpacing(biography.lineSpacingExtra, biography.lineSpacingMultiplier)
                    .setBreakStrategy(biography.breakStrategy)
                    .setHyphenationFrequency(biography.hyphenationFrequency)
                    .build().lineCount
                val configuration = fragment.resources.configuration
                val collapsedLines = if (
                    configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                    configuration.smallestScreenWidthDp < 600 && !isLayout(TV or EMULATOR)
                ) 8 else 20
                val truncated = fullLines > collapsedLines
                if (!truncated) bioExpanded = false
                biography.maxLines = if (truncated && !bioExpanded) collapsedLines else Int.MAX_VALUE
                toggle.visibility = if (truncated) View.VISIBLE else View.GONE
                toggle.text = if (bioExpanded) "Less" else "More"
            }
            biography.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) updateBiographyToggle()
            }
            toggle.setOnClickListener {
                bioExpanded = !bioExpanded
                updateBiographyToggle()
                save()
            }
            aboutPane.addView(biography)
            aboutPane.addView(toggle)

            body.addView(aboutPane, if (wide) LinearLayout.LayoutParams(0, -2, 1f)
                else LinearLayout.LayoutParams(-1, -2))
            val filmArea = column()
            body.addView(filmArea, if (wide) LinearLayout.LayoutParams(0, -2, 3f)
                else LinearLayout.LayoutParams(-1, -2))
            root.doOnPreDraw {
                if (dialog !== windowDialog) return@doOnPreDraw
                val rootScreen = IntArray(2).also { root.getLocationOnScreen(it) }
                val left = backScreen?.get(0)?.minus(rootScreen[0])?.coerceAtLeast(0) ?: dp(15)
                val top = backScreen?.get(1)?.minus(rootScreen[1])?.coerceAtLeast(0) ?: dp(8)
                (back.layoutParams as FrameLayout.LayoutParams).apply {
                    leftMargin = left
                    topMargin = top
                    back.layoutParams = this
                }
                (loadingName.layoutParams as FrameLayout.LayoutParams).apply {
                    leftMargin = left + dp(46)
                    rightMargin = dp(10)
                    loadingName.layoutParams = this
                }
                (heading.layoutParams as LinearLayout.LayoutParams).apply {
                    height = (top + dp(42)).coerceAtLeast(dp(50))
                    heading.layoutParams = this
                }
            }
            windowDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            windowDialog.setContentView(root)
            windowDialog.window?.setLayout(-1, -1)
            windowDialog.setOnDismissListener {
                scrollListeners.forEach { (view, listener) ->
                    view.viewTreeObserver.takeIf { it.isAlive }?.removeOnScrollChangedListener(listener)
                }
                scrollListeners.clear()
                handoff.close()
                if (detailHandoff === handoff) detailHandoff = null
                requestJob?.cancel()
                requestJob = null
                skeleton.loading(false)
                dialog = null
                scroll = null
                if (!preserve && !closed) clearState()
            }
            dialog = windowDialog
            windowDialog.show()
            windowDialog.window?.setLayout(-1, -1)
            save()
            scroller.post { scroller.scrollTo(0, resumeY) }
            val scrollerListener = ViewTreeObserver.OnScrollChangedListener {
                if (dialog === windowDialog) save()
            }
            scroller.viewTreeObserver.addOnScrollChangedListener(scrollerListener)
            scrollListeners.add(scroller to scrollerListener)

            if (actor.id == null) {
                biography.text = "TMDB person ID unavailable for this actor."
                revealBody()
                return
            }
            val (key, language) = configuration(activity)
            if (key.isBlank()) {
                biography.text = "Set a TMDB API key in StremioC settings."
                revealBody()
                return
            }
            fun showError(target: LinearLayout?, message: String, retry: () -> Unit) {
                val container = target ?: body
                container.addView(label(message))
                container.addView(label("Retry", 15f, true).apply {
                    isClickable = true
                    isFocusable = true
                    foreground = themedPressedForeground(this)
                    setOnClickListener { retry() }
                })
            }
            var ticket = 0
            fun begin() {
                requestJob?.cancel()
                ticket++
                val generation = ticket
                var creditsInsertAt = 1
                profileLoaded = false
                skeleton.loading(true)
                body.visibility = View.INVISIBLE
                loadingName.visibility = View.VISIBLE
                details.removeViews(1, details.childCount - 1)
                biographyReady = false
                biography.text = "Loading biography…"
                toggle.visibility = View.GONE
                filmArea.removeAllViews()
                filmArea.addView(label("Loading credits…"))
                requestJob = scope.launch {
                    val profile = async(Dispatchers.IO) {
                        try {
                            val first = cached(actor.id, "", language, key)
                            val bio = first.optString("biography")
                            if (bio.isBlank() && !language.startsWith("en", true)) {
                                val english = runCatching { cached(actor.id, "", "en-US", key) }.getOrNull()
                                if (english != null && english.optString("biography").isNotBlank())
                                    first.put("biography", english.optString("biography"))
                            }
                            Result.success(first)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Result.failure<JSONObject>(e) }
                    }
                    val work = async(Dispatchers.IO) {
                        try { Result.success(cached(actor.id, "/combined_credits", language, key)) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Result.failure<JSONObject>(e) }
                    }
                    val personResult = profile.await()
                    if (dialog !== windowDialog || generation != ticket) return@launch
                    personResult.onSuccess { info ->
                        val birth = info.optString("birthday").takeIf { it.isNotBlank() && it != "null" }
                        val death = info.optString("deathday").takeIf { it.isNotBlank() && it != "null" }
                        val place = info.optString("place_of_birth").takeIf { it.isNotBlank() && it != "null" }
                        val known = info.optString("known_for_department").takeIf { it.isNotBlank() }
                        creditsInsertAt = if (known != null) 2 else 1
                        listOfNotNull(known?.let { "Known for: " + it },
                            birth?.let { "Born: " + it + ageText(it, death) },
                            death?.let { "Died: " + it },
                            place).forEach { details.addView(fact(it)) }
                        profileLoaded = true
                        val about = info.optString("biography").ifBlank { "No biography available." }
                        biography.text = about
                        biographyReady = true
                        updateBiographyToggle()
                        biography.doOnPreDraw {
                            if (dialog === windowDialog && generation == ticket)
                                updateBiographyToggle()
                        }
                    }.onFailure { error ->
                        profileLoaded = true
                        biography.text = "Biography unavailable: " + (error.message ?: "Network error")
                        details.addView(label("Retry profile", 14f, true).apply {
                            isClickable = true
                            isFocusable = true
                            foreground = themedPressedForeground(this)
                            setOnClickListener { begin() }
                        })
                    }
                    val workResult = work.await()
                    if (dialog !== windowDialog || generation != ticket) return@launch
                    filmArea.removeAllViews()
                    workResult.onSuccess {
                        val all = credits(it, actor.preferCrew)
                        if (all.isNotEmpty()) {

                            val counted = all.distinctBy(Credit::id)
                            val firstYear = counted.mapNotNull { credit ->
                                credit.date?.take(4)?.toIntOrNull()
                            }.minOrNull()
                            val summary = "${counted.size} ${if (counted.size == 1) "title" else "titles"}" +
                                (firstYear?.let { " · since $it" } ?: "")
                            details.addView(fact(summary), creditsInsertAt)
                        }
                        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        val popular = all.sortedByDescending(Credit::popularity)
                        val latest = all.filter { item -> item.date != null && item.date <= today }
                            .sortedByDescending(Credit::date)
                        val upcoming = all.filter { item -> item.date != null && item.date > today }
                            .sortedBy(Credit::date)
                        if (all.isEmpty()) filmArea.addView(label("No credits available."))
                        listOf(Triple("Popular", popular, 35), Triple("Latest", latest, 35),
                            Triple("Upcoming", upcoming, 35)).forEach { (title, entries, limit) ->
                            if (entries.isNotEmpty()) {
                                filmArea.addView(label(title, 19f, true))
                                val strip = HorizontalScrollView(activity)
                                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                                entries.take(limit).forEach { credit ->
                                    val card = column().apply {
                                        isClickable = true
                                        isFocusable = true
                                        setBackgroundResource(R.drawable.outline_drawable)
                                        setPadding(dp(5), dp(3), dp(5), dp(8))
                                        setOnClickListener { openCredit(originProvider, credit, it) }
                                    }

                                    val posterHolder = CardView(activity).apply {
                                        radius = activity.resources.getDimension(R.dimen.rounded_image_radius)
                                        cardElevation = 0f
                                        setCardBackgroundColor(activity.colorFromAttribute(R.attr.primaryGrayBackground))
                                        isDuplicateParentStateEnabled = true
                                        addView(ImageView(activity).apply {
                                            scaleType = ImageView.ScaleType.CENTER_CROP
                                            isDuplicateParentStateEnabled = true
                                            foreground = themedPressedForeground(this)
                                            val url = posterFor(originProvider, origin, credit)
                                            if (url != null) loadImage(url)
                                            else loadImage(R.drawable.default_cover)
                                        }, ViewGroup.LayoutParams(-1, -1))
                                    }

                                    val posterWidth = dp(114)
                                    card.addView(posterHolder, LinearLayout.LayoutParams(posterWidth, dp(180)))
                                    card.addView(label(credit.title, 13f).apply {
                                        maxLines = 2
                                        setPadding(0, dp(6), 0, 0)
                                    }, LinearLayout.LayoutParams(posterWidth, -2))
                                    card.addView(label((credit.date?.take(4) ?: "") +
                                        (if (credit.media == "movie") " · Movie" else " · Series"), 11f).apply {
                                        setPadding(0, dp(6), 0, 0)
                                    }, LinearLayout.LayoutParams(posterWidth, -2))
                                    row.addView(card, LinearLayout.LayoutParams(dp(124), -2))
                                }
                                strip.addView(row)
                                strip.post { if (dialog === windowDialog) strip.scrollTo(creditScroll[title] ?: 0, 0) }
                                val stripListener = ViewTreeObserver.OnScrollChangedListener {
                                    if (dialog === windowDialog) {
                                        creditScroll[title] = strip.scrollX
                                        save()
                                    }
                                }
                                strip.viewTreeObserver.addOnScrollChangedListener(stripListener)
                                scrollListeners.add(strip to stripListener)
                                filmArea.addView(strip)
                            }
                        }
                    }.onFailure { error ->
                        showError(filmArea, "Credits unavailable: " +
                            (error.message ?: "Network error")) { begin() }
                    }
                    revealBody()
                }
            }
            begin()
        }

        private fun ageText(birth: String, death: String?): String {
            val pattern = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
            val start = runCatching { pattern.parse(birth) }.getOrNull() ?: return ""
            val end = if (death.isNullOrBlank()) Date() else
                runCatching { pattern.parse(death) }.getOrNull() ?: return ""
            val beginDate = java.util.Calendar.getInstance().apply { time = start }
            val endDate = java.util.Calendar.getInstance().apply { time = end }
            var years = endDate.get(java.util.Calendar.YEAR) - beginDate.get(java.util.Calendar.YEAR)
            if (endDate.get(java.util.Calendar.DAY_OF_YEAR) < beginDate.get(java.util.Calendar.DAY_OF_YEAR)) years--
            return if (years >= 0) " (" + years + ")" else ""
        }

        private fun openCredit(providerUrl: String?, credit: Credit, view: View) {
            if (detailHandoff?.isActive == true) return 

            val source = providerUrl ?: (model.currentRepo?.api as? StremioC)
                ?.mainUrl?.let(::stremioManifestUrl)
            val provider = getSafeAllProviders().filterIsInstance<StremioC>()
                .firstOrNull { it.hasMainPage && stremioManifestUrl(it.mainUrl) == source }
                ?: run {
                    android.widget.Toast.makeText(fragment.context,
                        "Original StremioC addon is unavailable.", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
            val movie = credit.media == "movie"
            val payload = StremioC.LoadData(
                id = "tmdb:" + credit.id, type = if (movie) "movie" else "series",
                addonUrl = provider.mainUrl
            )
            val result = provider.newMovieSearchResponse(
                credit.title, payload.toJson(), if (movie) TvType.Movie else TvType.TvSeries
            ) {
                this.posterUrl = currentOrigin?.let { posterFor(providerUrl, it, credit) }
                    ?: credit.poster?.let { "https://image.tmdb.org/t/p/w500" + it }
            }
            scroll?.let { save() }
            val covered = detailHandoff?.begin() == true
            SearchHelper.handleSearchClickCallback(
                SearchClickCallback(SEARCH_ACTION_LOAD, view, -1, result))
            if (!covered) {
                preserve = true
                dialog?.dismiss()
            }
        }

        fun stop() {
            if (closed) return
            closed = true
            preserve = true
            requestJob?.cancel()

            if (detailHandoff?.isActive != true) {
                detailHandoff?.close()
                dialog?.dismiss()
            }
            scope.cancel()
            runCatching { fragment.viewLifecycleOwner.lifecycle.removeObserver(resumeObserver) }
            model.page.removeObserver(pageObserver)
        }
    }
}
