package com.personal.assistant.core.nlp

/** A half-open `[start, end)` range of the input that a matcher has claimed. */
data class Span(val start: Int, val end: Int) {
    init {
        require(start in 0..end) { "invalid span $start..$end" }
    }

    fun overlaps(other: Span): Boolean = start < other.end && other.start < end
}

/** Tracks which parts of the input have already been consumed by an earlier matcher. */
class SpanTracker {
    private val claimed = mutableListOf<Span>()

    val spans: List<Span> get() = claimed.toList()

    fun isFree(span: Span): Boolean = claimed.none { it.overlaps(span) }

    /** Claims [span] unless it collides with something already claimed. Returns success. */
    fun claim(span: Span): Boolean {
        if (!isFree(span)) return false
        claimed += span
        return true
    }

    fun claimAll(spans: Collection<Span>) {
        spans.forEach { claim(it) }
    }
}
