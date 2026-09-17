package com.personal.assistant.core.model

import java.time.LocalDateTime

enum class MemoryCategory {
    ROUTINE,
    SCHEDULE,
    PROJECT,
    GOAL,
    PREFERENCE,
    PERSON,
    NOTE;

    companion object {
        fun fromNameOrNull(value: String?): MemoryCategory? =
            value?.trim()?.uppercase()?.let { name -> entries.firstOrNull { it.name == name } }
    }
}

/**
 * A fact the user asked the assistant to remember. Never written without an explicit signal from
 * the user ("remember that ...", "I usually ...") -- the specification forbids silently inventing
 * personal memories.
 */
data class Memory(
    val id: Long = UNSAVED,
    val content: String,
    val category: MemoryCategory = MemoryCategory.NOTE,
    /** 1 (incidental) .. 5 (defining). Used to decide what gets injected into a prompt. */
    val importance: Int = 3,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    init {
        require(importance in 1..5) { "importance must be 1..5 but was $importance" }
    }
}
