package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** User-initiated MKV embedded text translation: no polling, no player reload. */
internal class MkvEmbeddedTranslateHook(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private data class SubMaker(val base: String, val config: String)
    private data class TimedLine(val start: Long, val end: Long, val text: String)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: FragmentActivity? = null
    private var fragment: GeneratorPlayer? = null
    private var vm: PlayerGeneratorViewModel? = null
    private var focusTree: ViewTreeObserver? = null
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
        fragment?.view?.post { inspect() }
    }
    private var decorated: Dialog? = null
    private var action: TextView? = null
    private var task: Job? = null
    private var nextAttempt = 0
    private var taskAttempt = 0
    private var overlay: TranslatedOverlay? = null
    private val sourceDialogField = runCatching {
        FullScreenPlayer::class.java.getDeclaredField("selectSourceDialog")
            .apply { isAccessible = true }
    }.getOrNull()
    private val linkField = runCatching {
        CS3IPlayer::class.java.getDeclaredField("currentLink")
            .apply { isAccessible = true }
    }.getOrNull()
    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, saved: Bundle?) = watch(f)
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) = watch(f)
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) = detach(f)
    }

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }
    fun stop() {
        app.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        detach()
        host = null
        scope.cancel()
    }
    private fun use(activity: FragmentActivity) {
        if(host===activity) return
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        detach()
        host=activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks,true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach {
                if(it.view!=null) watch(it)
                if(it.isAdded) scan(it.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }
    private fun watch(f:Fragment) {
        if(f !is GeneratorPlayer || !f.isAdded || f.view==null || f===fragment) return
        detach()
        fragment=f
        vm=ViewModelProvider(f)[PlayerGeneratorViewModel::class.java]
        f.activity?.window?.decorView?.viewTreeObserver?.takeIf { it.isAlive }?.let {
            focusTree=it
            it.addOnWindowFocusChangeListener(focusListener)
        }
        f.view?.post { inspect() }
    }
    private fun inspect() {
        val f=fragment ?: return
        overlay?.syncOrDetach(f)
        val dialog=runCatching { sourceDialogField?.get(f) as? Dialog }.getOrNull()
        if(dialog?.isShowing!=true || decorated===dialog) return
        val list=dialog.findViewById<ListView>(R.id.sort_subtitles) ?: return
        if(vm?.state?.subtitles?.none { it.origin==SubtitleOrigin.EMBEDDED_IN_VIDEO }!=false) return
        val footer=LayoutInflater.from(f.requireContext())
            .inflate(R.layout.sort_bottom_footer_add_choice,list,false) as? TextView ?: return
        footer.text=if(f.resources.configuration.locale.language=="ko")
            "MKV 내장자막 번역" else "Translate MKV embedded subtitles"
        footer.setOnClickListener { chooseTrack(f,dialog) }
        list.addFooterView(footer,null,false)
        decorated=dialog
        action=footer
        dialog.window?.decorView?.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View)=Unit
            override fun onViewDetachedFromWindow(v:View) {
                v.removeOnAttachStateChangeListener(this)
                if(decorated===dialog) {
                    decorated=null
                    action=null
                }
            }
        })
    }
    private fun currentLink(f:GeneratorPlayer):ExtractorLink? =
        runCatching { linkField?.get(f.player) as? ExtractorLink }.getOrNull()
    private fun chooseTrack(f:GeneratorPlayer,dialog:Dialog) {
        val link=currentLink(f) ?: run {
            Log.w("Elevate-MkvTranslate","selection-skipped reason=no-active-stream")
            return toast(f,"No active stream")
        }
        if(!link.url.startsWith("http://",true) && !link.url.startsWith("https://",true)) {
            Log.w("Elevate-MkvTranslate","selection-skipped reason=non-http-stream")
            return toast(f,"Only HTTP MKV streams are supported")
        }
        val candidates=vm?.state?.subtitles.orEmpty()
            .filter { it.origin==SubtitleOrigin.EMBEDDED_IN_VIDEO }
            .distinctBy { it.url }
            .sortedWith(compareBy({it.url.toIntOrNull() ?: Int.MAX_VALUE},{it.url}))
        if(candidates.isEmpty()) {
            Log.w("Elevate-MkvTranslate","selection-skipped reason=no-embedded-tracks")
            return
        }
        val provider=subMaker() ?: run {
            Log.w("Elevate-MkvTranslate","selection-skipped reason=no-submaker-config")
            return toast(f,"Register SubMaker in StremioC before translating")
        }
        AlertDialog.Builder(f.requireContext())
            .setTitle(if(f.resources.configuration.locale.language=="ko") "번역할 내장자막 선택" else "Select embedded subtitles")
            .setItems(candidates.map { it.originalName+" · "+it.url }.toTypedArray()) { _,pos ->
                val selected=candidates.getOrNull(pos) ?: return@setItems
                dialog.dismiss()
                translate(f,vm ?: return@setItems,link,selected,provider)
            }
            .setNegativeButton(android.R.string.cancel,null)
            .show()
    }
    private fun subMaker():SubMaker? {
        val prefs=app.getSharedPreferences("StremioC",Context.MODE_PRIVATE)
        val registered=ArrayList<Pair<String,String>>()
        val configured=runCatching { JSONArray(prefs.getString("stremio_saved_links","[]")) }.getOrNull()
        if(configured!=null) for(i in 0 until configured.length()) {
            val item=configured.optJSONObject(i) ?: continue
            if(item.optBoolean("enabled",true))
                registered.add(item.optString("name") to item.optString("link"))
        }
        val streaming=runCatching { JSONArray(prefs.getString("stream_subtitle_addon_urls","[]")) }.getOrNull()
        if(streaming!=null) for(i in 0 until streaming.length()) {
            val item=streaming.optJSONObject(i)
            if(item==null) registered.add("" to streaming.optString(i))
            else if(item.optBoolean("enabled",true))
                registered.add(item.optString("name") to item.optString("url"))
        }
        for((name,url) in registered) {
            val uri=runCatching { URI(url.replace("stremio://","https://")) }.getOrNull() ?: continue
            if(!name.contains("submaker",true) && !uri.host.orEmpty().contains("submaker",true)) continue
            val path=uri.path.orEmpty()
            val match=Regex("""^(.*)/addon/([^/]+)/manifest\.json$""").matchEntire(path)
                ?: continue
            val origin=uri.scheme+"://"+uri.authority+match.groupValues[1]
            val config=match.groupValues[2]
            if(uri.scheme !in listOf("https","http") || config.isBlank()) continue
            return SubMaker(origin.trimEnd('/'),config)
        }
        return null
    }
    private fun hash(value:String):String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(20).joinToString("") { "%02x".format(it) }
    private fun translate(f:GeneratorPlayer,viewModel:PlayerGeneratorViewModel,
                          link:ExtractorLink,sub:SubtitleData,maker:SubMaker) {
        if(task?.isActive==true) Log.w("Elevate-MkvTranslate","job=$taskAttempt cancel-request reason=superseded")
        task?.cancel(CancellationException("superseded"))
        overlay?.detach("superseded")
        overlay=null
        val attempt=++nextAttempt
        taskAttempt=attempt
        val started=SystemClock.elapsedRealtime()
        val instance=viewModel.state.instance
        val mediaPlayer=f.playerView?.player
        val originalSelected=f.player.getCurrentPreferredSubtitle()?.url
        val trackId=sub.url.takeIf { it.matches(Regex("[a-zA-Z0-9._-]{1,120}")) }
            ?: run {
                Log.w("Elevate-MkvTranslate","job=$attempt selection-skipped reason=invalid-track-id")
                return toast(f,"Unsupported embedded track ID")
            }
        Log.i("Elevate-MkvTranslate","job=$attempt started track=$trackId language=${sub.languageCode} playerPresent=${mediaPlayer!=null}")
        toast(f,if(f.resources.configuration.locale.language=="ko") "내장자막 번역 시작" else "Translating embedded subtitles")
        task=scope.launch {
            var stage="extract-start"
            try {
                val videoHash=hash(link.url)
                val translated=withContext(Dispatchers.IO) {
                    val srt=MkvSubtitleRangeExtractor(link) { event ->
                        stage=event.substringBefore(' ')
                        Log.i("Elevate-MkvTranslate","job=$attempt $event")
                    }.extract(trackId,sub.languageCode)
                    Log.i("Elevate-MkvTranslate","job=$attempt extract-complete track=$trackId chars=${srt.length}")
                    stage="api-request"
                    requestTranslation(maker,videoHash,trackId,sub.languageCode,srt,attempt) {
                        stage="api-response"
                    }
                }
                stage="register"
                val sameFragment=fragment===f
                val sameModel=vm===viewModel
                val sameInstance=viewModel.state.instance==instance
                val samePlayer=f.playerView?.player===mediaPlayer
                val sameLink=currentLink(f)?.url==link.url
                if(!sameFragment || !sameModel || !sameInstance || !samePlayer || !sameLink) {
                    Log.w("Elevate-MkvTranslate","job=$attempt result-discarded fragment=$sameFragment model=$sameModel instance=$sameInstance player=$samePlayer link=$sameLink elapsedMs=${SystemClock.elapsedRealtime()-started}")
                    return@launch
                }
                val url=maker.base+"/addon/"+maker.config+
                    "/xembedded/"+videoHash+"/kor/"+trackId
                val korean=f.resources.configuration.locale.language=="ko"
                val entry=SubtitleData(
                    if(korean) "MKV 번역" else "MKV Translation",
                    if(korean) "한국어 · SubMaker" else "Korean · SubMaker",
                    url,
                    SubtitleOrigin.URL,
                    "application/x-subrip",
                    emptyMap(),
                    null
                )
                viewModel.addSubtitles(setOf(entry))
                Log.i("Elevate-MkvTranslate","job=$attempt subtitle-registered track=$trackId")
                overlay=TranslatedOverlay(f,mediaPlayer,originalSelected,translated,attempt).also { it.attach() }
                Log.i("Elevate-MkvTranslate","job=$attempt completed overlayAttached=${overlay?.isAttached==true} elapsedMs=${SystemClock.elapsedRealtime()-started}")
                toast(f,if(f.resources.configuration.locale.language=="ko")
                    "한국어 번역 완료" else "Korean subtitles translated")
            } catch(e:CancellationException) {
                Log.w("Elevate-MkvTranslate","job=$attempt cancelled stage=$stage reason=${e.message.orEmpty().take(80)} elapsedMs=${SystemClock.elapsedRealtime()-started}")
                throw e
            } catch(e:Exception) {
                val fromPlugin = e.stackTrace.firstOrNull {
                    it.className.startsWith("com.hsp1020.elevate.")
                }
                val location = fromPlugin?.let {
                    "${it.className.substringAfterLast('.') }.${it.methodName}:${it.lineNumber}"
                } ?: "unknown"
                val detail = e.message.orEmpty()
                    .replace(Regex("""https?://\S+"""), "[redacted-url]")
                    .take(200)
                Log.e("Elevate-MkvTranslate",
                    "job=$attempt failed stage=$stage elapsedMs=${SystemClock.elapsedRealtime()-started} type=${e.javaClass.simpleName} at=$location reason=$detail")
                if(fragment===f && vm===viewModel) toast(f,
                    if(f.resources.configuration.locale.language=="ko")
                        "자막 추출·번역 실패: "+(e.message ?: "오류")
                    else "Subtitle translation failed: "+(e.message ?: "Error"))
            }
        }
    }
    private fun requestTranslation(maker:SubMaker,hash:String,track:String,
                                   language:String?,srt:String,attempt:Int,onResponse:()->Unit):String {
        val source=when(language?.lowercase()) {
            "ja","jp","jpn" -> "jpn"
            "en","eng" -> "eng"
            "ko","kor" -> "kor"
            else -> language?.lowercase() ?: "und"
        }
        val payload=JSONObject().apply {
            put("configStr",maker.config)
            put("videoHash",hash)
            put("trackId",track)
            put("sourceLanguageCode",source)
            put("targetLanguage","kor")
            put("content",srt)
            put("metadata",JSONObject().put("codec","srt").put("label","MKV embedded text"))
        }
        val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
            .readTimeout(300,TimeUnit.SECONDS).build()
        val request=Request.Builder().url(maker.base+"/api/translate-embedded")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        Log.i("Elevate-MkvTranslate","job=$attempt api-request track=$track chars=${srt.length}")
        client.newCall(request).execute().use { res ->
            onResponse()
            Log.i("Elevate-MkvTranslate","job=$attempt api-http-response status=${res.code}")
            if(!res.isSuccessful) throw IOException("SubMaker HTTP "+res.code)
            val response=res.body?.string() ?: throw IOException("Empty SubMaker response")
            val json=JSONObject(response)
            val translated=json.optString("translatedContent")
            if(translated.isBlank() || !translated.contains("-->"))
                throw IOException("SubMaker returned no SRT content")
            Log.i("Elevate-MkvTranslate","job=$attempt api-response track=$track status=${res.code} chars=${translated.length}")
            return translated
        }
    }
    private fun toast(f:GeneratorPlayer,message:String) =
        Toast.makeText(f.requireContext(),message,Toast.LENGTH_LONG).show()

    private class TranslatedOverlay(
        private val fragment:GeneratorPlayer,
        private val player:Player?,
        private val sourceUrl:String?,
        content:String,
        private val attempt:Int
    ) : Player.Listener {
        private val main=Handler(Looper.getMainLooper())
        private val lines=content.trim().split(Regex("""\r?\n\s*\r?\n""")).mapNotNull { piece ->
            val split=piece.lines()
            val index=split.indexOfFirst { it.contains("-->") }
            if(index<0) return@mapNotNull null
            val pair=split[index].split("-->")
            val start=time(pair.getOrNull(0) ?: return@mapNotNull null) ?: return@mapNotNull null
            val end=time(pair.getOrNull(1) ?: return@mapNotNull null) ?: return@mapNotNull null
            if(end<=start) return@mapNotNull null
            TimedLine(start,end,split.drop(index+1).joinToString("\n"))
        }.sortedBy { it.start }
        private var native:SubtitleView?=null
        private var output:View?=null
        private var priorAlpha=1f
        private var view:SubtitleView?=null
        private var lastText:String?=null
        private var firstCueLogged=false
        val isAttached:Boolean get()=view!=null
        private val advance=Runnable { update() }
        fun attach() {
            val parent=fragment.subView ?: return skipped("missing-subtitle-view")
            val currentPlayer=player ?: return skipped("missing-player")
            val nativeOutput=parent.getChildAt(0) ?: return skipped("missing-native-output")
            if(lines.isEmpty()) return skipped("empty-srt-cues")
            native=parent
            output=nativeOutput
            priorAlpha=nativeOutput.alpha
            val subtitleView=SubtitleView(parent.context)
            SubtitlesFragment.setSubtitleViewStyle(
                subtitleView,SubtitlesFragment.getCurrentSavedStyle(),true)
            parent.addView(subtitleView,FrameLayout.LayoutParams(-1,-1))
            view=subtitleView
            nativeOutput.alpha=0f
            currentPlayer.addListener(this)
            update()
            if(view===subtitleView)
                Log.i("Elevate-MkvTranslate","job=$attempt overlay-attached cues=${lines.size}")
            else skipped("detached-during-attach")
        }
        private fun skipped(reason:String) {
            Log.w("Elevate-MkvTranslate","job=$attempt overlay-skipped reason=$reason cues=${lines.size}")
        }
        override fun onEvents(player:Player,events:Player.Events) { update() }
        fun syncOrDetach(current:GeneratorPlayer) {
            if(current!==fragment || current.playerView?.player!==player ||
                current.player.getCurrentPreferredSubtitle()?.url!=sourceUrl) {
                detach("player-or-selection-changed")
            } else update()
        }
        private fun update() {
            main.removeCallbacks(advance)
            val media=player ?: return
            val v=view ?: return
            if(fragment.playerView?.player!==media ||
                fragment.player.getCurrentPreferredSubtitle()?.url!=sourceUrl) {
                detach("player-or-selection-changed")
                return
            }
            val pos=media.currentPosition+fragment.player.getSubtitleOffset()
            val active=lines.filter { pos>=it.start && pos<it.end }
            val text=active.joinToString("\n") { it.text }
            if(text!=lastText) {
                v.setCues(if(text.isEmpty()) emptyList()
                    else listOf(Cue.Builder().setText(text).build()))
                lastText=text
                if(text.isNotEmpty() && !firstCueLogged) {
                    firstCueLogged=true
                    Log.i("Elevate-MkvTranslate","job=$attempt first-cue-submitted positionMs=$pos")
                }
            }
            if(!media.isPlaying) return
            val next=lines.asSequence().flatMap { sequenceOf(it.start,it.end) }
                .filter { it>pos }.minOrNull() ?: return
            val delay=((next-pos)/media.playbackParameters.speed.coerceAtLeast(0.1f))
                .toLong().coerceAtLeast(1)
            main.postDelayed(advance,delay)
        }
        fun detach(reason:String="reset") {
            if(view!=null) Log.i("Elevate-MkvTranslate","job=$attempt overlay-detached reason=$reason")
            main.removeCallbacks(advance)
            player?.removeListener(this)
            view?.let { (it.parent as? ViewGroup)?.removeView(it) }
            output?.alpha=priorAlpha
            view=null; native=null; output=null
        }
        companion object {
            private fun time(value:String):Long? {
                val m=Regex("""(\d+):(\d{2}):(\d{2})[,\.](\d{3})""")
                    .find(value.trim()) ?: return null
                return m.groupValues[1].toLong()*3600000+
                    m.groupValues[2].toLong()*60000+
                    m.groupValues[3].toLong()*1000+
                    m.groupValues[4].toLong()
            }
        }
    }

    private fun detach(f:Fragment?=null) {
        if(f!=null && f!==fragment) return
        focusTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusTree=null
        if(task?.isActive==true) Log.w("Elevate-MkvTranslate","job=$taskAttempt cancel-request reason=fragment-detached")
        task?.cancel(CancellationException("fragment-detached"));task=null
        overlay?.detach("fragment-detached");overlay=null
        decorated=null;action=null
        fragment=null;vm=null
    }
    override fun onActivityResumed(activity:Activity) {
        if(activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity:Activity) {
        if(host===activity) {
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
            detach();host=null
        }
    }
    override fun onActivityCreated(activity:Activity,savedInstanceState:Bundle?)=Unit
    override fun onActivityStarted(activity:Activity)=Unit
    override fun onActivityPaused(activity:Activity)=Unit
    override fun onActivityStopped(activity:Activity)=Unit
    override fun onActivitySaveInstanceState(activity:Activity,outState:Bundle)=Unit
}
