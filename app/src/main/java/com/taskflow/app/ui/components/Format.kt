package com.taskflow.app.ui.components

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object Format {

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val monthDayFmt = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val fullDateFmt = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
    private val shortDateFmt = DateTimeFormatter.ofPattern("MM-dd")

    fun time(dt: LocalDateTime): String = dt.format(timeFmt)

    fun date(dt: LocalDateTime): String = dt.format(monthDayFmt)

    fun fullDate(dt: LocalDateTime): String = dt.format(fullDateFmt)

    fun describeDueDate(due: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): String {
        val today = now.toLocalDate()
        val dateOnly = due.hour == 0 && due.minute == 0 && due.second == 0 && due.nano == 0
        return when {
            due.toLocalDate().isEqual(today) ->
                if (dateOnly) "今天" else "今天 " + due.format(timeFmt)
            due.toLocalDate().isEqual(today.plusDays(1)) ->
                if (dateOnly) "明天" else "明天 " + due.format(timeFmt)
            due.toLocalDate().isEqual(today.minusDays(1)) ->
                if (dateOnly) "昨天" else "昨天 " + due.format(timeFmt)
            due.year == now.year ->
                if (dateOnly) due.format(monthDayFmt)
                else due.format(monthDayFmt) + " " + due.format(timeFmt)
            else ->
                if (dateOnly) due.format(fullDateFmt)
                else due.format(fullDateFmt) + " " + due.format(timeFmt)
        }
    }

    fun describeDueShort(due: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): String {
        val today = now.toLocalDate()
        val dateOnly = due.hour == 0 && due.minute == 0 && due.second == 0 && due.nano == 0
        return when {
            due.toLocalDate().isEqual(today) -> if (dateOnly) "今天" else due.format(timeFmt)
            due.toLocalDate().isEqual(today.plusDays(1)) -> "明天"
            due.toLocalDate().isEqual(today.minusDays(1)) -> "昨天"
            due.year == now.year -> due.format(monthDayFmt)
            else -> due.format(shortDateFmt)
        }
    }

    fun weekdayLabel(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)

    fun greetingPrefix(hour: Int): String = when (hour) {
        in 5..11 -> "早上好"
        in 12..17 -> "下午好"
        else -> "晚上好"
    }
}
