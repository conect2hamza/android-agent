package com.personal.assistant.core.domain

import com.personal.assistant.core.ai.AssistantQuery
import com.personal.assistant.core.ai.QueryKind
import com.personal.assistant.core.model.Category
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import com.personal.assistant.core.nlp.TextNormalizer
import java.time.LocalDateTime

data class SearchHit(val task: Task, val score: Int)

/**
 * Local search over tasks. Runs entirely in memory over the rows the repository already loaded --
 * there is no index to keep in sync and nothing leaves the device.
 */
class SearchEngine(private val categories: List<Category> = emptyList()) {

    fun run(query: AssistantQuery, tasks: List<Task>, now: LocalDateTime): List<SearchHit> {
        val categoryNames = categories.associateBy({ it.id }, { it.name.lowercase() })
        val keywords = query.keywords.map { TextNormalizer.normalize(it) }.filter { it.isNotBlank() }

        return tasks
            .asSequence()
            .filter { it.date in query.range }
            .filter { matchesKind(it, query.kind, now) }
            .mapNotNull { task ->
                val score = score(task, keywords, categoryNames[task.categoryId])
                if (keywords.isNotEmpty() && score == 0) null else SearchHit(task, score)
            }
            .sortedWith(
                compareByDescending<SearchHit> { it.score }
                    .thenBy { it.task.date }
                    .thenBy { it.task.startTime ?: java.time.LocalTime.MAX },
            )
            .toList()
    }

    private fun matchesKind(task: Task, kind: QueryKind, now: LocalDateTime): Boolean {
        val derived = TaskStateMachine.deriveForClock(task, now)
        return when (kind) {
            QueryKind.COMPLETED -> derived == TaskStatus.COMPLETED || derived == TaskStatus.PARTIALLY_COMPLETED
            QueryKind.MISSED -> derived == TaskStatus.MISSED
            QueryKind.UPCOMING -> !derived.isTerminal
            QueryKind.PLAN, QueryKind.SEARCH, QueryKind.TIME_SPENT,
            QueryKind.DAILY_REPORT, QueryKind.WEEKLY_REPORT, QueryKind.MONTHLY_REPORT,
            -> true
        }
    }

    /** Title matches outrank note matches, which outrank a category-name match. */
    private fun score(task: Task, keywords: List<String>, categoryName: String?): Int {
        if (keywords.isEmpty()) return 1
        val title = TextNormalizer.normalize(task.title)
        val description = TextNormalizer.normalize(task.description ?: "")
        val notes = TextNormalizer.normalize(task.notes ?: "")
        var score = 0
        for (keyword in keywords) {
            if (title == keyword) score += 10
            else if (title.contains(keyword)) score += 6
            if (description.contains(keyword)) score += 3
            if (notes.contains(keyword)) score += 2
            if (categoryName != null && categoryName.contains(keyword)) score += 2
        }
        return score
    }

    /** Resolves a spoken task reference ("my website task") to candidates, best first. */
    fun resolveReference(
        titleQuery: String?,
        date: java.time.LocalDate?,
        time: java.time.LocalTime?,
        tasks: List<Task>,
    ): List<Task> {
        val needle = titleQuery?.let { TextNormalizer.normalize(it) }?.takeIf { it.isNotBlank() }
        return tasks
            .asSequence()
            .filter { date == null || it.date == date }
            .filter { time == null || it.startTime == time }
            .mapNotNull { task ->
                if (needle == null) return@mapNotNull task to 1
                val title = TextNormalizer.normalize(task.title)
                val score = when {
                    title == needle -> 10
                    title.contains(needle) -> 6
                    needle.split(' ').any { it.length > 2 && title.contains(it) } -> 3
                    else -> 0
                }
                if (score == 0) null else task to score
            }
            .sortedWith(compareByDescending<Pair<Task, Int>> { it.second }.thenByDescending { it.first.date })
            .map { it.first }
            .toList()
    }
}
