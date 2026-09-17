package com.personal.assistant.core.model

enum class Priority(val weight: Int) {
    LOW(0),
    NORMAL(1),
    HIGH(2),
    URGENT(3);

    companion object {
        fun fromNameOrNull(value: String?): Priority? =
            value?.trim()?.uppercase()?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
