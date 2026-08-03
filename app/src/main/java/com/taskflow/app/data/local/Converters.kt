package com.taskflow.app.data.local

import androidx.room.TypeConverter
import com.taskflow.app.data.model.Priority
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Room type converters.
 *
 * Timestamps are stored as epoch milliseconds (UTC) so they survive locale/timezone
 * changes and remain sortable in SQL. Enums are stored by name for readability.
 */
class Converters {

    @TypeConverter
    fun fromLocalDateTime(value: LocalDateTime?): Long? =
        value?.atOffset(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()

    @TypeConverter
    fun toLocalDateTime(value: Long?): LocalDateTime? =
        value?.let {
            LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(it),
                ZoneOffset.UTC
            )
        }

    @TypeConverter
    fun fromPriority(priority: Priority): String = priority.name

    @TypeConverter
    fun toPriority(value: String): Priority = Priority.fromName(value)
}
