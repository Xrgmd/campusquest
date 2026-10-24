package com.campusquest.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.campusquest.app.logic.DEFAULT_SEMESTER_START
import com.campusquest.app.logic.DEFAULT_TOTAL_WEEKS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore("cq_prefs")

object Prefs {
    val KEY_DARK = booleanPreferencesKey("dark")
    val KEY_START = stringPreferencesKey("semester_start")
    val KEY_WEEKS = intPreferencesKey("total_weeks")
    val KEY_CITY = stringPreferencesKey("city")
    val KEY_SEEDED = booleanPreferencesKey("seeded")
    val KEY_COLLAPSED = stringPreferencesKey("collapsed")
    val KEY_DONEOPEN = stringPreferencesKey("done_open")
    /** 课程组手动排序(课程名,\n 分隔) */
    val KEY_COURSE_ORDER = stringPreferencesKey("course_order")
    /** 支线任务手动排序(任务 id,逗号分隔;仅未置顶项参与排序) */
    val KEY_RAND_ORDER = stringPreferencesKey("rand_order")

    fun dark(ctx: Context): Flow<Boolean?> = ctx.dataStore.data.map { it[KEY_DARK] }
    fun start(ctx: Context): Flow<String> = ctx.dataStore.data.map { it[KEY_START] ?: DEFAULT_SEMESTER_START }
    fun weeks(ctx: Context): Flow<Int> = ctx.dataStore.data.map { it[KEY_WEEKS] ?: DEFAULT_TOTAL_WEEKS }
    fun city(ctx: Context): Flow<String> = ctx.dataStore.data.map { it[KEY_CITY] ?: "" }
    fun seeded(ctx: Context): Flow<Boolean> = ctx.dataStore.data.map { it[KEY_SEEDED] ?: false }

    suspend fun setDark(ctx: Context, v: Boolean) { ctx.dataStore.edit { it[KEY_DARK] = v } }
    suspend fun clearDark(ctx: Context) { ctx.dataStore.edit { it.remove(KEY_DARK) } }
    suspend fun setStart(ctx: Context, v: String) { ctx.dataStore.edit { it[KEY_START] = v } }
    suspend fun setWeeks(ctx: Context, v: Int) { ctx.dataStore.edit { it[KEY_WEEKS] = v } }
    suspend fun setCity(ctx: Context, v: String) { ctx.dataStore.edit { it[KEY_CITY] = v } }
    suspend fun setSeeded(ctx: Context) { ctx.dataStore.edit { it[KEY_SEEDED] = true } }
    fun collapsed(ctx: Context): Flow<Set<String>> = ctx.dataStore.data.map { it[KEY_COLLAPSED]?.split("|")?.filter { s -> s.isNotEmpty() }?.toSet() ?: emptySet() }
    fun doneOpen(ctx: Context): Flow<Set<String>> = ctx.dataStore.data.map { it[KEY_DONEOPEN]?.split("|")?.filter { s -> s.isNotEmpty() }?.toSet() ?: emptySet() }
    suspend fun setCollapsed(ctx: Context, v: Set<String>) { ctx.dataStore.edit { it[KEY_COLLAPSED] = v.joinToString("|") } }
    suspend fun setDoneOpen(ctx: Context, v: Set<String>) { ctx.dataStore.edit { it[KEY_DONEOPEN] = v.joinToString("|") } }

    fun courseOrder(ctx: Context): Flow<List<String>> = ctx.dataStore.data.map {
        it[KEY_COURSE_ORDER]?.split('\n')?.filter { s -> s.isNotBlank() } ?: emptyList()
    }
    fun randOrder(ctx: Context): Flow<List<Long>> = ctx.dataStore.data.map {
        it[KEY_RAND_ORDER]?.split(',')?.mapNotNull { s -> s.trim().toLongOrNull() } ?: emptyList()
    }
    suspend fun setCourseOrder(ctx: Context, v: List<String>) { ctx.dataStore.edit { it[KEY_COURSE_ORDER] = v.joinToString("\n") } }
    suspend fun setRandOrder(ctx: Context, v: List<Long>) { ctx.dataStore.edit { it[KEY_RAND_ORDER] = v.joinToString(",") } }
}
