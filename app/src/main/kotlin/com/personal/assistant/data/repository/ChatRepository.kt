package com.personal.assistant.data.repository

import com.personal.assistant.core.model.Conversation
import com.personal.assistant.core.model.Message
import com.personal.assistant.core.model.Sender
import com.personal.assistant.data.dao.ChatDao
import com.personal.assistant.data.mapper.Mappers
import com.personal.assistant.data.mapper.Mappers.toEntity
import com.personal.assistant.data.mapper.Mappers.toModel
import com.personal.assistant.security.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime

class ChatRepository(
    private val dao: ChatDao,
    private val crypto: CryptoManager,
) {

    /**
     * There is one rolling conversation. A personal assistant is not a support desk; threading the
     * history into separate sessions would add a concept the user has to manage for no benefit.
     */
    suspend fun activeConversation(now: LocalDateTime): Conversation {
        dao.latestConversation()?.let { return it.toModel() }
        val conversation = Conversation(createdAt = now, updatedAt = now)
        val id = dao.insertConversation(conversation.toEntity())
        return conversation.copy(id = id)
    }

    /** Newest first, which is the order a chat list renders in when it is reversed. */
    fun observeMessages(conversationId: Long, limit: Int = 200): Flow<List<Message>> =
        dao.observeMessages(conversationId, limit).map { rows -> rows.map { it.toModel(crypto) } }

    suspend fun append(
        conversationId: Long,
        sender: Sender,
        content: String,
        at: LocalDateTime,
        relatedTaskId: Long? = null,
        awaitingConfirmation: Boolean = false,
    ): Message {
        val message = Message(
            conversationId = conversationId,
            sender = sender,
            content = content,
            timestamp = at,
            relatedTaskId = relatedTaskId,
            awaitingConfirmation = awaitingConfirmation,
        )
        val id = dao.insertMessage(message.toEntity(crypto))
        dao.touchConversation(conversationId, with(Mappers) { at.toEpochSecond() })
        return message.copy(id = id)
    }

    suspend fun clear(conversationId: Long) = dao.deleteMessages(conversationId)

    suspend fun clearAll() = dao.clearHistory()

    suspend fun allConversations(): List<Conversation> = dao.allConversations().map { it.toModel() }

    suspend fun allMessages(): List<Message> = dao.allMessages().map { it.toModel(crypto) }

    suspend fun replaceAll(conversations: List<Conversation>, messages: List<Message>) {
        dao.clearHistory()
        dao.insertConversations(conversations.map { it.toEntity() })
        dao.insertMessages(messages.map { it.toEntity(crypto) })
    }
}
