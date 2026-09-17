package com.personal.assistant.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.assistant.AppContainer
import com.personal.assistant.core.model.Message
import com.personal.assistant.core.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val sending: Boolean = false,
    val lastTaskId: Long? = null,
    val candidates: List<Task> = emptyList(),
    val error: String? = null,
)

class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> =
        flow { emit(container.chatRepository.activeConversation(container.now()).id) }
            .flatMapLatest { conversationId -> container.chatRepository.observeMessages(conversationId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return
        _state.value = _state.value.copy(sending = true, error = null, candidates = emptyList())

        viewModelScope.launch(Dispatchers.IO) {
            val turn = container.assistantService.send(trimmed)
            _state.value = ChatUiState(
                sending = false,
                lastTaskId = turn.taskId,
                candidates = turn.candidates,
            )
        }
    }

    /** Tapping a disambiguation candidate answers the question with that exact task. */
    fun chooseCandidate(task: Task) {
        send(task.title)
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val conversation = container.chatRepository.activeConversation(container.now())
            container.chatRepository.clear(conversation.id)
        }
    }
}
