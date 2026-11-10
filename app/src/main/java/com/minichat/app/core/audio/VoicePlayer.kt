package com.minichat.app.core.audio

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One shared player for voice notes.
 *
 * A chat screen only ever plays one clip at a time, so a singleton keeps the
 * behaviour predictable: starting a new note stops the previous one, and the
 * position ticks drive the progress bar without polling MediaPlayer on the main
 * thread.
 */
@Singleton
class VoicePlayer @Inject constructor() {

    data class PlaybackState(
        val messageId: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var tickerJob: Job? = null

    /**
     * Plays [url] for [messageId]. Tapping the same note toggles pause/resume;
     * tapping a different one switches to it.
     */
    fun toggle(messageId: String, url: String, durationHintMs: Int) {
        if (_state.value.messageId == messageId && player != null) {
            val active = player ?: return
            if (active.isPlaying) {
                active.pause()
                stopTicker()
                _state.value = _state.value.copy(isPlaying = false)
            } else {
                active.start()
                startTicker(messageId)
                _state.value = _state.value.copy(isPlaying = true, error = null)
            }
            return
        }
        play(messageId, url, durationHintMs)
    }

    fun play(messageId: String, url: String, durationHintMs: Int) {
        stop()
        _state.value = PlaybackState(
            messageId = messageId,
            isPlaying = true,
            durationMs = durationHintMs,
        )

        val instance = MediaPlayer()
        player = instance

        runCatching {
            instance.setDataSource(url)
            instance.setOnPreparedListener { prepared ->
                _state.value = _state.value.copy(
                    durationMs = prepared.duration.takeIf { it > 0 } ?: durationHintMs,
                )
                prepared.start()
                startTicker(messageId)
            }
            instance.setOnCompletionListener {
                stop()
            }
            instance.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Playback error what=$what extra=$extra")
                stop()
                _state.value = PlaybackState(error = "This voice message couldn't be played.")
                true
            }
            instance.prepareAsync()
        }.onFailure { error ->
            Log.w(TAG, "Could not start playback", error)
            stop()
            _state.value = PlaybackState(error = "This voice message couldn't be played.")
        }
    }

    fun stop() {
        stopTicker()
        val instance = player
        player = null
        runCatching { instance?.stop() }
        runCatching { instance?.release() }
        _state.value = PlaybackState()
    }

    /** Frees resources when the owning screen goes away. */
    fun release() = stop()

    private fun startTicker(messageId: String) {
        stopTicker()
        tickerJob = scope.launch {
            while (isActive) {
                val active = player ?: break
                val position = runCatching { active.currentPosition }.getOrDefault(0)
                val duration = runCatching { active.duration }.getOrDefault(0)
                _state.value = _state.value.copy(
                    messageId = messageId,
                    positionMs = position,
                    durationMs = if (duration > 0) duration else _state.value.durationMs,
                    isPlaying = runCatching { active.isPlaying }.getOrDefault(false),
                )
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private companion object {
        const val TAG = "VoicePlayer"
        const val TICK_MS = 80L
    }
}
