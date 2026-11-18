package com.minichat.app.data.livekit

import android.content.Context
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single LiveKit [Room] of the process.
 *
 * The room is a *transport* concern only: it knows nothing about Supabase. The call view models
 * decide when to connect and what to publish, and observe [state] to render the UI. A room can hold
 * one remote participant (direct call) or many (group call) — [State.peers] mirrors whoever is
 * currently subscribed.
 *
 * Reliability rules learned the hard way:
 *  * participants are re-read from the SDK every couple of seconds, so a dropped event can never
 *    leave a call without video;
 *  * publishing the camera is retried until the track really exists, because some devices fail the
 *    first capture attempt;
 *  * every failure ends up in [State.error] instead of an exception.
 */
@Singleton
class LiveKitRoomManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    enum class Connection { Disconnected, Connecting, Connected, Reconnecting, Failed }

    /** One remote participant, keyed by their LiveKit identity (which is the Supabase user id). */
    data class RemotePeer(
        val identity: String,
        val name: String,
        val videoTrack: VideoTrack? = null,
        val microphoneEnabled: Boolean = true,
        val cameraEnabled: Boolean = false,
    ) {
        val hasVideo: Boolean get() = videoTrack != null
    }

    data class State(
        val connection: Connection = Connection.Disconnected,
        /** LiveKit room the session is connected to; lets a screen re-attach without rejoining. */
        val roomName: String? = null,
        val microphoneEnabled: Boolean = false,
        val cameraEnabled: Boolean = false,
        val speakerOn: Boolean = true,
        val localVideoTrack: VideoTrack? = null,
        val peers: Map<String, RemotePeer> = emptyMap(),
        val error: String? = null,
        /** Bumped whenever a remote track event arrives; useful for diagnostics. */
        val remoteEventTick: Long = 0,
    ) {
        /** Convenience for one-to-one screens. */
        val remoteVideoTrack: VideoTrack? get() = peers.values.firstNotNullOfOrNull { it.videoTrack }
        val remoteCameraEnabled: Boolean get() = peers.values.any { it.cameraEnabled }
        val remoteAudioAvailable: Boolean get() = peers.isNotEmpty()
        val remoteIdentity: String? get() = peers.keys.firstOrNull()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var room: Room? = null
    private var eventJob: Job? = null
    private var syncJob: Job? = null

    /**
     * Room events outlive the screen that started the call, so a call keeps running (and keeps
     * updating its state) while the user is elsewhere in the app.
     */
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The room for the current call, created on demand. Returns null when WebRTC could not be
     * initialised on this device — callers surface that as a normal failure instead of crashing.
     */
    fun roomOrNull(): Room? {
        room?.let { return it }
        return runCatching {
            LiveKit.create(
                appContext = context,
                // Adaptive stream only delivers video to renderers that are already attached; a
                // late or re-created view can then stay black. Reliability beats bandwidth here.
                options = RoomOptions(adaptiveStream = false, dynacast = false),
            )
        }.onSuccess { room = it }
            .onFailure { failure ->
                Log.e(TAG, "LiveKit room could not be created", failure)
                _state.update {
                    it.copy(
                        connection = Connection.Failed,
                        error = "Voice and video aren't supported on this device.",
                    )
                }
            }
            .getOrNull()
    }

    /**
     * Joins [url] with [token] and publishes the microphone, plus the camera when [video] is true.
     * Calling this again for the room we are already inside is a no-op, so screens can re-attach.
     */
    suspend fun connect(
        url: String,
        token: String,
        roomName: String? = null,
        video: Boolean = false,
    ) {
        val current = _state.value
        if (current.connection == Connection.Connected && current.roomName == roomName) {
            // Already inside this room (the screen was recreated): just make sure media is on.
            enableAudio()
            if (video) enableVideo()
            return
        }

        _state.update { it.copy(connection = Connection.Connecting, error = null, roomName = roomName) }
        val activeRoom = roomOrNull()
        if (activeRoom == null) {
            _state.update {
                it.copy(
                    connection = Connection.Failed,
                    error = "Voice and video aren't supported on this device.",
                )
            }
            return
        }
        observeEvents(activeRoom)
        runCatching { activeRoom.connect(url, token) }
            .onSuccess {
                _state.update { state ->
                    state.copy(connection = Connection.Connected, peers = snapshotPeers(activeRoom))
                }
                startPeerSync(activeRoom)
                enableAudio()
                if (video) enableVideo()
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

    // ---------------------------------------------------------------------
    // Media
    // ---------------------------------------------------------------------

    private suspend fun enableAudio() {
        val activeRoom = roomOrNull() ?: return
        repeat(2) { attempt ->
            val ok = runCatching {
                activeRoom.localParticipant.setMicrophoneEnabled(true)
            }.isSuccess
            if (ok) {
                _state.update { it.copy(microphoneEnabled = true) }
                return
            }
            Log.w(TAG, "Microphone publish attempt ${attempt + 1} failed")
            delay(300L)
        }
    }

    /**
     * Turns the camera on and waits until the track actually exists. Devices occasionally fail the
     * first capture (camera still held by the system), so the publish is retried with a backoff.
     */
    private suspend fun enableVideo() {
        val activeRoom = roomOrNull() ?: return
        repeat(3) { attempt ->
            val failed = runCatching {
                activeRoom.localParticipant.setCameraEnabled(true)
            }.exceptionOrNull()
            refreshLocalVideo()
            if (_state.value.localVideoTrack != null) {
                _state.update { it.copy(cameraEnabled = true, error = null) }
                return
            }
            if (failed != null) {
                Log.w(TAG, "Camera publish attempt ${attempt + 1} failed", failed)
            }
            delay(400L * (attempt + 1))
        }
        Log.w(TAG, "Camera never produced a track")
        _state.update {
            it.copy(
                cameraEnabled = false,
                error = "Camera unavailable — check the camera permission, then turn the camera on again.",
            )
        }
    }

    // ---------------------------------------------------------------------
    // Participants
    // ---------------------------------------------------------------------

    /** Stable key for a participant, even on SDK builds where `identity` is nullable. */
    private fun keyOf(participant: Participant): String {
        val identity = participant.identity?.value
        if (!identity.isNullOrBlank()) return identity
        // Some SDK builds type `sid` as a value class, so only interpolate it.
        return "sid-${participant.sid}"
    }

    private fun snapshotPeers(activeRoom: Room): Map<String, RemotePeer> {
        val peers = mutableMapOf<String, RemotePeer>()
        activeRoom.remoteParticipants.values.forEach { participant ->
            val videoTrack = participant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
            val micPublication = participant.getTrackPublication(Track.Source.MICROPHONE)
            peers[keyOf(participant)] = RemotePeer(
                identity = keyOf(participant),
                name = displayName(participant),
                videoTrack = videoTrack,
                microphoneEnabled = micPublication?.muted != true,
                cameraEnabled = videoTrack != null,
            )
        }
        return peers
    }

    /**
     * Re-reads the room every two seconds. LiveKit events are the fast path, this is the safety
     * net: a missed `TrackSubscribed` (or a device that silently stops publishing) can no longer
     * leave the other side staring at an avatar.
     */
    private fun startPeerSync(activeRoom: Room) {
        syncJob?.cancel()
        syncJob = eventScope.launch {
            while (isActive) {
                delay(PEER_SYNC_MS)
                if (_state.value.connection != Connection.Connected) continue
                runCatching {
                    _state.update { it.copy(peers = snapshotPeers(activeRoom)) }
                    refreshLocalVideo()
                }
            }
        }
    }

    private fun displayName(participant: Participant?): String {
        val explicit = participant?.name?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit
        val identity = participant?.identity?.value
        return identity?.take(8)?.let { "Guest $it" } ?: "Guest"
    }

    // ---------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------

    private fun observeEvents(activeRoom: Room) {
        eventJob?.cancel()
        eventJob = eventScope.launch {
            activeRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected, is RoomEvent.Reconnected ->
                        _state.update {
                            it.copy(
                                connection = Connection.Connected,
                                peers = snapshotPeers(activeRoom),
                            )
                        }

                    is RoomEvent.Reconnecting ->
                        _state.update { it.copy(connection = Connection.Reconnecting) }

                    is RoomEvent.Disconnected -> {
                        syncJob?.cancel()
                        syncJob = null
                        _state.update {
                            it.copy(
                                connection = Connection.Disconnected,
                                localVideoTrack = null,
                                peers = emptyMap(),
                                microphoneEnabled = false,
                                cameraEnabled = false,
                            )
                        }
                    }

                    is RoomEvent.FailedToConnect ->
                        _state.update {
                            it.copy(
                                connection = Connection.Failed,
                                error = event.error.message ?: "We couldn't connect the call.",
                            )
                        }

                    // Every participant/track change just refreshes the snapshot: it is the single
                    // source of truth, so the UI can never drift from the room.
                    is RoomEvent.ParticipantConnected,
                    is RoomEvent.ParticipantDisconnected,
                    is RoomEvent.TrackSubscribed,
                    is RoomEvent.TrackUnsubscribed,
                    is RoomEvent.TrackMuted,
                    is RoomEvent.TrackUnmuted -> _state.update {
                        it.copy(
                            peers = snapshotPeers(activeRoom),
                            remoteEventTick = it.remoteEventTick + 1,
                        )
                    }

                    is RoomEvent.TrackPublished -> {
                        refreshLocalVideo()
                        _state.update { it.copy(peers = snapshotPeers(activeRoom)) }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun refreshLocalVideo() {
        val track = roomOrNull()?.localParticipant
            ?.getTrackPublication(Track.Source.CAMERA)
            ?.track as? VideoTrack
        _state.update { it.copy(localVideoTrack = track, cameraEnabled = track != null) }
    }

    // ---------------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------------

    suspend fun setMicrophoneEnabled(enabled: Boolean) {
        val activeRoom = roomOrNull() ?: return
        runCatching { activeRoom.localParticipant.setMicrophoneEnabled(enabled) }
            .onSuccess { _state.update { it.copy(microphoneEnabled = enabled) } }
            .onFailure { Log.w(TAG, "Microphone toggle failed", it) }
    }

    suspend fun setCameraEnabled(enabled: Boolean) {
        if (enabled) {
            enableVideo()
            return
        }
        val activeRoom = roomOrNull() ?: return
        runCatching { activeRoom.localParticipant.setCameraEnabled(false) }
            .onSuccess {
                _state.update { state ->
                    state.copy(cameraEnabled = false, localVideoTrack = null)
                }
            }
            .onFailure { Log.w(TAG, "Camera toggle failed", it) }
    }

    fun flipCamera() {
        val videoTrack = roomOrNull()?.localParticipant
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

    /** Re-attaches the camera after the app came back to the foreground mid-call. */
    suspend fun resumeMedia(video: Boolean) {
        if (_state.value.connection != Connection.Connected) return
        enableAudio()
        if (video) enableVideo()
    }

    /** Routes audio to the loudspeaker (video calls, speaker-phone) or back to the earpiece. */
    fun setSpeakerOn(on: Boolean) {
        val handler = roomOrNull()?.audioSwitchHandler ?: return
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

    fun currentSpeakerOn(): Boolean {
        val handler = roomOrNull()?.audioSwitchHandler as? AudioSwitchHandler
        return handler?.selectedAudioDevice is AudioDevice.Speakerphone
    }

    /** Leaves the room and releases the camera. The Room object itself is reused. */
    suspend fun disconnect() {
        eventJob?.cancel()
        eventJob = null
        syncJob?.cancel()
        syncJob = null
        runCatching { room?.disconnect() }
        _state.value = State()
    }

    fun release() {
        eventJob?.cancel()
        eventJob = null
        syncJob?.cancel()
        syncJob = null
        eventScope.coroutineContext.cancelChildren()
        runCatching { room?.disconnect() }
        room = null
        _state.value = State()
    }

    private companion object {
        const val TAG = "LiveKitRoom"
        const val PEER_SYNC_MS = 2_000L
    }
}
