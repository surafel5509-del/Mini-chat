package com.minichat.app.feature.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.domain.model.Call
import com.minichat.app.domain.model.CallStatus
import com.minichat.app.domain.model.GroupCall
import com.minichat.app.domain.model.GroupCallParticipant
import com.minichat.app.domain.repository.AuthRepository
import com.minichat.app.domain.repository.AuthState
import com.minichat.app.domain.repository.CallRepository
import com.minichat.app.domain.repository.GroupCallRepository
import com.minichat.app.domain.repository.ProfileRepository
import com.minichat.app.notifications.NotificationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject

/**
 * Watches Realtime for calls ringing *at me* and surfaces the first one so the UI can open the
 * call screen from anywhere in the app.
 *
 * State lives on a single, app scoped view model because a ringing call must interrupt whatever
 * screen the user is on — it is not tied to the chat screen that started it. The watch restarts
 * whenever the signed-in user changes (sign in, sign out, account switch).
 */
@HiltViewModel
class CallGuardViewModel @Inject constructor(
    private val callRepository: CallRepository,
    private val groupCallRepository: GroupCallRepository,
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val notificationHelper: NotificationHelper,
) : ViewModel() {

    private val _incomingCall = MutableStateFlow<Call?>(null)
    val incomingCall: StateFlow<Call?> = _incomingCall.asStateFlow()

    private val _incomingGroupCall = MutableStateFlow<GroupCall?>(null)
    val incomingGroupCall: StateFlow<GroupCall?> = _incomingGroupCall.asStateFlow()

    /** Calls the user already dismissed locally, so they do not pop back up. */
    private val dismissed: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Group calls this user already declined or joined from this device. */
    private val dismissedGroups: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    init {
        viewModelScope.launch {
            authRepository.authState.collectLatest { state ->
                if (state is AuthState.SignedIn) {
                    watch(state.userId)
                } else {
                    _incomingCall.value = null
                    _incomingGroupCall.value = null
                }
            }
        }
    }

    private suspend fun watch(userId: String) {
        callRepository.expireStaleCalls()

        // Scoped to this call: signing out (or switching account) cancels both collectors.
        coroutineScope {
            launch { observeIncoming(userId) }
            launch { observeDismissals(userId) }
            launch { observeGroupIncoming(userId) }
            launch { observeGroupDismissals(userId) }
        }
    }

    private suspend fun observeIncoming(userId: String) {
        callRepository.incomingCalls(userId).collect { call ->
            if (call.status != CallStatus.Ringing) return@collect
            if (dismissed.contains(call.id)) return@collect
            if (_incomingCall.value?.id == call.id) return@collect
            _incomingCall.value = call
            showNotification(call, userId)
        }
    }

    private suspend fun observeDismissals(userId: String) {
        callRepository.callUpdates(userId).collect { call ->
            val current = _incomingCall.value
            if (current != null && current.id == call.id && call.status != CallStatus.Ringing) {
                _incomingCall.value = null
                notificationHelper.cancelIncomingCall(call.id)
            }
        }
    }

    private suspend fun observeGroupIncoming(userId: String) {
        groupCallRepository.incomingCalls(userId).collect { call ->
            if (!call.status.isLive) return@collect
            if (dismissedGroups.contains(call.id)) return@collect
            if (_incomingGroupCall.value?.id == call.id) return@collect
            // Only ring if I am still an invitee: joining (or declining) clears this.
            val roster = groupCallRepository.participants(call.id)
            val mine = (roster as? AppResult.Success)?.data?.firstOrNull { it.userId == userId }
            if (mine == null) return@collect
            if (mine.status != GroupCallParticipant.ParticipationStatus.Invited) return@collect

            _incomingGroupCall.value = call
            showGroupNotification(call, mine, userId)
        }
    }

    private suspend fun observeGroupDismissals(userId: String) {
        groupCallRepository.callUpdates(userId).collect { call ->
            val current = _incomingGroupCall.value
            if (current != null && current.id == call.id && !call.status.isLive) {
                _incomingGroupCall.value = null
                notificationHelper.cancelIncomingGroupCall(call.id)
            }
        }
    }

    private fun showGroupNotification(
        call: GroupCall,
        me: GroupCallParticipant,
        userId: String,
    ) {
        viewModelScope.launch {
            val hostId = call.hostId
            val hostName = if (hostId == userId) {
                "you"
            } else {
                when (val profile = profileRepository.profile(hostId)) {
                    is AppResult.Success -> profile.data.displayName
                    is AppResult.Failure -> ""
                }
            }
            notificationHelper.notifyIncomingGroupCall(
                callId = call.id,
                title = call.title,
                hostName = hostName,
                isVideo = call.type.isVideo,
                participantCount = 1,
            )
        }
    }

    private fun showNotification(call: Call, userId: String) {
        viewModelScope.launch {
            val name = when (val profile = profileRepository.profile(call.peerId(userId))) {
                is AppResult.Success -> profile.data.displayName
                is AppResult.Failure -> ""
            }
            notificationHelper.notifyIncomingCall(
                callId = call.id,
                callerName = name.ifBlank { "MINI CHAT" },
                isVideo = call.type.isVideo,
            )
        }
    }

    fun consume() {
        _incomingCall.value?.let { dismissed.add(it.id) }
        _incomingCall.value = null
    }

    fun consumeGroupCall() {
        _incomingGroupCall.value?.let { dismissedGroups.add(it.id) }
        _incomingGroupCall.value = null
    }

    fun markGroupHandled(callId: String) {
        dismissedGroups.add(callId)
        _incomingGroupCall.value = null
    }

    /** Only used by the call screen once the user answered or declined. */
    fun markHandled(callId: String) {
        dismissed.add(callId)
        _incomingCall.update { current -> current?.takeUnless { it.id == callId } }
    }
}
