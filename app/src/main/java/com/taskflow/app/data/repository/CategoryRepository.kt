package com.taskflow.app.data.repository

import android.content.Context
import com.taskflow.app.data.local.CategoryDao
import com.taskflow.app.data.local.TaskDatabase
import com.taskflow.app.data.model.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CategoryRepository private constructor(
    private val categoryDao: CategoryDao
) {
    fun observeAll(): Flow<List<Category>> =
        categoryDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getAll(): List<Category> = categoryDao.getAll().map { it.toDomain() }

    suspend fun getById(id: Long): Category? = categoryDao.getById(id)?.toDomain()

    suspend fun add(category: Category): Long = categoryDao.insert(category.toEntity())

    suspend fun update(category: Category) = categoryDao.update(category.toEntity())

    suspend fun delete(id: Long) = categoryDao.deleteById(id)

    companion object {
        @Volatile
        private var INSTANCE: CategoryRepository? = null

        fun get(context: Context): CategoryRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CategoryRepository(
                    TaskDatabase.get(context).categoryDao()
                ).also { INSTANCE = it }
            }
    }
}
