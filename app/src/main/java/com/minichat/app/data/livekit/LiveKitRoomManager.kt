package com.minichat.app.data.livekit

import android.content.Context
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single LiveKit [Room] of the process.
 *
 * The room is a *transport* concern only: it knows nothing about Supabase. [CallViewModel] decides
 * when to connect and what to publish, and observes the flows below to render the UI.
 */
@Singleton
class LiveKitRoomManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    enum class Connection { Disconnected, Connecting, Connected, Reconnecting, Failed }

    data class State(
        val connection: Connection = Connection.Disconnected,
        val microphoneEnabled: Boolean = false,
        val cameraEnabled: Boolean = false,
        val speakerOn: Boolean = true,
        val localVideoTrack: VideoTrack? = null,
        val remoteVideoTrack: VideoTrack? = null,
        val remoteIdentity: String? = null,
        val remoteAudioAvailable: Boolean = false,
        val remoteCameraEnabled: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var room: Room? = null
    private var eventJob: Job? = null

    /** The room for the current call, created on demand. */
    fun room(): Room = room ?: buildRoom().also { room = it }

    private fun buildRoom(): Room {
        return LiveKit.create(
            appContext = context,
            options = RoomOptions(adaptiveStream = true, dynacast = true),
        )
    }

    suspend fun connect(url: String, token: String, scope: CoroutineScope) {
        _state.update { it.copy(connection = Connection.Connecting, error = null) }
        val activeRoom = room()
        observeEvents(activeRoom, scope)
        runCatching { activeRoom.connect(url, token) }
            .onSuccess {
                _state.update {
                    it.copy(connection = Connection.Connected, remoteAudioAvailable = true)
                }
            }
            .onFailure { failure ->
                Log.e(TAG, "LiveKit connect failed", failure)
                _state.update {
                    it.copy(
                        connection = Connection.Failed,
                        error = failure.message ?: "We couldn't connect the call.",
                    )
                }
            }
    }

    private fun observeEvents(activeRoom: Room, scope: CoroutineScope) {
        eventJob?.cancel()
        eventJob = scope.launch {
            activeRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected ->
                        _state.update { it.copy(connection = Connection.Connected) }

                    is RoomEvent.Reconnecting ->
                        _state.update { it.copy(connection = Connection.Reconnecting) }

                    is RoomEvent.Reconnected ->
                        _state.update { it.copy(connection = Connection.Connected) }

                    is RoomEvent.Disconnected ->
                        _state.update {
                            it.copy(
                                connection = Connection.Disconnected,
                                localVideoTrack = null,
                                remoteVideoTrack = null,
                                microphoneEnabled = false,
                                cameraEnabled = false,
                            )
                        }

                    is RoomEvent.FailedToConnect ->
                        _state.update {
                            it.copy(
                                connection = Connection.Failed,
                                error = event.error.message ?: "We couldn't connect the call.",
                            )
                        }

                    is RoomEvent.TrackSubscribed -> {
                        if (event.participant is RemoteParticipant) {
                            val track = event.track
                            if (track is VideoTrack) {
                                _state.update {
                                    it.copy(
                                        remoteVideoTrack = track,
                                        remoteCameraEnabled = true,
                                        remoteIdentity = (event.participant as? RemoteParticipant)?.identity?.value,
                                    )
                                }
                            } else {
                                _state.update { it.copy(remoteAudioAvailable = true) }
                            }
                        }
                    }

                    is RoomEvent.TrackUnsubscribed -> {
                        if (event.track is VideoTrack) {
                            _state.update { it.copy(remoteVideoTrack = null) }
                        }
                    }

                    is RoomEvent.TrackMuted -> {
                        if (event.participant is RemoteParticipant &&
                            event.publication.source == Track.Source.CAMERA
                        ) {
                            _state.update { it.copy(remoteCameraEnabled = false, remoteVideoTrack = null) }
                        }
                    }

                    is RoomEvent.TrackUnmuted -> {
                        if (event.participant is RemoteParticipant &&
                            event.publication.source == Track.Source.CAMERA
                        ) {
                            _state.update { state ->
                                state.copy(
                                    remoteCameraEnabled = true,
                                    remoteVideoTrack = event.publication.track as? VideoTrack
                                        ?: state.remoteVideoTrack,
                                )
                            }
                        }
                    }

                    is RoomEvent.ParticipantDisconnected ->
                        _state.update {
                            it.copy(remoteVideoTrack = null, remoteAudioAvailable = false)
                        }

                    is RoomEvent.TrackPublished -> {
                        if (event.participant is io.livekit.android.room.participant.LocalParticipant) {
                            refreshLocalVideo()
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun refreshLocalVideo() {
        val track = room()?.localParticipant
            ?.getTrackPublication(Track.Source.CAMERA)
            ?.track as? VideoTrack
        _state.update { it.copy(localVideoTrack = track) }
    }

    suspend fun setMicrophoneEnabled(enabled: Boolean) {
        val activeRoom = room()
        runCatching { activeRoom.localParticipant.setMicrophoneEnabled(enabled) }
            .onSuccess { _state.update { it.copy(microphoneEnabled = enabled) } }
            .onFailure { Log.w(TAG, "Microphone toggle failed", it) }
    }

    suspend fun setCameraEnabled(enabled: Boolean) {
        val activeRoom = room()
        runCatching { activeRoom.localParticipant.setCameraEnabled(enabled) }
            .onSuccess {
                _state.update { state -> state.copy(cameraEnabled = enabled) }
                refreshLocalVideo()
                if (!enabled) _state.update { it.copy(localVideoTrack = null) }
            }
            .onFailure { Log.w(TAG, "Camera toggle failed", it) }
    }

    fun flipCamera() {
        val videoTrack = room()?.localParticipant
            ?.getTrackPublication(Track.Source.CAMERA)
            ?.track as? LocalVideoTrack
            ?: return
        val next = when (videoTrack.options.position) {
            CameraPosition.FRONT -> CameraPosition.BACK
            CameraPosition.BACK -> CameraPosition.FRONT
            else -> null
        }
        runCatching { videoTrack.switchCamera(position = next) }
            .onFailure { Log.w(TAG, "Camera flip failed", it) }
    }

    /**
     * Routes audio to the loudspeaker (video calls, speaker-phone) or back to the earpiece.
     */
    fun setSpeakerOn(on: Boolean) {
        val handler = room()?.audioSwitchHandler ?: return
        val devices = handler.availableAudioDevices
        val target: AudioDevice? = if (on) {
            devices.firstOrNull { it is AudioDevice.Speakerphone }
                ?: devices.firstOrNull { it is AudioDevice.BluetoothHeadset }
        } else {
            devices.firstOrNull { it is AudioDevice.Earpiece }
                ?: devices.firstOrNull { it !is AudioDevice.Speakerphone }
        }
        runCatching { handler.selectDevice(target) }
            .onSuccess { _state.update { it.copy(speakerOn = on) } }
            .onFailure { Log.w(TAG, "Audio routing failed", it) }
    }

    fun currentSpeakerOn(): Boolean =
        (room()?.audioSwitchHandler as? AudioSwitchHandler)?.selectedAudioDevice
            is AudioDevice.Speakerphone

    /** Leaves the room and releases the camera. The Room object itself is reused. */
    suspend fun disconnect() {
        eventJob?.cancel()
        eventJob = null
        runCatching { room?.disconnect() }
        _state.value = State()
    }

    fun release() {
        eventJob?.cancel()
        eventJob = null
        runCatching { room?.disconnect() }
        room = null
        _state.value = State()
    }

    private companion object {
        const val TAG = "LiveKitRoom"
    }
}
