package com.hsp1020.elevate

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
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
import androidx.navigation.NavOptions
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.result.ResultData
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.settings.getCurrentLocale
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withContext
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

internal data class ElevateActorPersonRef(
    val id: Int?,
    val name: String,
    val photo: String?,
    val preferCrew: Boolean
)

internal object ElevateActorInformation {
    private data class CacheEntry(val bytes: ByteArray, val time: Long)
    private val cache = LinkedHashMap<String, CacheEntry>(50, 0.75f, true)
    private var cacheBytes = 0L
    private const val MAX_CACHE_ENTRIES = 50
    private const val MAX_CACHE_BYTES = 8L * 1024 * 1024
    private val flights = mutableMapOf<String, Deferred<JSONObject>>()
    private val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val TTL = 12L * 60 * 60 * 1000

    private fun configuration(context: Context): Pair<String, String> =
        ElevateIdResolver.tmdbApiKey().orEmpty() to getCurrentLocale(context)

    private suspend fun request(
        personId: Int, endpoint: String, language: String, apiKey: String
    ): JSONObject {
        if (apiKey.isBlank()) throw IllegalStateException("TMDB API key is not configured")
        val url = android.net.Uri.parse("https://api.themoviedb.org/3/person/" + personId + endpoint)
            .buildUpon().appendQueryParameter("language", language)
            .appendQueryParameter("api_key", apiKey).build().toString()
        val response = app.get(url, timeout = 15L)
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
            flights[cacheKey] ?: sharedScope.async(start = CoroutineStart.LAZY) {
                val result = request(personId, endpoint, lang, apiKey)
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
                }
                result
            }.also { flight ->
                flights[cacheKey] = flight
                flight.invokeOnCompletion {
                    synchronized(flights) {
                        if (flights[cacheKey] === flight) flights.remove(cacheKey)
                    }
                }
            }
        }
        task.start()
        return task.await()
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


    internal class Controller(private val fragment: Fragment) {
        private val model by lazy { ViewModelProvider(fragment)[ResultViewModel2::class.java] }
        private val stateKey = "elevate_actor_info_return"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var dialog: AppCompatDialog? = null
        private var originActorFocus: View? = null
        private var focusedCreditKey: String? = null
        private var detailHandoff: ElevateActorHandoff? = null
        private var originState: androidx.lifecycle.SavedStateHandle? = null
        private var requestJob: kotlinx.coroutines.Job? = null
        private var preserve = false
        private var currentPerson: ElevateActorPersonRef? = null
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

        fun click(person: ElevateActorPersonRef, originCard: View? = null) {
            val page = (model.page.value as? Resource.Success<ResultData>)?.value ?: return
            originActorFocus = if (isLayout(TV)) originCard else null
            focusedCreditKey = null
            bioExpanded = false
            creditScroll.clear()
            show(null, person, page.url)
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
                put("creditFocus", focusedCreditKey)
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
            val ref = ElevateActorPersonRef(
                obj.optInt("id").takeIf { it > 0 },
                obj.optString("name"),
                obj.optString("photo").takeIf { it.isNotBlank() && it != "null" },
                obj.optBoolean("crew")
            )
            bioExpanded = obj.optBoolean("expanded")
            focusedCreditKey = obj.optString("creditFocus").takeIf {
                it.isNotBlank() && it != "null"
            }
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
                if (strong) applyElevateNativeFont(bold = true)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }

        private fun column() = LinearLayout(fragment.requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        private fun show(originProvider: String?, actor: ElevateActorPersonRef, origin: String, resumeY: Int = 0) {
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
            // Decode a TMDB credit poster once for every Popular/Latest occurrence.
            // Each card retains its own ImageView, focus and custom-poster overlay.
            val nativePosterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val nativePosterFlights = mutableMapOf<String, Deferred<coil3.Image?>>()
            val nativePrefetched = HashSet<String>()
            val customPrefetched = HashSet<String>()
            fun nativePosterUrl(credit: Credit): String? =
                credit.poster?.takeIf { it.startsWith("/") }
                    ?.let { "https://image.tmdb.org/t/p/w600_and_h900_bestv2$it" }
            fun requestNativePoster(url: String): Deferred<coil3.Image?> =
                nativePosterFlights[url] ?: nativePosterScope.async(Dispatchers.IO) {
                    val request = ImageRequest.Builder(activity.applicationContext)
                        .data(url).size(dp(114), dp(180))
                        .httpHeaders(NetworkHeaders.Builder().apply {
                            this["User-Agent"] = USER_AGENT
                        }.build()).build()
                    val result = SingletonImageLoader.get(activity.applicationContext)
                        .execute(request) as? SuccessResult
                    result?.image
                }.also { flight ->
                    nativePosterFlights[url] = flight
                    flight.invokeOnCompletion {
                        nativePosterScope.launch {
                            if (nativePosterFlights[url] === flight) nativePosterFlights.remove(url)
                        }
                    }
                }
            fun prefetchNative(entries: List<Credit>, end: Int) {
                entries.take(end).forEach { credit ->
                    nativePosterUrl(credit)?.let { url ->
                        if (nativePrefetched.add(url)) nativePosterScope.launch {
                            try { requestNativePoster(url).await() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* An on-screen card can retry. */ }
                        }
                    }
                }
            }
            fun ImageView.loadSharedNativePoster(url: String?, credit: Credit) {
                setImageResource(R.drawable.default_cover)
                val image = this
                val initial = drawable
                if (!url.isNullOrBlank()) nativePrefetched.add(url)
                val flight = url?.let(::requestNativePoster)
                nativePosterScope.launch {
                    try {
                        val decoded = flight?.await()
                        if (decoded != null && dialog === windowDialog &&
                            image.drawable === initial && image.isAttachedToWindow)
                            image.setImageDrawable(decoded.asDrawable(resources))
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { /* Keep native placeholder on failure. */ }

                    // Custom Posters is independent of the Actor Information toggle.
                    if (dialog !== windowDialog || !image.isAttachedToWindow ||
                        !ElevateFeaturePrefs.enabled(activity, ElevateFeature.LIBRARY_POSTERS))
                        return@launch
                    val config = LibraryPosterPrefs.get(activity)
                    if (!config.ready) return@launch
                    val movie = credit.media == "movie"
                    val ids = withContext(Dispatchers.IO) {
                        PosterIds(tmdb = credit.id.toString()).preferImdb(movie)
                    }
                    val version = if (config.service == PosterService.XRDB)
                        withContext(Dispatchers.IO) { ElevateXrdbVersion.resolve(config) }
                    else null
                    val primary = config.image(ids, movie, version)
                    val fallback = if (ids.imdb != null)
                        config.image(ids.copy(imdb = null), movie, version)
                    else null
                    for (poster in listOfNotNull(primary, fallback).distinct()) {
                        val result = PosterWipe.load(image, poster) {
                            dialog === windowDialog && image.isAttachedToWindow &&
                                ElevateFeaturePrefs.enabled(activity, ElevateFeature.LIBRARY_POSTERS) &&
                                LibraryPosterPrefs.get(activity) == config
                        }
                        if (result.success || result.error == "stale") break
                    }
                }
            }
            val scrollListeners = mutableListOf<Pair<View, ViewTreeObserver.OnScrollChangedListener>>()
            var focusedCreditView: View? = null
            val navigation = (activity.supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)?.navController
            val handoff = ElevateActorHandoff(
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
                    } else {
                        windowDialog.show()
                        focusedCreditView?.takeIf { it.isAttachedToWindow }?.let { focus ->
                            focus.post {
                                if (dialog === windowDialog && windowDialog.isShowing &&
                                    focus.isAttachedToWindow && focus.isShown)
                                    focus.requestFocus()
                            }
                        }
                    }
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
                if (isLayout(TV)) foreground = elevateActorOutlinedForeground(this, null)
                setOnClickListener { windowDialog.dismiss() }
            }
            heading.addView(back, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.START))
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
            val skeleton = ElevateActorLoading(activity, 3)
            bodyFrame.addView(skeleton, FrameLayout.LayoutParams(-1, -1))
            body.visibility = View.INVISIBLE
            skeleton.loading(true)
            scroller.addView(bodyFrame)
            root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
            fun revealBody() {
                body.doOnPreDraw {
                    if (dialog === windowDialog) {
                        body.visibility = View.VISIBLE
                        skeleton.loading(false)
                        if (isLayout(TV)) focusedCreditView?.takeIf {
                            it.isAttachedToWindow && it.isShown
                        }?.requestFocus()
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
                foreground = elevateActorOutlinedForeground(this)
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
                nativePosterScope.cancel()
                handoff.close()
                if (detailHandoff === handoff) detailHandoff = null
                requestJob?.cancel()
                requestJob = null
                skeleton.loading(false)
                dialog = null
                scroll = null
                if (!preserve && !closed) {
                    clearState()
                    originActorFocus?.takeIf { it.isAttachedToWindow }?.let { focus ->
                        focus.post {
                            if (fragment.isResumed && dialog == null &&
                                navigation?.currentDestination?.id == R.id.navigation_results_tv &&
                                focus.isAttachedToWindow && focus.isShown && focus.isFocusable)
                                focus.requestFocus()
                        }
                    }
                    originActorFocus = null
                }
            }
            dialog = windowDialog
            windowDialog.show()
            windowDialog.window?.setLayout(-1, -1)
            if (isLayout(TV)) back.requestFocus()
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
            val (key, language) = configuration(fragment.requireContext())
            if (key.isBlank()) {
                biography.text = "CloudStream TMDB API key is unavailable."
                revealBody()
                return
            }
            fun showError(target: LinearLayout?, message: String, retry: () -> Unit) {
                val container = target ?: body
                container.addView(label(message))
                container.addView(label("Retry", 15f, true).apply {
                    isClickable = true
                    isFocusable = true
                    foreground = elevateActorOutlinedForeground(this)
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
                details.removeViews(1, details.childCount - 1)
                biographyReady = false
                biography.text = "Loading biography…"
                toggle.visibility = View.GONE
                filmArea.removeAllViews()
                filmArea.tag = null
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
                            foreground = elevateActorOutlinedForeground(this)
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
                        val preloadSections = mutableListOf<() -> Unit>()
                        fun preparePosters(entries: List<Credit>, shown: Int) {
                            val end = (shown + 32).coerceAtMost(entries.size)
                            prefetchNative(entries, end)
                            if (!ElevateFeaturePrefs.enabled(activity, ElevateFeature.LIBRARY_POSTERS))
                                return
                            val config = LibraryPosterPrefs.get(activity)
                            if (!config.ready) return
                            entries.take(end).forEach { credit ->
                                val identity = "${credit.media}:${credit.id}"
                                if (!customPrefetched.add(identity)) return@forEach
                                nativePosterScope.launch {
                                    val movie = credit.media == "movie"
                                    val ids = withContext(Dispatchers.IO) {
                                        PosterIds(tmdb = credit.id.toString()).preferImdb(movie)
                                    }
                                    val version = if (config.service == PosterService.XRDB)
                                        withContext(Dispatchers.IO) { ElevateXrdbVersion.resolve(config) }
                                    else null
                                    val poster = config.image(ids, movie, version) ?: return@launch
                                    if (dialog === windowDialog &&
                                        ElevateFeaturePrefs.enabled(activity, ElevateFeature.LIBRARY_POSTERS) &&
                                        LibraryPosterPrefs.get(activity) == config)
                                        PosterWipe.prefetch(activity, poster, dp(114), dp(180))
                                }
                            }
                        }
                        listOf("Popular" to popular, "Latest" to latest,
                            "Upcoming" to upcoming).forEach { (title, entries) ->
                            if (entries.isNotEmpty()) {
                                filmArea.addView(label(title, 19f, true))
                                val strip = HorizontalScrollView(activity)
                                val row = LinearLayout(activity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    // HorizontalScrollView measures its child with UNSPECIFIED width:
                                    // LayoutParams.width is ignored, but minimumWidth is honored.
                                    minimumWidth = entries.size * dp(124)
                                }
                                var shown = 0
                                lateinit var requestMore: () -> Unit
                                fun appendCredits(count: Int) {
                                    val target = (shown + count).coerceAtMost(entries.size)
                                    while (shown < target) {
                                        val credit = entries[shown++]
                                        val position = shown
                                        val card = column().apply {
                                            isClickable = true
                                            isFocusable = true
                                            setBackgroundResource(R.drawable.outline_drawable)
                                            setPadding(dp(5), dp(3), dp(5), dp(8))
                                            val focusKey = "${title}:${credit.media}:${credit.id}"
                                            if (isLayout(TV) && focusedCreditKey == focusKey)
                                                focusedCreditView = this
                                            setOnClickListener {
                                                if (isLayout(TV)) {
                                                    focusedCreditKey = focusKey
                                                    focusedCreditView = it
                                                }
                                                openCredit(originProvider, credit, it)
                                            }
                                            if (isLayout(TV)) setOnFocusChangeListener { focusedView, focused ->
                                                if (focused && position >= shown - 3) focusedView.post {
                                                    if (dialog === windowDialog && focusedView.isAttachedToWindow)
                                                        requestMore()
                                                }
                                            }
                                        }

                                        val posterHolder = CardView(activity).apply {
                                            radius = activity.resources.getDimension(R.dimen.rounded_image_radius)
                                            cardElevation = 0f
                                            setCardBackgroundColor(activity.colorFromAttribute(R.attr.primaryGrayBackground))
                                            isDuplicateParentStateEnabled = true
                                            addView(ImageView(activity).apply {
                                                scaleType = ImageView.ScaleType.CENTER_CROP
                                                isDuplicateParentStateEnabled = true
                                                foreground = elevateActorPressedForeground(this)
                                                val url = nativePosterUrl(credit)
                                                // Standard TMDB artwork identity, independent of any plugin settings.
                                                loadSharedNativePoster(url, credit)
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
                                }
                                val restoreX = creditScroll[title] ?: 0
                                appendCredits((restoreX / dp(124) + 12).coerceAtLeast(12))
                                requestMore = {
                                    if (dialog === windowDialog && shown < entries.size) {
                                        appendCredits(12)
                                        preparePosters(entries, shown)
                                    }
                                }
                                preloadSections.add { preparePosters(entries, shown) }
                                // Fixed row minimum width keeps fling bounds stable as cards append.
                                strip.addView(row)
                                strip.post {
                                    if (dialog === windowDialog) {
                                        val x = creditScroll[title] ?: 0
                                        strip.scrollTo(x, 0)
                                    }
                                }
                                var lastStripX = strip.scrollX
                                val stripListener = ViewTreeObserver.OnScrollChangedListener {
                                    if (dialog === windowDialog && strip.scrollX != lastStripX) {
                                        lastStripX = strip.scrollX
                                        creditScroll[title] = strip.scrollX
                                        save()
                                        // Only extend actual cards near their trailing edge;
                                        // the total scroll range is already fixed.
                                        if (shown < entries.size && strip.width > 0 &&
                                            strip.scrollX + strip.width >=
                                                (shown - 4) * dp(124)) {
                                            requestMore()
                                        }
                                    }
                                }
                                strip.viewTreeObserver.addOnScrollChangedListener(stripListener)
                                scrollListeners.add(strip to stripListener)
                                filmArea.addView(strip)
                            }
                        }
                        // Native poster requests for initial cards precede offscreen preloads.
                        preloadSections.forEach { it() }
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
            if (detailHandoff?.isActive == true || closed) return
            val movie = credit.media == "movie"
            val provider = model.currentRepo?.api
            scope.launch {
                val results = withContext(Dispatchers.IO) {
                    try { provider?.search(credit.title).orEmpty() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                }
                val creditYear = credit.date?.take(4)?.toIntOrNull()
                val matching = results.filter { item ->
                    item.name.equals(credit.title, ignoreCase = true) &&
                        (item.type in setOf(TvType.Movie, TvType.AnimeMovie)) == movie &&
                        (creditYear == null || item.year == creditYear)
                }
                val candidate = matching.singleOrNull()
                if (!fragment.isAdded || closed || dialog?.isShowing != true) return@launch
                scroll?.let { save() }
                if (candidate == null) {
                    val activity = fragment.activity ?: return@launch
                    val searchId = activity.resources.getIdentifier("navigation_search", "id",
                        activity.packageName)
                    if (searchId == 0) return@launch
                    preserve = true
                    dialog?.dismiss()
                    val enter = activity.resources.getIdentifier("enter_anim", "anim",
                        activity.packageName)
                    val exit = activity.resources.getIdentifier("exit_anim", "anim",
                        activity.packageName)
                    activity.navigate(searchId, Bundle().apply {
                        putString("search_query", credit.title)
                        putBoolean("global_detail_search", true)
                    }, NavOptions.Builder().setEnterAnim(enter).setExitAnim(exit)
                        .setPopEnterAnim(enter).setPopExitAnim(exit).build())
                } else {
                    val covered = detailHandoff?.begin() == true
                    SearchHelper.handleSearchClickCallback(
                        SearchClickCallback(SEARCH_ACTION_LOAD, view, -1, candidate))
                    if (!covered) {
                        preserve = true
                        dialog?.dismiss()
                    }
                }
            }
        }

        fun stop() {
            if (closed) return
            closed = true
            preserve = true
            originActorFocus = null
            focusedCreditKey = null
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
