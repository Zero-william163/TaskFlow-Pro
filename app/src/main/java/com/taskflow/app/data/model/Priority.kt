package com.taskflow.app.data.model

import androidx.compose.ui.graphics.Color

enum class Priority(val weight: Int) {
    NONE(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3);

    companion object {
        fun fromName(name: String?): Priority =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** Brand-aligned color tokens for each priority level. */
val Priority.color: Color
    get() = when (this) {
        Priority.NONE -> Color(0xFF9AA0B4)
        Priority.LOW -> Color(0xFF15D0AB)
        Priority.MEDIUM -> Color(0xFFFFB020)
        Priority.HIGH -> Color(0xFFFF6B6B)
    }

val Priority.labelRes: Int
    get() = when (this) {
        Priority.NONE -> com.taskflow.app.R.string.priority_none
        Priority.LOW -> com.taskflow.app.R.string.priority_low
        Priority.MEDIUM -> com.taskflow.app.R.string.priority_medium
        Priority.HIGH -> com.taskflow.app.R.string.priority_high
    }
