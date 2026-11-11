package com.minichat.app.core.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records a short voice note with [MediaRecorder].
 *
 * Design notes:
 *  * Audio is written to AAC in an MP4 container — the only format every Android
 *    device can both record and play back, and one of the MIME types the
 *    `chat-media` bucket accepts.
 *  * Recording is deliberately capped ([MAX_DURATION_MS]); the caller also stops
 *    it on a timer so the tip of the clip is never truncated by the encoder.
 *  * [stop] is safe to call from any state, which matters because the UI can be
 *    torn down mid-recording (rotation, back press, process death).
 */
@Singleton
class VoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Recording(
        val file: File,
        val durationMs: Int,
        val sizeBytes: Long,
    )

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0L

    val isRecording: Boolean get() = recorder != null

    /**
     * Starts a new recording. Returns false when the microphone is unavailable or
     * permission has not been granted — the UI shows a friendly message instead of
     * crashing.
     */
    fun start(): Boolean {
        if (recorder != null) return true

        val file = File.createTempFile("minichat_voice_", ".m4a", context.cacheDir)

        val instance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            instance.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // Voice-optimised: mono, 22.05 kHz, 48 kbps ≈ 6 KB per second,
                // so a 7 second note is roughly 42 KB on the wire.
                setAudioChannels(1)
                setAudioSamplingRate(22_050)
                setAudioEncodingBitRate(48_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = instance
            outputFile = file
            startedAt = System.currentTimeMillis()
            true
        } catch (error: Exception) {
            Log.w(TAG, "Could not start recording", error)
            runCatching { instance.release() }
            file.delete()
            recorder = null
            outputFile = null
            false
        }
    }

    /** Milliseconds elapsed since [start]. */
    fun elapsedMs(): Int =
        if (startedAt == 0L) 0 else (System.currentTimeMillis() - startedAt).toInt().coerceAtLeast(0)

    /**
     * Stops and returns the finished clip, or null when nothing usable was
     * captured (too short, encoder failure, already stopped).
     */
    fun stop(): Recording? {
        val instance = recorder ?: return null
        val file = outputFile
        val duration = elapsedMs()

        recorder = null
        outputFile = null
        startedAt = 0L

        return try {
            instance.stop()
            instance.release()

            if (file == null || !file.exists() || file.length() <= 0L) {
                file?.delete()
                return null
            }
            // Under ~400ms there is no meaningful audio and the AAC frame is incomplete.
            if (duration < MIN_DURATION_MS) {
                file.delete()
                return null
            }
            Recording(file = file, durationMs = duration, sizeBytes = file.length())
        } catch (error: Exception) {
            Log.w(TAG, "Could not finish recording", error)
            runCatching { instance.release() }
            file?.delete()
            null
        }
    }

    /** Aborts a recording and removes the partial file. */
    fun cancel() {
        val instance = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        startedAt = 0L

        runCatching { instance?.stop() }
        runCatching { instance?.release() }
        runCatching { file?.delete() }
    }

    companion object {
        private const val TAG = "VoiceRecorder"

        /** The composer stops automatically here. */
        const val MAX_DURATION_MS = 7_000

        /** Anything shorter is noise, not a message. */
        const val MIN_DURATION_MS = 400
    }
}
