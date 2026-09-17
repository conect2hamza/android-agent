package com.personal.assistant.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.assistant.AppContainer
import com.personal.assistant.core.model.Memory
import com.personal.assistant.core.model.MemoryCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The memory screen is the audit surface for everything the assistant has kept about the user. Every
 * item is visible, editable and individually deletable, and the whole store can be wiped or switched
 * off -- which is what makes a persistent memory acceptable in a private app.
 */
class MemoryViewModel(private val container: AppContainer) : ViewModel() {

    val memories: StateFlow<List<Memory>> = container.memoryRepository
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(content: String, category: MemoryCategory, importance: Int) =
        viewModelScope.launch(Dispatchers.IO) {
            if (content.isBlank()) return@launch
            container.memoryRepository.remember(content, category, importance, container.now())
        }

    fun update(memory: Memory) = viewModelScope.launch(Dispatchers.IO) {
        container.memoryRepository.update(memory, container.now())
    }

    fun delete(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        container.memoryRepository.delete(id)
    }

    fun deleteAll() = viewModelScope.launch(Dispatchers.IO) {
        container.memoryRepository.deleteAll()
    }
}
