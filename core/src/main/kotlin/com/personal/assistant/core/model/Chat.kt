package com.personal.assistant.core.model

import java.time.LocalDateTime

enum class Sender { USER, ASSISTANT, SYSTEM }

data class Conversation(
    val id: Long = UNSAVED,
    val title: String? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

data class Message(
    val id: Long = UNSAVED,
    val conversationId: Long,
    val sender: Sender,
    val content: String,
    val timestamp: LocalDateTime,
    /** Task this message created or acted on, so the chat can render a live task card. */
    val relatedTaskId: Long? = null,
    /** True when the assistant is waiting on a yes/no or a disambiguation before acting. */
    val awaitingConfirmation: Boolean = false,
)
