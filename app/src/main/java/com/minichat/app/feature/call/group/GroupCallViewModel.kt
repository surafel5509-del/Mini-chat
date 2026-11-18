package com.minichat.app.feature.call.group

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.call.CallForegroundService
import com.minichat.app.core.di.ApplicationScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.data.livekit.LiveKitRoomManager
import com.minichat.app.domain.model.CallStatus
import com.minichat.app.domain.model.GroupCall
import com.minichat.app.domain.model.GroupCallParticipant
import com.minichat.app.domain.repository.AuthRepository
import com.minichat.app.domain.repository.GroupCallRepository
import com.minichat.app.domain.repository.ProfileRepository
import com.minichat.app.notifications.NotificationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives one group call room: roster, media, and the join/leave lifecycle.
 *
 * The roster is refreshed on a slow poll (4 s) because participants can join and leave from other
 * devices; the call *status* comes over Realtime so the room closes immediately when the host ends
 * it. Media state comes from [LiveKitRoomManager].
 */
@HiltViewModel
class GroupCallViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupCallRepository: GroupCallRepository,
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    private val roomManager: LiveKitRoomManager,
    private val notificationHelper: NotificationHelper,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    enum class Phase { Loading, Incoming, Connecting, Active, Ended, Failed }

    data class UiState(
        val call: GroupCall? = null,
        val participants: List<GroupCallParticipant> = emptyList(),
        val phase: Phase = Phase.Loading,
        val joined: Boolean = false,
        val isHost: Boolean = false,
        val micEnabled: Boolean = true,
        val cameraEnabled: Boolean = false,
        val speakerOn: Boolean = false,
        val isFrontCamera: Boolean = true,
        val elapsedSeconds: Long = 0,
        val error: String? = null,
        val showPermissions: Boolean = false,
        val localVideoTrack: VideoTrack? = null,
        val peers: Map<String, LiveKitRoomManager.RemotePeer> = emptyMap(),
    ) {
        val isVideo: Boolean get() = call?.type?.isVideo == true
        val inCallCount: Int get() = participants.count { it.status.isInCall }
        val invitedCount: Int get() = participants.count {
            it.status == GroupCallParticipant.ParticipationStatus.Invited
        }
    }

    val callId: String = savedStateHandle.get<String>("callId").orEmpty()

    val myId: String? = authRepository.currentUserId()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var mediaJob: Job? = null
    private var timerJob: Job? = null
    private var rosterJob: Job? = null
    private var connecting = false
    private var selfLeft = false

    init {
        observeRoom()
        loadCall()
        observeCallUpdates()
    }

    // ---------------------------------------------------------------------
    // Bootstrap
    // ---------------------------------------------------------------------

    private fun loadCall() {
        viewModelScope.launch {
            when (val result = groupCallRepository.call(callId)) {
                is AppResult.Success -> {
                    val call = result.data
                    if (call == null) {
                        _uiState.update {
                            it.copy(phase = Phase.Failed, error = "This call no longer exists.")
                        }
                        return@launch
                    }
                    _uiState.update {
                        it.copy(call = call, isHost = call.isHost(myId), phase = firstPhase(call))
                    }
                    startRoster()
                    if (call.status.isTerminal) finish(call)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(phase = Phase.Failed, error = result.message)
                }
            }
        }
    }

    private fun firstPhase(call: GroupCall): Phase = if (call.status.isTerminal) Phase.Ended else Phase.Incoming

    private fun observeCallUpdates() {
        val userId = myId ?: return
        viewModelScope.launch {
            groupCallRepository.callUpdates(userId).collect { update ->
                if (update.id != callId) return@collect
                val mine = _uiState.value
                _uiState.update { it.copy(call = update, isHost = update.isHost(myId)) }
                if (update.status.isTerminal && mine.phase != Phase.Ended) {
                    finish(update)
                } else if (update.status.isLive && mine.joined && mine.phase == Phase.Incoming) {
                    startMedia(update)
                }
                if (mine.joined) startTimer(update)
            }
        }
    }

    private fun startRoster() {
        if (rosterJob?.isActive == true) return
        rosterJob = viewModelScope.launch {
            while (isActive) {
                refreshRoster()
                delay(4_000)
            }
        }
    }

    private suspend fun refreshRoster() {
        when (val result = groupCallRepository.participants(callId)) {
            is AppResult.Success -> {
                val mine = result.data.firstOrNull { it.userId == myId }
                val joined = mine?.status?.isInCall == true
                val wasJoined = _uiState.value.joined
                _uiState.update { state ->
                    state.copy(
                        participants = result.data,
                        joined = joined || state.joined,
                        showPermissions = state.showPermissions || (joined && !hasAudioPermission()),
                    )
                }
                // Reopening the screen while already inside the room: reconnect the media.
                val call = _uiState.value.call
                if (joined && !wasJoined && call != null && call.status.isLive && !selfLeft) {
                    startMedia(call)
                }
            }

            is AppResult.Failure -> Unit
        }
    }

    // ---------------------------------------------------------------------
    // Media
    // ---------------------------------------------------------------------

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasPermissions(isVideo: Boolean): Boolean {
        val camera = !isVideo || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        return hasAudioPermission() && camera
    }

    fun onPermissionsGranted() {
        _uiState.update { it.copy(showPermissions = false) }
        val call = _uiState.value.call ?: return
        if (_uiState.value.joined && call.status.isLive) startMedia(call)
    }

    private fun startMedia(call: GroupCall) {
        if (connecting || mediaJob?.isActive == true) return
        val isVideo = call.type.isVideo
        if (!hasPermissions(isVideo)) {
            _uiState.update { it.copy(showPermissions = true) }
            return
        }
        connecting = true
        mediaJob = viewModelScope.launch {
            _uiState.update { it.copy(phase = Phase.Connecting, cameraEnabled = isVideo) }
            when (val result = groupCallRepository.credentials(call.id)) {
                is AppResult.Success -> runCatching {
                    roomManager.connect(
                        url = result.data.url,
                        token = result.data.token,
                        roomName = result.data.roomName,
                        video = isVideo,
                    )
                    CallForegroundService.start(
                        context = context,
                        callId = call.id,
                        title = call.title,
                        subtitle = "Group call · ${_uiState.value.inCallCount} on the call",
                        isVideo = isVideo,
                    )
                    roomManager.setSpeakerOn(true)
                    notificationHelper.cancelIncomingGroupCall(call.id)
                    _uiState.update {
                        it.copy(phase = Phase.Active, micEnabled = true, speakerOn = true)
                    }
                    startTimer(call)
                }.onFailure { failure ->
                    Log.e(TAG, "Could not join the group room", failure)
                    _uiState.update {
                        it.copy(
                            phase = Phase.Failed,
                            error = "The call couldn't start on this device.",
                        )
                    }
                }

                is AppResult.Failure -> {
                    Log.w(TAG, "Group call credentials failed: ${result.message}")
                    _uiState.update { it.copy(phase = Phase.Failed, error = result.message) }
                }
            }
            connecting = false
        }
    }

    private fun observeRoom() {
        viewModelScope.launch {
            roomManager.state.collect { room ->
                val state = _uiState.value
                val phase = when {
                    state.phase == Phase.Ended || state.phase == Phase.Failed -> state.phase
                    room.connection == LiveKitRoomManager.Connection.Connected &&
                        state.joined -> Phase.Active

                    room.connection == LiveKitRoomManager.Connection.Failed -> Phase.Failed
                    else -> state.phase
                }
                _uiState.update {
                    it.copy(
                        phase = phase,
                        micEnabled = room.microphoneEnabled,
                        cameraEnabled = room.cameraEnabled && room.localVideoTrack != null,
                        localVideoTrack = room.localVideoTrack,
                        peers = room.peers,
                        error = if (phase == Phase.Failed) room.error ?: it.error else it.error,
                    )
                }
            }
        }
    }

    private fun startTimer(call: GroupCall) {
        if (timerJob?.isActive == true) return
        timerJob = viewModelScope.launch {
            while (isActive) {
                _uiState.update { state ->
                    state.copy(elapsedSeconds = (state.call ?: call).connectedSeconds())
                }
                delay(1_000)
            }
        }
    }

    private fun finish(call: GroupCall) {
        timerJob?.cancel()
        timerJob = null
        rosterJob?.cancel()
        rosterJob = null
        mediaJob?.cancel()
        mediaJob = null
        connecting = false

        appScope.launch { roomManager.disconnect() }
        CallForegroundService.stop(context)
        notificationHelper.cancelIncomingGroupCall(call.id)

        _uiState.update {
            it.copy(
                call = call,
                phase = Phase.Ended,
                elapsedSeconds = call.connectedSeconds(),
                localVideoTrack = null,
                peers = emptyMap(),
            )
        }
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    fun join() {
        val call = _uiState.value.call ?: return
        notificationHelper.cancelIncomingGroupCall(call.id)
        _uiState.update { it.copy(phase = Phase.Connecting, error = null) }
        viewModelScope.launch {
            when (val result = groupCallRepository.join(call.id)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(call = result.data, joined = true, isHost = result.data.isHost(myId))
                    }
                    startRoster()
                    startMedia(result.data)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(phase = Phase.Failed, error = result.message)
                }
            }
        }
    }

    fun decline() {
        val call = _uiState.value.call ?: return
        selfLeft = true
        notificationHelper.cancelIncomingGroupCall(call.id)
        viewModelScope.launch {
            groupCallRepository.decline(call.id)
            finish(call)
        }
    }

    fun leave() {
        val call = _uiState.value.call ?: return
        selfLeft = true
        viewModelScope.launch {
            when (val result = groupCallRepository.leave(call.id)) {
                is AppResult.Success -> finish(result.data)
                is AppResult.Failure -> finish(call.copy(status = CallStatus.Ended))
            }
        }
    }

    fun endForEveryone() {
        val call = _uiState.value.call ?: return
        selfLeft = true
        viewModelScope.launch {
            when (val result = groupCallRepository.end(call.id)) {
                is AppResult.Success -> finish(result.data)
                is AppResult.Failure -> finish(call.copy(status = CallStatus.Ended))
            }
        }
    }

    fun toggleMicrophone() {
        val next = !_uiState.value.micEnabled
        _uiState.update { it.copy(micEnabled = next) }
        viewModelScope.launch { roomManager.setMicrophoneEnabled(next) }
    }

    fun toggleCamera() {
        val next = !_uiState.value.cameraEnabled
        _uiState.update { it.copy(cameraEnabled = next) }
        viewModelScope.launch { roomManager.setCameraEnabled(next) }
    }

    fun flipCamera() {
        roomManager.flipCamera()
        _uiState.update { it.copy(isFrontCamera = !it.isFrontCamera) }
    }

    fun toggleSpeaker() {
        val next = !_uiState.value.speakerOn
        _uiState.update { it.copy(speakerOn = next) }
        roomManager.setSpeakerOn(next)
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        val call = _uiState.value.call
        if (call != null && !call.status.isTerminal && !selfLeft && !_uiState.value.joined) {
            appScope.launch { groupCallRepository.decline(call.id) }
        }
    }

    private companion object {
        const val TAG = "GroupCallViewModel"
    }
}
