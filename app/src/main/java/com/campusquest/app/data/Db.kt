package com.campusquest.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.campusquest.app.logic.parseWeeksStr
import com.campusquest.app.logic.weeksToText
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** 稳定身份 + 修改时间:0.3.4 起给每条记录都带上。
 *  - uuid:跨设备/跨重装唯一,将来做合并同步靠它认「这是同一条」;
 *  - updatedAt:最后修改时刻,冲突时后写胜出;
 *  - 目前只用于「导出/恢复」,还没做同步,但少了这两个字段同步一定合坏数据。 */
private fun newUuid(): String = UUID.randomUUID().toString()

@Entity(tableName = "courses")
data class CourseEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val short: String,
    val tea: String,
    val day: Int,          // 1..7 周一..周日
    val band: Int,         // 0..5 BANDS 下标
    val weeksStr: String,  // weeksToText 格式
    val room: String,
    val colorIdx: Int,
    val uuid: String = newUuid(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun weeks(): List<IntArray> = parseWeeksStr(weeksStr)
}

@Entity(tableName = "tasks")
data class TaskItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,        // "hw" | "rand"
    val course: String = "", // hw 归属课程
    val title: String,
    val note: String = "",
    val due: Long? = null,   // epoch millis
    val done: Boolean = false,
    val pin: Boolean = false,
    val epic: String? = null,
    val epicVer: Int = 0,
    val uuid: String = newUuid(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses") fun all(): Flow<List<CourseEntry>>
    @Query("SELECT * FROM courses") suspend fun allNow(): List<CourseEntry>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<CourseEntry>)
    @Query("DELETE FROM courses") suspend fun clear()
    @Query("SELECT COUNT(*) FROM courses") suspend fun count(): Int
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE kind=:kind") fun byKind(kind: String): Flow<List<TaskItem>>
    @Query("SELECT * FROM tasks") fun all(): Flow<List<TaskItem>>
    @Query("SELECT * FROM tasks") suspend fun allNow(): List<TaskItem>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(t: TaskItem): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(t: List<TaskItem>)
    @Update suspend fun update(t: TaskItem)
    @Delete suspend fun delete(t: TaskItem)
    @Query("DELETE FROM tasks WHERE id=:id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM tasks") suspend fun clear()
    @Query("SELECT COUNT(*) FROM tasks") suspend fun count(): Int
}

/** v1 → v2:两张表各补 uuid / updatedAt。
 *  SQLite 不能给已有表加「NOT NULL 且无默认值」的列,所以这里重建表再搬数据,
 *  这样建出来的表结构与 Room 期望的完全一致(不依赖 default 值做校验)。
 *  老数据在这里一次性补上 uuid 与时间戳。 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS courses_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL, short TEXT NOT NULL, tea TEXT NOT NULL,
                day INTEGER NOT NULL, band INTEGER NOT NULL, weeksStr TEXT NOT NULL,
                room TEXT NOT NULL, colorIdx INTEGER NOT NULL,
                uuid TEXT NOT NULL, updatedAt INTEGER NOT NULL)"""
        )
        db.execSQL(
            """INSERT INTO courses_new (id,name,short,tea,day,band,weeksStr,room,colorIdx,uuid,updatedAt)
               SELECT id,name,short,tea,day,band,weeksStr,room,colorIdx,
                      lower(hex(randomblob(16))), CAST(strftime('%s','now') AS INTEGER)*1000 FROM courses"""
        )
        db.execSQL("DROP TABLE courses")
        db.execSQL("ALTER TABLE courses_new RENAME TO courses")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS tasks_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                kind TEXT NOT NULL, course TEXT NOT NULL, title TEXT NOT NULL, note TEXT NOT NULL,
                due INTEGER, done INTEGER NOT NULL, pin INTEGER NOT NULL,
                epic TEXT, epicVer INTEGER NOT NULL,
                uuid TEXT NOT NULL, updatedAt INTEGER NOT NULL)"""
        )
        db.execSQL(
            """INSERT INTO tasks_new (id,kind,course,title,note,due,done,pin,epic,epicVer,uuid,updatedAt)
               SELECT id,kind,course,title,note,due,done,pin,epic,epicVer,
                      lower(hex(randomblob(16))), CAST(strftime('%s','now') AS INTEGER)*1000 FROM tasks"""
        )
        db.execSQL("DROP TABLE tasks")
        db.execSQL("ALTER TABLE tasks_new RENAME TO tasks")
    }
}

@Database(entities = [CourseEntry::class, TaskItem::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "campusquest.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { inst = it }
        }
    }
}
