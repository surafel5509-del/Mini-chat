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
 * The room is a *transport* concern only: it knows nothing about Supabase. The call view models
 * decide when to connect and what to publish, and observe [state] to render the UI. A room can hold
 * one remote participant (direct call) or many (group call) — [State.peers] mirrors whoever is
 * currently subscribed.
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
        val microphoneEnabled: Boolean = false,
        val cameraEnabled: Boolean = false,
        val speakerOn: Boolean = true,
        val localVideoTrack: VideoTrack? = null,
        val peers: Map<String, RemotePeer> = emptyMap(),
        val error: String? = null,
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

    /** The room for the current call, created on demand. */
    fun room(): Room = room ?: buildRoom().also { room = it }

    private fun buildRoom(): Room = LiveKit.create(
        appContext = context,
        options = RoomOptions(adaptiveStream = true, dynacast = true),
    )

    suspend fun connect(url: String, token: String, scope: CoroutineScope) {
        _state.update { it.copy(connection = Connection.Connecting, error = null) }
        val activeRoom = room()
        observeEvents(activeRoom, scope)
        runCatching { activeRoom.connect(url, token) }
            .onSuccess {
                _state.update { current ->
                    current.copy(connection = Connection.Connected, peers = snapshotPeers(activeRoom))
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

    // ---------------------------------------------------------------------
    // Participants
    // ---------------------------------------------------------------------

    private fun snapshotPeers(activeRoom: Room): Map<String, RemotePeer> {
        val peers = mutableMapOf<String, RemotePeer>()
        activeRoom.remoteParticipants.values.forEach { participant ->
            val identity = participant.identity?.value ?: return@forEach
            val videoTrack = participant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
            val micPublication = participant.getTrackPublication(Track.Source.MICROPHONE)
            peers[identity] = RemotePeer(
                identity = identity,
                name = displayName(participant),
                videoTrack = videoTrack,
                microphoneEnabled = micPublication?.muted != true,
                cameraEnabled = videoTrack != null,
            )
        }
        return peers
    }

    private fun displayName(participant: Participant?): String {
        val explicit = participant?.name?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit
        val identity = participant?.identity?.value
        return identity?.take(8)?.let { "Guest $it" } ?: "Guest"
    }

    private fun updatePeer(identity: String?, transform: (RemotePeer) -> RemotePeer) {
        val id = identity ?: return
        _state.update { current ->
            val existing = current.peers[id] ?: RemotePeer(identity = id, name = "Guest")
            current.copy(peers = current.peers + (id to transform(existing)))
        }
    }

    private fun removePeer(identity: String?) {
        val id = identity ?: return
        _state.update { current -> current.copy(peers = current.peers - id) }
    }

    // ---------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------

    private fun observeEvents(activeRoom: Room, scope: CoroutineScope) {
        eventJob?.cancel()
        eventJob = scope.launch {
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

                    is RoomEvent.Disconnected ->
                        _state.update {
                            it.copy(
                                connection = Connection.Disconnected,
                                localVideoTrack = null,
                                peers = emptyMap(),
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

                    is RoomEvent.ParticipantConnected -> {
                        val participant = event.participant as? RemoteParticipant ?: return@collect
                        val identity = participant.identity?.value ?: return@collect
                        _state.update { current ->
                            current.copy(
                                peers = current.peers + (identity to RemotePeer(
                                    identity = identity,
                                    name = displayName(participant),
                                )),
                            )
                        }
                    }

                    is RoomEvent.ParticipantDisconnected ->
                        removePeer((event.participant as? RemoteParticipant)?.identity?.value)

                    is RoomEvent.TrackSubscribed -> {
                        val participant = event.participant as? RemoteParticipant ?: return@collect
                        val identity = participant.identity?.value ?: return@collect
                        val track = event.track
                        _state.update { current ->
                            val existing = current.peers[identity]
                                ?: RemotePeer(identity = identity, name = displayName(participant))
                            val updated = if (track is VideoTrack) {
                                existing.copy(videoTrack = track, cameraEnabled = true)
                            } else {
                                existing.copy(microphoneEnabled = true)
                            }
                            current.copy(peers = current.peers + (identity to updated))
                        }
                    }

                    is RoomEvent.TrackUnsubscribed -> {
                        val participant = event.participant as? RemoteParticipant ?: return@collect
                        val identity = participant.identity?.value ?: return@collect
                        if (event.track is VideoTrack) {
                            updatePeer(identity) { it.copy(videoTrack = null, cameraEnabled = false) }
                        }
                    }

                    is RoomEvent.TrackMuted -> {
                        val participant = event.participant as? RemoteParticipant ?: return@collect
                        when (event.publication.source) {
                            Track.Source.MICROPHONE ->
                                updatePeer(participant.identity?.value) { it.copy(microphoneEnabled = false) }

                            Track.Source.CAMERA ->
                                updatePeer(participant.identity?.value) {
                                    it.copy(videoTrack = null, cameraEnabled = false)
                                }

                            else -> Unit
                        }
                    }

                    is RoomEvent.TrackUnmuted -> {
                        val participant = event.participant as? RemoteParticipant ?: return@collect
                        when (event.publication.source) {
                            Track.Source.MICROPHONE ->
                                updatePeer(participant.identity?.value) { it.copy(microphoneEnabled = true) }

                            Track.Source.CAMERA -> {
                                val track = event.publication.track as? VideoTrack
                                updatePeer(participant.identity?.value) {
                                    it.copy(videoTrack = track ?: it.videoTrack, cameraEnabled = true)
                                }
                            }

                            else -> Unit
                        }
                    }

                    is RoomEvent.TrackPublished -> {
                        if (event.participant is LocalParticipant) {
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

    // ---------------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------------

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

    /** Routes audio to the loudspeaker (video calls, speaker-phone) or back to the earpiece. */
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

    fun currentSpeakerOn(): Boolean {
        val handler = room()?.audioSwitchHandler as? AudioSwitchHandler
        return handler?.selectedAudioDevice is AudioDevice.Speakerphone
    }

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
