package com.personal.assistant.data.repository

import com.personal.assistant.core.model.Memory
import com.personal.assistant.core.model.MemoryCategory
import com.personal.assistant.data.dao.MemoryDao
import com.personal.assistant.data.mapper.Mappers.toEntity
import com.personal.assistant.data.mapper.Mappers.toModel
import com.personal.assistant.security.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime

/**
 * The assistant's long-term memory, under the user's control.
 *
 * Two rules are enforced here rather than left to callers. A memory is only ever written in response to
 * an explicit signal from the user, and a near-duplicate updates the existing row instead of adding
 * another -- an assistant that quietly accumulates seventeen variations of the same fact is one the user
 * cannot audit, which defeats the point of showing them the list.
 */
class MemoryRepository(
    private val dao: MemoryDao,
    private val crypto: CryptoManager,
) {

    fun observeAll(): Flow<List<Memory>> =
        dao.observeAll().map { rows -> rows.map { it.toModel(crypto) } }

    suspend fun all(): List<Memory> = dao.all().map { it.toModel(crypto) }

    /** The handful of memories worth spending prompt space on. */
    suspend fun forPrompt(limit: Int = 8): List<String> =
        dao.top(limit).map { it.toModel(crypto).content }

    suspend fun remember(
        content: String,
        category: MemoryCategory,
        importance: Int,
        now: LocalDateTime,
    ): Memory {
        val trimmed = content.trim().take(MAX_LENGTH)
        val existing = all().firstOrNull { it.isNearDuplicateOf(trimmed) }
        val memory = existing?.copy(
            content = trimmed,
            category = category,
            importance = maxOf(existing.importance, importance),
            updatedAt = now,
        ) ?: Memory(
            content = trimmed,
            category = category,
            importance = importance.coerceIn(1, 5),
            createdAt = now,
            updatedAt = now,
        )
        val id = dao.upsert(memory.toEntity(crypto))
        return memory.copy(id = if (memory.id == 0L) id else memory.id)
    }

    suspend fun update(memory: Memory, now: LocalDateTime) {
        dao.upsert(memory.copy(updatedAt = now).toEntity(crypto))
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun replaceAll(memories: List<Memory>) {
        dao.deleteAll()
        dao.insertAll(memories.map { it.toEntity(crypto) })
    }

    /**
     * Cheap similarity: identical ignoring case and punctuation, or a strong word overlap. Deliberately
     * not fuzzy -- the cost of a false match is silently rewriting something the user wrote.
     */
    private fun Memory.isNearDuplicateOf(candidate: String): Boolean {
        val a = content.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()
        val b = candidate.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()
        if (a == b) return true
        val wordsA = a.split(' ').filter { it.length > 3 }.toSet()
        val wordsB = b.split(' ').filter { it.length > 3 }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        val shared = wordsA.intersect(wordsB).size.toDouble()
        return shared / maxOf(wordsA.size, wordsB.size) >= DUPLICATE_THRESHOLD
    }

    companion object {
        private const val MAX_LENGTH = 1000
        private const val DUPLICATE_THRESHOLD = 0.8
    }
}
