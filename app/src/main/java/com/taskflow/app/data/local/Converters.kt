package com.taskflow.app.data.local

import androidx.room.TypeConverter
import com.taskflow.app.data.model.FrequencyType
import com.taskflow.app.data.model.Priority
import com.taskflow.app.data.model.ReminderMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Room type converters.
 *
 * LocalDateTime is stored as epoch-millis relative to the device's SYSTEM DEFAULT
 * timezone (not UTC). This guarantees:
 *  - The calendar / widget / home screen all agree on "which day" a task belongs to
 *  - The AlarmScheduler can call LocalDateTime.atZone(ZoneId.systemDefault()) without
 *    introducing an offset shift
 *  - Round-trip storage remains lossless: store(yield(x)) == x for any LocalDateTime
 *
 * LocalDate is stored as epoch-day (zoneless) — always consistent.
 * Enums are stored by name for readability.
 */
class Converters {

    @TypeConverter
    fun fromLocalDateTime(value: LocalDateTime?): Long? =
        value?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

    @TypeConverter
    fun toLocalDateTime(value: Long?): LocalDateTime? =
        value?.let {
            LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(it),
                ZoneId.systemDefault()
            )
        }

    @TypeConverter
    fun fromLocalDate(value: LocalDate?): Long? =
        value?.toEpochDay()

    @TypeConverter
    fun toLocalDate(value: Long?): LocalDate? =
        value?.let { LocalDate.ofEpochDay(it) }

    @TypeConverter
    fun fromPriority(priority: Priority): String = priority.name

    @TypeConverter
    fun toPriority(value: String): Priority = Priority.fromName(value)

    @TypeConverter
    fun fromReminderMode(value: ReminderMode): String = value.name

    @TypeConverter
    fun toReminderMode(value: String): ReminderMode = ReminderMode.fromName(value)

    @TypeConverter
    fun fromFrequencyType(value: FrequencyType): String = value.name

    @TypeConverter
    fun toFrequencyType(value: String): FrequencyType =
        FrequencyType.entries.firstOrNull { it.name == value } ?: FrequencyType.DAILY
}
