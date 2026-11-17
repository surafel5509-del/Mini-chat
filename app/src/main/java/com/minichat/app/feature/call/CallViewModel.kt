package com.minichat.app.feature.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.call.CallForegroundService
import com.minichat.app.core.di.ApplicationScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.data.livekit.LiveKitRoomManager
import com.minichat.app.domain.model.Call
import com.minichat.app.domain.model.CallStatus
import com.minichat.app.domain.model.CallType
import com.minichat.app.domain.repository.AuthRepository
import com.minichat.app.domain.repository.CallRepository
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

@HiltViewModel
class CallViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val callRepository: CallRepository,
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val roomManager: LiveKitRoomManager,
    private val notificationHelper: NotificationHelper,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    enum class Phase { Loading, Incoming, Outgoing, Connecting, Active, Ended, Failed }

    data class UiState(
        val call: Call? = null,
        val phase: Phase = Phase.Loading,
        val peerName: String = "",
        val peerUsername: String = "",
        val peerAvatarUrl: String? = null,
        val isCaller: Boolean = false,
        val isVideo: Boolean = false,
        val micEnabled: Boolean = true,
        val cameraEnabled: Boolean = false,
        val speakerOn: Boolean = false,
        val isFrontCamera: Boolean = true,
        val elapsedSeconds: Long = 0,
        val error: String? = null,
        val callingConfigured: Boolean = true,
        val localVideoTrack: VideoTrack? = null,
        val remoteVideoTrack: VideoTrack? = null,
        val reconnecting: Boolean = false,
        val remoteCameraEnabled: Boolean = false,
        val showPermissions: Boolean = false,
    ) {
        val isOngoing: Boolean get() = phase == Phase.Incoming || phase == Phase.Outgoing ||
            phase == Phase.Connecting || phase == Phase.Active
    }

    val callId: String = savedStateHandle.get<String>("callId").orEmpty()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var mediaJob: Job? = null
    private var timerJob: Job? = null
    private var connecting = false
    private var endedByMe = false

    init {
        observeRoom()
        loadCall()
        observeCallUpdates()
        checkConfiguration()
    }

    // ---------------------------------------------------------------------
    // Bootstrapping
    // ---------------------------------------------------------------------

    private fun checkConfiguration() {
        viewModelScope.launch {
            when (val result = callRepository.callingConfigured()) {
                is AppResult.Success -> _uiState.update { it.copy(callingConfigured = result.data) }
                is AppResult.Failure -> Unit
            }
        }
    }

    private fun loadCall() {
        viewModelScope.launch {
            when (val result = callRepository.call(callId)) {
                is AppResult.Success -> {
                    val call = result.data
                    if (call == null) {
                        _uiState.update {
                            it.copy(phase = Phase.Failed, error = "This call no longer exists.")
                        }
                        return@launch
                    }
                    applyCall(call, initial = true)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(phase = Phase.Failed, error = result.message)
                }
            }
        }
    }

    private fun observeCallUpdates() {
        val userId = authRepository.currentUserId() ?: return
        viewModelScope.launch {
            callRepository.callUpdates(userId).collect { call ->
                if (call.id != callId) return@collect
                applyCall(call, initial = false)
            }
        }
    }

    private fun applyCall(call: Call, initial: Boolean) {
        val userId = authRepository.currentUserId()
        val isCaller = call.isCaller(userId)
        val peerId = call.peerId(userId)

        _uiState.update {
            it.copy(
                call = call,
                isCaller = isCaller,
                isVideo = call.type.isVideo,
                peerAvatarUrl = it.peerAvatarUrl,
            )
        }

        if (initial) {
            loadPeer(peerId)
        }

        when {
            call.status == CallStatus.Active -> {
                // The peer just answered: the caller's media session is already up, so promote the
                // phase here instead of waiting for another room event.
                _uiState.update {
                    it.copy(
                        phase = if (roomManager.state.value.connection ==
                            LiveKitRoomManager.Connection.Connected
                        ) {
                            Phase.Active
                        } else {
                            Phase.Connecting
                        },
                    )
                }
                startMedia(call)
                startTimer(call)
            }

            call.status == CallStatus.Ringing && !isCaller -> {
                _uiState.update { it.copy(phase = Phase.Incoming) }
            }

            call.status == CallStatus.Ringing && isCaller -> {
                _uiState.update { it.copy(phase = Phase.Outgoing) }
                startMedia(call)
            }

            call.status.isTerminal -> finish(call)
        }
    }

    private fun loadPeer(peerId: String) {
        if (peerId.isBlank()) return
        viewModelScope.launch {
            when (val result = profileRepository.profile(peerId)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        peerName = result.data.displayName,
                        peerUsername = result.data.username,
                        peerAvatarUrl = result.data.avatarUrl,
                    )
                }

                is AppResult.Failure -> Unit
            }
        }
    }

    // ---------------------------------------------------------------------
    // Media
    // ---------------------------------------------------------------------

    private fun hasPermissions(call: Call): Boolean {
        val audio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val camera = !call.type.isVideo ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        return audio && camera
    }

    fun onPermissionsGranted() {
        val call = _uiState.value.call ?: return
        _uiState.update { it.copy(showPermissions = false) }
        if (!call.status.isLive) return
        if (call.status == CallStatus.Active) {
            startMedia(call)
            startTimer(call)
        } else if (_uiState.value.isCaller) {
            startMedia(call)
        }
    }

    private fun startMedia(call: Call) {
        if (connecting || mediaJob?.isActive == true) return
        if (!hasPermissions(call)) {
            _uiState.update { it.copy(showPermissions = true) }
            return
        }
        connecting = true
        mediaJob = viewModelScope.launch {
            _uiState.update {
                it.copy(phase = if (call.status == CallStatus.Active) Phase.Connecting else Phase.Outgoing)
            }

            when (val result = callRepository.credentials(call.id)) {
                is AppResult.Success -> runCatching {
                    val credentials = result.data
                    roomManager.connect(credentials.url, credentials.token, credentials.roomName)
                    startForegroundService(call)
                    roomManager.setMicrophoneEnabled(true)
                    if (call.type.isVideo) {
                        roomManager.setCameraEnabled(true)
                    }
                    roomManager.setSpeakerOn(call.type.isVideo)
                    _uiState.update {
                        it.copy(
                            phase = if (call.status == CallStatus.Active) Phase.Active else it.phase,
                            speakerOn = call.type.isVideo,
                            cameraEnabled = call.type.isVideo,
                            micEnabled = true,
                        )
                    }
                }.onFailure { failure ->
                    Log.e(TAG, "Could not start the media session", failure)
                    _uiState.update {
                        it.copy(
                            phase = Phase.Failed,
                            error = "The call couldn't start on this device.",
                        )
                    }
                }

                is AppResult.Failure -> {
                    Log.w(TAG, "Could not get call credentials: ${result.message}")
                    notificationHelper.cancelIncomingCall(call.id)
                    _uiState.update {
                        it.copy(phase = Phase.Failed, error = result.message)
                    }
                    callRepository.endCall(call.id)
                }
            }
            connecting = false
        }
    }

    private fun observeRoom() {
        viewModelScope.launch {
            roomManager.state.collect { room ->
                val phase = _uiState.value.phase
                val call = _uiState.value.call
                val nextPhase = when {
                    phase == Phase.Ended || phase == Phase.Failed -> phase
                    room.connection == LiveKitRoomManager.Connection.Connected &&
                        call?.status == CallStatus.Active -> Phase.Active

                    room.connection == LiveKitRoomManager.Connection.Failed -> Phase.Failed
                    else -> phase
                }

                _uiState.update {
                    it.copy(
                        phase = nextPhase,
                        micEnabled = room.microphoneEnabled,
                        cameraEnabled = room.cameraEnabled && room.localVideoTrack != null,
                        localVideoTrack = room.localVideoTrack,
                        remoteVideoTrack = room.remoteVideoTrack,
                        remoteCameraEnabled = room.remoteCameraEnabled,
                        reconnecting = room.connection == LiveKitRoomManager.Connection.Reconnecting,
                        error = if (nextPhase == Phase.Failed) room.error ?: it.error else it.error,
                    )
                }
            }
        }
    }

    private fun startTimer(call: Call) {
        if (timerJob?.isActive == true) return
        timerJob = viewModelScope.launch {
            while (isActive) {
                _uiState.update { state ->
                    state.copy(elapsedSeconds = state.call?.connectedSeconds() ?: 0L)
                }
                delay(1_000)
            }
        }
    }

    private fun finish(call: Call) {
        timerJob?.cancel()
        timerJob = null
        mediaJob?.cancel()
        mediaJob = null
        connecting = false

        appScope.launch { roomManager.disconnect() }
        CallForegroundService.stop(context)
        notificationHelper.cancelIncomingCall(call.id)

        _uiState.update {
            it.copy(
                call = call,
                phase = Phase.Ended,
                elapsedSeconds = call.connectedSeconds() ?: 0L,
                localVideoTrack = null,
                remoteVideoTrack = null,
            )
        }
    }

    private fun startForegroundService(call: Call) {
        val title = _uiState.value.peerName.ifBlank { "MINI CHAT call" }
        val subtitle = if (call.type.isVideo) "Video call in progress" else "Voice call in progress"
        runCatching { CallForegroundService.start(context, call.id, title, subtitle, call.type.isVideo) }
    }

    // ---------------------------------------------------------------------
    // User actions
    // ---------------------------------------------------------------------

    fun accept() {
        val call = _uiState.value.call ?: return
        notificationHelper.cancelIncomingCall(call.id)
        _uiState.update { it.copy(phase = Phase.Connecting) }
        viewModelScope.launch {
            when (val result = callRepository.answerCall(call.id)) {
                is AppResult.Success -> {
                    applyCall(result.data, initial = false)
                    startMedia(result.data)
                    startTimer(result.data)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(phase = Phase.Failed, error = result.message)
                }
            }
        }
    }

    fun decline() {
        val call = _uiState.value.call ?: return
        endedByMe = true
        notificationHelper.cancelIncomingCall(call.id)
        viewModelScope.launch {
            when (val result = callRepository.declineCall(call.id)) {
                is AppResult.Success -> finish(result.data)
                is AppResult.Failure -> _uiState.update {
                    it.copy(phase = Phase.Failed, error = result.message)
                }
            }
        }
    }

    fun hangUp() {
        val call = _uiState.value.call ?: return
        endedByMe = true
        viewModelScope.launch {
            when (val result = callRepository.endCall(call.id)) {
                is AppResult.Success -> finish(result.data)
                is AppResult.Failure -> {
                    // Even if the network is gone, leave the media session cleanly.
                    finish(call.copy(status = CallStatus.Ended))
                }
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

    fun togglePermissionsPrompt(show: Boolean) {
        _uiState.update { it.copy(showPermissions = show) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Called when the user leaves the screen: ringing calls are cancelled, live calls survive. */
    override fun onCleared() {
        super.onCleared()
        val call = _uiState.value.call
        val phase = _uiState.value.phase
        if (call != null && !call.status.isTerminal && !endedByMe &&
            call.status == CallStatus.Ringing
        ) {
            appScope.launch { callRepository.endCall(call.id) }
            appScope.launch { roomManager.disconnect() }
            CallForegroundService.stop(context)
        }
    }

    private companion object {
        const val TAG = "CallViewModel"
    }
}
