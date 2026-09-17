package com.personal.assistant.core.nlp

/** A word of the input together with its position in the (length-preserving) normalized string. */
data class Token(val text: String, val start: Int, val end: Int) {
    val span: Span get() = Span(start, end)
    val asInt: Int? get() = text.toIntOrNull()
}

object Tokenizer {

    /** Characters trimmed from the edges of a token but kept inside it (so "4:30" survives). */
    private const val EDGE_PUNCTUATION = ".,!?;:()[]{}\"'؟،۔"

    fun tokenize(normalized: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var index = 0
        while (index < normalized.length) {
            if (normalized[index].isWhitespace()) {
                index++
                continue
            }
            val start = index
            while (index < normalized.length && !normalized[index].isWhitespace()) index++
            var from = start
            var to = index
            while (from < to && normalized[from] in EDGE_PUNCTUATION) from++
            while (to > from && normalized[to - 1] in EDGE_PUNCTUATION) to--
            if (to > from) tokens += Token(normalized.substring(from, to), from, to)
        }
        return tokens
    }

    /**
     * Joins [count] tokens starting at [index] into a single space-separated phrase, or null if the
     * window runs past the end. Used to match multi-word lexicon entries such as "day after
     * tomorrow" without a second pass over the raw string.
     */
    fun phrase(tokens: List<Token>, index: Int, count: Int): Pair<String, Span>? {
        if (index < 0 || index + count > tokens.size || count <= 0) return null
        val text = (index until index + count).joinToString(" ") { tokens[it].text }
        return text to Span(tokens[index].start, tokens[index + count - 1].end)
    }
}
