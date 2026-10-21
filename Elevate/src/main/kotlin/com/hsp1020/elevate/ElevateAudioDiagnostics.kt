package com.hsp1020.elevate

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.lagradost.cloudstream3.ui.player.CS3IPlayer.Companion.preferredAudioTrackLanguage
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer

/** Temporary, session-scoped audio diagnostics. Remove this file after validating language selection. */
internal object ElevateAudioTrace {
    private const val TAG = "ELEVATE_AUDIO_TRACE"
    fun log(stage: String, detail: String) {
        Log.i(TAG, "lifecycle=$stage $detail")
    }
}

@OptIn(UnstableApi::class)
internal class ElevateAudioDiagnostics {
    private val handler = Handler(Looper.getMainLooper())
    private var attached: Player? = null
    private var fragment: GeneratorPlayer? = null
    private var episodeInstance: Int? = null
    private var session = 0
    private var lastAutoWrite = 0L
    private var lastSelected = ""
    private var lastTrackCount = 0
    private var lastPreferred = ""
    private var lastDialogOpen = false

    // The plugin is compiled independently of the host app. Implementing Player.Listener
    // as a Kotlin object can crash with AbstractMethodError when the host Media3 interface
    // differs. A dynamic proxy safely receives current and future callback methods.
    private val listener = java.lang.reflect.Proxy.newProxyInstance(
        Player.Listener::class.java.classLoader,
        arrayOf(Player.Listener::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "onTracksChanged" -> {
                (args?.getOrNull(0) as? Tracks)?.let { tracks ->
                    log("TRACKS_CHANGED", "groups=${tracks.groups.size} audioGroups=${tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO }}")
                    snapshot("TRACKS_CHANGED")
                }
                null
            }
            "onTrackSelectionParametersChanged" -> {
                (args?.getOrNull(0) as? TrackSelectionParameters)?.let { parameters ->
                    val elapsed = SystemClock.elapsedRealtime() - lastAutoWrite
                    val origin = when {
                        dialogVisible() -> "manual_dialog_visible"
                        elapsed in 0..300 -> "near_auto_write"
                        else -> "native_or_external"
                    }
                    log("PARAMETERS_CHANGED", "originHint=$origin msAfterAuto=$elapsed " +
                        "preferred=${parameters.preferredAudioLanguages} overrides=${parameters.overrides.size}")
                    snapshot("PARAMETERS_CHANGED")
                }
                null
            }
            "onPlaybackStateChanged" -> {
                val state = args?.getOrNull(0) as? Int
                if (state != null) {
                    log("PLAYBACK_STATE", "state=$state")
                    if (state == Player.STATE_READY) schedule("READY")
                }
                null
            }
            "onMediaItemTransition" -> {
                log("MEDIA_TRANSITION", "reason=${args?.getOrNull(1)}")
                schedule("MEDIA_TRANSITION")
                null
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ElevateAudioDiagnosticsListener"
            else -> null
        }
    } as Player.Listener

    private fun log(event: String, data: String) {
        Log.i("ELEVATE_AUDIO_TRACE", "session=$session episode=$episodeInstance event=$event $data")
    }

    private fun dialogVisible(): Boolean {
        val frag = fragment ?: return false
        var current: Class<*>? = frag.javaClass
        while (current != null) {
            val klass = current
            val field = runCatching {
                klass.getDeclaredField("selectTrackDialog").apply { isAccessible = true }
            }.getOrNull()
            if (field != null) {
                return runCatching { (field.get(frag) as? Dialog)?.isShowing == true }.getOrDefault(false)
            }
            current = current.superclass
        }
        return false
    }

    fun episode(instance: Int, provider: String, tvType: String, episodeType: String?,
                dataAnime: Boolean?, finalAnime: Boolean, links: Int, subtitles: Int) {
        if (episodeInstance != instance) {
            detach("new_episode")
            session++
            episodeInstance = instance
        }
        log("EPISODE", "provider=$provider type=$tvType episodeType=$episodeType " +
            "dataIsAnime=$dataAnime finalAnime=$finalAnime links=$links subtitles=$subtitles " +
            "nativePreferred=$preferredAudioTrackLanguage")
    }

    fun bind(player: Player, owner: GeneratorPlayer) {
        if (attached === player) return
        detach("player_replaced")
        attached = player
        fragment = owner
        player.addListener(listener)
        log("PLAYER_ATTACHED", "playerId=${System.identityHashCode(player)} " +
            "nativePreferred=$preferredAudioTrackLanguage state=${player.playbackState}")
        snapshot("ATTACH")
        schedule("ATTACH")
    }

    fun attempt(priority: List<String>, available: Boolean, match: String?, index: Int?,
                selectedBefore: Boolean, reason: String) {
        log("AUTO_DECISION", "priority=$priority available=$available match=$match " +
            "index=$index selectedBefore=$selectedBefore reason=$reason nativePreferred=$preferredAudioTrackLanguage")
    }

    fun beforeAuto() {
        lastAutoWrite = SystemClock.elapsedRealtime()
        snapshot("BEFORE_AUTO")
    }

    fun afterAuto() {
        snapshot("AFTER_AUTO")
        schedule("AUTO_WRITE")
    }

    fun snapshot(reason: String) {
        val player = attached ?: return
        val tracks = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val listed = tracks.flatMapIndexed { groupIndex, group ->
            (0 until group.length).map { index ->
                val format = group.getTrackFormat(index)
                "g$groupIndex/$index(lang=${format.language},id=${format.id?.take(55)}," +
                    "label=${format.label?.take(55)},supported=${group.isTrackSupported(index)}," +
                    "selected=${group.isTrackSelected(index)})"
            }
        }
        val selected = listed.filter { it.contains("selected=true") }.joinToString(";")
        val preferred = preferredAudioTrackLanguage?.toString() ?: "null"
        val dialog = dialogVisible()
        val detail = listed.take(35).joinToString(";")
        val param = player.trackSelectionParameters
        log("SNAPSHOT", "reason=$reason nativePreferred=$preferred " +
            "paramPreferred=${param.preferredAudioLanguages} overrides=${param.overrides.size} " +
            "audioCount=${listed.size} manualDialogVisible=$dialog selected=[$selected] tracks=[$detail]")
        if (selected != lastSelected || listed.size != lastTrackCount ||
            preferred != lastPreferred || dialog != lastDialogOpen) {
            log("STATE_DIFFERENCE", "reason=$reason selectedBefore=[$lastSelected] " +
                "selectedNow=[$selected] countBefore=$lastTrackCount countNow=${listed.size} " +
                "nativePreferredBefore=$lastPreferred nativePreferredNow=$preferred " +
                "dialogBefore=$lastDialogOpen dialogNow=$dialog")
            lastSelected = selected
            lastTrackCount = listed.size
            lastPreferred = preferred
            lastDialogOpen = dialog
        }
    }

    private fun schedule(reason: String) {
        val player = attached ?: return
        val currentSession = session
        for (delay in listOf(300L, 1500L, 5000L)) {
            handler.postDelayed({
                if (attached === player && session == currentSession) {
                    snapshot("$reason+${delay}ms")
                }
            }, delay)
        }
    }

    fun detach(reason: String) {
        handler.removeCallbacksAndMessages(null)
        attached?.let { player ->
            snapshot("DETACH")
            log("PLAYER_DETACHED", "reason=$reason playerId=${System.identityHashCode(player)}")
            player.removeListener(listener)
        }
        attached = null
        fragment = null
        lastAutoWrite = 0L
        lastSelected = ""
        lastTrackCount = 0
        lastPreferred = ""
        lastDialogOpen = false
    }

    fun close() {
        detach("selector_stopped")
        episodeInstance = null
    }
}
