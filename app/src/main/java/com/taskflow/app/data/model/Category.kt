package com.taskflow.app.data.model

/** A task category with a brand color used across UI, widget, and notifications. */
data class Category(
    val id: Long = 0L,
    val name: String,
    /** ARGB int color. */
    val color: Int,
    val sortOrder: Int = 0
) {
    companion object {
        /** Built-in categories seeded on first launch. Colors match the brand palette. */
        val DEFAULTS = listOf(
            Category(name = "工作", color = 0xFF4C6EF5.toInt(), sortOrder = 0),
            Category(name = "个人", color = 0xFF15D0AB.toInt(), sortOrder = 1),
            Category(name = "学习", color = 0xFF7950F2.toInt(), sortOrder = 2),
            Category(name = "其他", color = 0xFFFFB020.toInt(), sortOrder = 3),
        )
    }
}
