package com.minichat.app.feature.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.domain.model.Call
import com.minichat.app.domain.model.CallStatus
import com.minichat.app.core.util.AppResult
import com.minichat.app.domain.repository.AuthRepository
import com.minichat.app.domain.repository.CallRepository
import com.minichat.app.domain.repository.ProfileRepository
import com.minichat.app.notifications.NotificationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject

/**
 * Watches Realtime for calls ringing *at me* and surfaces the first one so the UI can open the
 * call screen from anywhere in the app.
 *
 * State lives on a single, app scoped view model because a ringing call must interrupt whatever
 * screen the user is on — it is not tied to the chat screen that started it.
 */
@HiltViewModel
class CallGuardViewModel @Inject constructor(
    private val callRepository: CallRepository,
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val notificationHelper: NotificationHelper,
) : ViewModel() {

    private val _incomingCall = MutableStateFlow<Call?>(null)
    val incomingCall: StateFlow<Call?> = _incomingCall.asStateFlow()

    /** Calls the user already dismissed locally, so they do not pop back up. */
    private val dismissed: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    init {
        viewModelScope.launch { callRepository.expireStaleCalls() }
        observeIncoming()
        observeDismissals()
    }

    private fun observeIncoming() {
        val userId = authRepository.currentUserId() ?: return
        viewModelScope.launch {
            callRepository.incomingCalls(userId).collect { call ->
                if (call.status != CallStatus.Ringing) return@collect
                if (dismissed.contains(call.id)) return@collect
                if (_incomingCall.value?.id == call.id) return@collect
                _incomingCall.value = call
                showNotification(call, userId)
            }
        }
    }

    private fun observeDismissals() {
        val userId = authRepository.currentUserId() ?: return
        viewModelScope.launch {
            callRepository.callUpdates(userId).collect { call ->
                val current = _incomingCall.value
                if (current != null && current.id == call.id && call.status != CallStatus.Ringing) {
                    _incomingCall.value = null
                    notificationHelper.cancelIncomingCall(call.id)
                }
            }
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

    /** Only used by the call screen once the user answered or declined. */
    fun markHandled(callId: String) {
        dismissed.add(callId)
        _incomingCall.update { current -> current?.takeUnless { it.id == callId } }
    }
}
