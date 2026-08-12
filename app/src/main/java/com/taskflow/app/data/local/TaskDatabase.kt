package com.taskflow.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.taskflow.app.data.model.Category

@Database(
    entities = [TaskEntity::class, CategoryEntity::class, TaskInstanceEntity::class, FocusHistoryEntity::class],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class TaskDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun categoryDao(): CategoryDao
    abstract fun taskInstanceDao(): TaskInstanceDao
    abstract fun focusHistoryDao(): FocusHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: TaskDatabase? = null

        fun get(context: Context): TaskDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TaskDatabase::class.java,
                    "taskflow.db"
                )
                    .addCallback(SeedCallback())
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
                    .also { INSTANCE = it }
            }

        /**
         * v1 → v2: introduce [TaskEntity.pinnedToWidget]. Existing rows default to 1
         * (visible on widget) so any pending tasks created before the upgrade keep
         * appearing on already-placed widgets.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN pinnedToWidget INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tasks_pinnedToWidget ON tasks(pinnedToWidget)"
                )
            }
        }

        /**
         * v2 → v3: add frequency fields + reminder mode + startDate + TaskInstance
         * table. All new task columns default to "NONE / ONCE / NULL" for backwards
         * compatibility.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN startDate INTEGER"
                )
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN reminderMode TEXT NOT NULL DEFAULT 'ONCE'"
                )
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN frequency TEXT NOT NULL DEFAULT 'NONE'"
                )
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN weeklyWeekdays INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN monthlyDays INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN customDatesRaw TEXT"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_startDate ON tasks(startDate)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_frequency ON tasks(frequency)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS task_instances (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskId INTEGER NOT NULL,
                        occurrenceDate INTEGER NOT NULL,
                        isCompleted INTEGER NOT NULL DEFAULT 0,
                        completedAt INTEGER,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_task_instances_taskId ON task_instances(taskId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_task_instances_occurrenceDate ON task_instances(occurrenceDate)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_task_instances_isCompleted ON task_instances(isCompleted)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_task_instances_taskId_occurrenceDate ON task_instances(taskId, occurrenceDate)"
                )
            }
        }

        /**
         * v3 → v4: add alarmSoundUri to enable per-task custom ringtone URI.
         * NULL defaults are important — "null string" = fall back to system default
         * reminder sound stored on NotificationChannel (Android O+).
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN alarmSoundUri TEXT"
                )
            }
        }

        /**
         * v4 → v5: add recurring-task completion fields.
         * - lastCompletedDate: "yyyy-MM-dd" of last check-off for recurring tasks
         * - nextDueDate: pre-computed next due timestamp (epoch millis)
         *
         * Both default to NULL — existing tasks are unaffected. Non-recurring
         * tasks simply never use these columns.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN lastCompletedDate TEXT"
                )
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN nextDueDate INTEGER"
                )
            }
        }

        /**
         * v5 → v6: add isCustom column to categories table so user-created
         * categories can be distinguished from built-in ones (used by the
         * custom-category feature for long-press delete, etc.).
         * Defaults to 0 (false) — all existing categories are built-in.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE categories ADD COLUMN isCustom INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v6 → v7:
         * - tasks: add [TaskEntity.hasDueDate] (Boolean) and
         *   [TaskEntity.focusDurationMinutes] (Int). Both default to 0 so
         *   existing tasks keep their current behavior (hasDueDate=false means
         *   "no explicit deadline"; focusDurationMinutes=0 falls back to the
         *   25-min app default on the Pomodoro screen).
         * - New `focus_history` table recording completed Pomodoro sessions,
         *   with a CASCADE foreign key to tasks and indexes on taskId /
         *   timestamp for the Stats screen's trend queries.
         *
         * CREATE TABLE column types/nullability/defaults mirror the
         * [FocusHistoryEntity] definition so Room's runtime schema validation
         * passes (see MIGRATION_2_3 for the same pattern).
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN hasDueDate INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN focusDurationMinutes INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS focus_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskId INTEGER NOT NULL,
                        durationMinutes INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_focus_history_taskId ON focus_history(taskId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_focus_history_timestamp ON focus_history(timestamp)"
                )
            }
        }

        /**
         * v7 → v8: add [TaskEntity.pauseLimitMinutes] (Int). Defaults to 0 which
         * the Pomodoro screen interprets as the app default (2 min). Existing
         * tasks keep their current focus-flow behavior.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN pauseLimitMinutes INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private class SeedCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // Seed default categories synchronously (onCreate runs in a transaction).
                // isCustom=0 for all built-in categories.
                val rows = Category.DEFAULTS.joinToString(",") { c ->
                    "(${c.sortOrder}, '${c.name.replace("'", "''")}', ${c.color}, 0)"
                }
                db.execSQL(
                    "INSERT INTO categories (sortOrder, name, color, isCustom) VALUES $rows"
                )
            }
        }
    }
}
