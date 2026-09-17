package com.personal.assistant.core.nlp

/**
 * Builds a task title out of what is left of the user's sentence once dates, times, durations and
 * repeat rules have been claimed.
 *
 * It works on the **original** text, not the normalized copy, so "finish the WordPress project"
 * keeps its capitalisation. This is why [TextNormalizer] is length-preserving.
 */
object TaskTitleBuilder {

    private const val MAX_TITLE_LENGTH = 120

    fun build(original: String, consumed: List<Span>): String {
        val kept = StringBuilder(original.length)
        val sorted = consumed.sortedBy { it.start }
        var cursor = 0
        for (span in sorted) {
            if (span.start > cursor) kept.append(original, cursor, span.start.coerceAtMost(original.length))
            // Replace the removed range with a space so neighbouring words do not fuse.
            kept.append(' ')
            cursor = maxOf(cursor, span.end)
        }
        if (cursor < original.length) kept.append(original, cursor, original.length)

        var text = collapse(kept.toString())
        text = stripFiller(text)
        text = collapse(text).trim(' ', ',', '.', '-', ':', ';', '،', '۔')
        if (text.isEmpty()) return ""
        if (text.length > MAX_TITLE_LENGTH) text = text.take(MAX_TITLE_LENGTH).trimEnd()
        return text.replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }
    }

    private fun collapse(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

    /** Drops prepositions stranded at either edge by a removed date or time. */
    private fun stripOrphanEdges(text: String): String {
        var words = text.split(' ').filter { it.isNotBlank() }.toMutableList()
        fun edge(word: String) = word.trim(',', '.', ':', ';', '-', '\u06D4').lowercase() in Lexicon.ORPHAN_EDGE_WORDS
        while (words.isNotEmpty() && edge(words.first())) words.removeAt(0)
        while (words.isNotEmpty() && edge(words.last())) words.removeAt(words.size - 1)
        return words.joinToString(" ")
    }

    private fun stripFiller(text: String): String {
        var current = text
        // Leading filler can stack: "please remind me to" + "call Ahmed".
        var changed = true
        while (changed) {
            changed = false
            val lower = current.lowercase()
            for (phrase in Lexicon.LEADING_FILLER.sortedByDescending { it.length }) {
                if (lower.startsWith("$phrase ") || lower == phrase) {
                    current = current.drop(phrase.length).trimStart()
                    changed = true
                    break
                }
            }
        }
        changed = true
        while (changed) {
            changed = false
            val lower = current.lowercase().trimEnd(' ', '.', ',', '۔')
            for (phrase in Lexicon.TRAILING_FILLER.sortedByDescending { it.length }) {
                if (lower.endsWith(" $phrase") || lower == phrase) {
                    val cut = lower.length - phrase.length
                    current = current.take(cut).trimEnd(' ', '.', ',', '۔')
                    changed = true
                    break
                }
            }
        }
        return current
    }
}
