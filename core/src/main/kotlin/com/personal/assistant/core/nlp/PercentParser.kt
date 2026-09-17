package com.personal.assistant.core.nlp

/** Extracts a 0..100 progress value from phrases like "50%", "half done", "teen chauthai". */
object PercentParser {

    private val EXPLICIT = Regex("""(\d{1,3})\s*%""")
    private val PERCENT_WORD = Regex("""(\d{1,3})\s*(percent|fisad|فیصد)""")

    private val FRACTIONS: Map<String, Int> = mapOf(
        "quarter" to 25, "chauthai" to 25, "چوتھائی" to 25,
        "half" to 50, "halfway" to 50, "adha" to 50, "aadha" to 50, "آدھا" to 50, "نصف" to 50,
        "most" to 75, "zyada" to 75,
        "all" to 100, "fully" to 100, "poora" to 100, "پورا" to 100,
    )

    private val DONE_WORDS = setOf("done", "completed", "complete", "finished", "mukammal", "مکمل")
    private val NONE_WORDS = setOf("nothing", "none", "kuch nahi", "کچھ نہیں", "shuru nahi")

    fun find(normalized: String, tokens: List<Token>): Int? {
        EXPLICIT.find(normalized)?.let { return it.groupValues[1].toInt().coerceIn(0, 100) }
        PERCENT_WORD.find(normalized)?.let { return it.groupValues[1].toInt().coerceIn(0, 100) }

        if (NONE_WORDS.any { normalized.contains(it) }) return 0

        // "three quarters" before the bare "quarter" reading.
        for (i in tokens.indices) {
            val value = Lexicon.numberOf(tokens[i].text)
            val next = tokens.getOrNull(i + 1)?.text
            if (value == 3 && (next == "quarters" || next == "quarter")) return 75
        }

        for (token in tokens) {
            FRACTIONS[token.text]?.let { return it }
        }
        if (tokens.any { it.text in DONE_WORDS }) return 100
        return null
    }

    fun hasPercentSignal(normalized: String, tokens: List<Token>): Boolean {
        if (EXPLICIT.containsMatchIn(normalized) || PERCENT_WORD.containsMatchIn(normalized)) return true
        return tokens.any { FRACTIONS.containsKey(it.text) }
    }
}
