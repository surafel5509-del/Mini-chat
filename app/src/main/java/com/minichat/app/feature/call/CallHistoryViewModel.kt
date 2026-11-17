package com.minichat.app.feature.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.domain.model.CallHistoryItem
import com.minichat.app.domain.model.GroupCallHistoryItem
import com.minichat.app.domain.repository.CallRepository
import com.minichat.app.domain.repository.GroupCallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CallHistoryViewModel @Inject constructor(
    private val callRepository: CallRepository,
    private val groupCallRepository: GroupCallRepository,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val items: List<CallHistoryItem> = emptyList(),
        val groupItems: List<GroupCallHistoryItem> = emptyList(),
        val error: String? = null,
        val redialling: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** A call started from a history row that the UI should open. */
    private val _startedCallId = MutableStateFlow<String?>(null)
    val startedCallId: StateFlow<String?> = _startedCallId.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = it.items.isEmpty()) }
            val direct = callRepository.history(limit = 60)
            val group = groupCallRepository.history(limit = 40)

            when (direct) {
                is AppResult.Success -> _uiState.update {
                    it.copy(isLoading = false, items = direct.data, error = null)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, error = direct.message)
                }
            }
            if (group is AppResult.Success) {
                _uiState.update { it.copy(groupItems = group.data) }
            }
        }
    }

    fun redial(item: CallHistoryItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(redialling = item.call.conversationId, error = null) }
            val result = callRepository.startCall(item.call.conversationId, item.call.type)
            _uiState.update { it.copy(redialling = null) }
            when (result) {
                is AppResult.Success -> _startedCallId.value = result.data.id
                is AppResult.Failure -> _uiState.update { it.copy(error = result.message) }
            }
        }
    }

    fun consumeStartedCall() {
        _startedCallId.value = null
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
