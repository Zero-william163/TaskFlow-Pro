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
    entities = [TaskEntity::class, CategoryEntity::class, TaskInstanceEntity::class],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class TaskDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun categoryDao(): CategoryDao
    abstract fun taskInstanceDao(): TaskInstanceDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
         * compatibility. Task instances are seeded on first insert (see
         * TaskRepository). The seed keeps already-created tasks usable without
         * requiring user intervention.
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

        private class SeedCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // Seed default categories synchronously (onCreate runs in a transaction).
                val rows = Category.DEFAULTS.joinToString(",") { c ->
                    "(${c.sortOrder}, '${c.name.replace("'", "''")}', ${c.color})"
                }
                db.execSQL(
                    "INSERT INTO categories (sortOrder, name, color) VALUES $rows"
                )
            }
        }
    }
}
