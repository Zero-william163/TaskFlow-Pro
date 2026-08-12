package com.taskflow.app.data.model

/** A task category with a brand color used across UI, widget, and notifications. */
data class Category(
    val id: Long = 0L,
    val name: String,
    /** ARGB int color. */
    val color: Int,
    val sortOrder: Int = 0,
    /** Whether this category was created by the user (true) or is built-in (false). */
    val isCustom: Boolean = false
) {
    companion object {
        /** Built-in categories seeded on first launch. Colors match the brand palette. */
        val DEFAULTS = listOf(
            Category(name = "工作", color = 0xFF4C6EF5.toInt(), sortOrder = 0),
            Category(name = "个人", color = 0xFF15D0AB.toInt(), sortOrder = 1),
            Category(name = "学习", color = 0xFF7950F2.toInt(), sortOrder = 2),
            Category(name = "其他", color = 0xFFFFB020.toInt(), sortOrder = 3),
        )

        /**
         * Curated Material Design / Morandi color palette for custom categories.
         * Used by the "创建自定义分类" dialog's color picker.
         */
        val COLOR_PALETTE: List<Int> = listOf(
            0xFF4FC3F7.toInt(), // 浅蓝
            0xFFF48FB1.toInt(), // 粉红
            0xFF66BB6A.toInt(), // 薄荷绿
            0xFFFFB74D.toInt(), // 暖橙
            0xFF9575CD.toInt(), // 紫罗兰
            0xFF78909C.toInt(), // 深灰
            0xFFEF5350.toInt(), // 珊瑚红
            0xFFFFCA28.toInt(), // 金黄
            0xFF26A69A.toInt(), // 青绿
            0xFF8D6E63.toInt(), // 摩卡棕
        )
    }
}
