package com.taskflow.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val color: Int,
    val sortOrder: Int = 0,
    /** Whether this category was created by the user (true) or is built-in (false). */
    val isCustom: Boolean = false
)
