package com.personal.assistant.core.nlp

/**
 * Lower-cases the input and folds Eastern Arabic / Urdu digits to ASCII **without changing the
 * string length**.
 *
 * Length preservation is the whole point: every matcher in this package reports the character
 * range it consumed, and [TaskTitleBuilder] removes those exact ranges from the *original* text to
 * build a task title with the user's own capitalisation intact. Any normalisation that inserted or
 * dropped characters would misalign those ranges.
 */
object TextNormalizer {

    private const val ARABIC_INDIC_ZERO = '٠'
    private const val EXTENDED_ARABIC_INDIC_ZERO = '۰'

    fun normalize(input: String): String {
        val out = StringBuilder(input.length)
        for (ch in input) {
            out.append(foldChar(ch))
        }
        check(out.length == input.length) { "normalisation changed the string length" }
        return out.toString()
    }

    private fun foldChar(ch: Char): Char = when (ch) {
        in ARABIC_INDIC_ZERO..(ARABIC_INDIC_ZERO + 9) -> '0' + (ch - ARABIC_INDIC_ZERO)
        in EXTENDED_ARABIC_INDIC_ZERO..(EXTENDED_ARABIC_INDIC_ZERO + 9) ->
            '0' + (ch - EXTENDED_ARABIC_INDIC_ZERO)
        // Narrow/no-break spaces and the Arabic comma behave as separators.
        ' ', '‏', '‎', ' ' -> ' '
        '،' -> ','
        '۔' -> '.'
        else -> ch.lowercaseChar()
    }

    /** True when the text contains Urdu/Arabic script, used only to pick the reply language. */
    fun containsUrduScript(input: String): Boolean =
        input.any { it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F }
}
