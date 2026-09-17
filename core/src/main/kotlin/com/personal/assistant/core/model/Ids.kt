package com.personal.assistant.core.model

/**
 * Identifiers are plain [Long]s everywhere so the same model classes can be produced by Room
 * (auto-generated row ids) and by the parser (which uses [UNSAVED] for things not yet stored).
 */
const val UNSAVED: Long = 0L
