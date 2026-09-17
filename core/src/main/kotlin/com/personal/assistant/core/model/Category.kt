package com.personal.assistant.core.model

data class Category(
    val id: Long = UNSAVED,
    val name: String,
    val colorArgb: Int,
    val builtIn: Boolean = false,
) {
    companion object {
        /**
         * Seeded on first launch. Colours are plain ARGB ints so this file stays free of any
         * Android or Compose type.
         */
        val DEFAULTS: List<Category> = listOf(
            Category(name = "Development", colorArgb = 0xFF3B82F6.toInt(), builtIn = true),
            Category(name = "Design", colorArgb = 0xFFA855F7.toInt(), builtIn = true),
            Category(name = "Study", colorArgb = 0xFF22C55E.toInt(), builtIn = true),
            Category(name = "Work", colorArgb = 0xFFF59E0B.toInt(), builtIn = true),
            Category(name = "Personal", colorArgb = 0xFFEC4899.toInt(), builtIn = true),
            Category(name = "Health", colorArgb = 0xFF14B8A6.toInt(), builtIn = true),
            Category(name = "Other", colorArgb = 0xFF64748B.toInt(), builtIn = true),
        )
    }
}
