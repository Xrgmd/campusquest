package com.campusquest.app.logic

import android.content.Context
import android.net.Uri
import com.campusquest.app.data.AppDb
import com.campusquest.app.data.CourseEntry
import com.campusquest.app.data.Prefs
import com.campusquest.app.data.TaskItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 备份 = 一份自包含的 JSON(课程 + 任务 + 偏好 + 手动顺序)。
 *
 * 为什么现在就做:更新本身不会动数据,真正会丢数据的是「卸载重装」——
 * 有了导出/恢复,那条路就不是死路。顺带给将来(可选的)同步打地基:
 * 每条记录都带 uuid 与 updatedAt,合并时才能认出「同一条」和「谁更新」。
 */
object Backup {
    const val FORMAT = 1
    const val MAX_SNAPSHOTS = 7
    private const val AUTO_GAP_MS = 20L * 60 * 60 * 1000   // 20 小时

    data class Snap(val file: File, val ts: Long, val courses: Int, val tasks: Int)

    data class RestoreResult(val courses: Int, val tasks: Int)

    // ---------------- 序列化 ----------------

    suspend fun exportJson(ctx: Context): String {
        val db = AppDb.get(ctx)
        val courses = db.courseDao().allNow()
        val tasks = db.taskDao().allNow()

        val courseOrder = runCatching { Prefs.courseOrder(ctx).first() }.getOrDefault(emptyList())
        val randIds = runCatching { Prefs.randOrder(ctx).first() }.getOrDefault(emptyList())
        // 顺序里存的是数据库 id,跨设备/重装后会变——所以导出时必须换成 uuid
        val idToUuid = tasks.associate { it.id to it.uuid }
        val randUuids = randIds.mapNotNull { idToUuid[it] }

        val prefs = JSONObject().apply {
            put("semesterStart", runCatching { Prefs.start(ctx).first() }.getOrNull() ?: DEFAULT_SEMESTER_START)
            put("totalWeeks", runCatching { Prefs.weeks(ctx).first() }.getOrNull() ?: DEFAULT_TOTAL_WEEKS)
            put("city", runCatching { Prefs.city(ctx).first() }.getOrNull() ?: "")
            val d = runCatching { Prefs.dark(ctx).first() }.getOrNull()
            if (d == null) put("dark", JSONObject.NULL) else put("dark", d)
            put("collapsed", JSONArray(runCatching { Prefs.collapsed(ctx).first() }.getOrDefault(emptySet()).toList()))
            put("doneOpen", JSONArray(runCatching { Prefs.doneOpen(ctx).first() }.getOrDefault(emptySet()).toList()))
            put("courseOrder", JSONArray(courseOrder))
            put("randOrder", JSONArray(randUuids))
        }

        val cArr = JSONArray()
        courses.forEach { c ->
            cArr.put(JSONObject().apply {
                put("uuid", c.uuid); put("name", c.name); put("short", c.short); put("tea", c.tea)
                put("day", c.day); put("band", c.band); put("weeksStr", c.weeksStr)
                put("room", c.room); put("colorIdx", c.colorIdx); put("updatedAt", c.updatedAt)
            })
        }
        val tArr = JSONArray()
        tasks.forEach { t ->
            tArr.put(JSONObject().apply {
                put("uuid", t.uuid); put("kind", t.kind); put("course", t.course)
                put("title", t.title); put("note", t.note)
                if (t.due == null) put("due", JSONObject.NULL) else put("due", t.due)
                put("done", t.done); put("pin", t.pin)
                if (t.epic == null) put("epic", JSONObject.NULL) else put("epic", t.epic)
                put("epicVer", t.epicVer); put("updatedAt", t.updatedAt)
            })
        }

        return JSONObject().apply {
            put("app", "CampusQuest")
            put("format", FORMAT)
            put("versionName", "0.3.4")
            put("exportedAt", System.currentTimeMillis())
            put("prefs", prefs)
            put("courses", cArr)
            put("tasks", tArr)
        }.toString(2)
    }

    suspend fun restore(ctx: Context, text: String): RestoreResult = withContext(Dispatchers.IO) {
        val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("不是有效的备份文件") }
        if (root.optString("app") != "CampusQuest") throw IllegalArgumentException("这不是 CampusQuest 的备份")
        if (root.optInt("format", 0) > FORMAT) throw IllegalArgumentException("备份来自更新的版本,请先升级 App")
        val now = System.currentTimeMillis()

        val cArr = root.optJSONArray("courses") ?: JSONArray()
        val tArr = root.optJSONArray("tasks") ?: JSONArray()
        val courses = (0 until cArr.length()).map { i ->
            val o = cArr.getJSONObject(i)
            CourseEntry(
                name = o.optString("name"),
                short = o.optString("short"),
                tea = o.optString("tea"),
                day = o.optInt("day", 1),
                band = o.optInt("band", 0),
                weeksStr = o.optString("weeksStr"),
                room = o.optString("room"),
                colorIdx = o.optInt("colorIdx", 1),
                uuid = o.optString("uuid").ifBlank { UUID.randomUUID().toString() },
                updatedAt = o.optLong("updatedAt", now),
            )
        }.filter { it.name.isNotBlank() }

        val tasks = (0 until tArr.length()).map { i ->
            val o = tArr.getJSONObject(i)
            TaskItem(
                kind = o.optString("kind", "hw"),
                course = o.optString("course"),
                title = o.optString("title"),
                note = o.optString("note"),
                due = if (o.isNull("due")) null else o.optLong("due"),
                done = o.optBoolean("done", false),
                pin = o.optBoolean("pin", false),
                epic = if (o.isNull("epic")) null else o.optString("epic"),
                epicVer = o.optInt("epicVer", 0),
                uuid = o.optString("uuid").ifBlank { UUID.randomUUID().toString() },
                updatedAt = o.optLong("updatedAt", now),
            )
        }.filter { it.title.isNotBlank() }

        val db = AppDb.get(ctx)
        db.courseDao().clear()
        db.taskDao().clear()
        db.courseDao().insertAll(courses)
        // 先落库再读回来拿新 id,顺序表里存的是 uuid,要映射成这台的 id
        val uuidToId = run {
            db.taskDao().upsertAll(tasks)
            db.taskDao().allNow().associate { it.uuid to it.id }
        }

        root.optJSONObject("prefs")?.let { p ->
            p.optString("semesterStart").takeIf { it.isNotBlank() }?.let { Prefs.setStart(ctx, it) }
            if (p.has("totalWeeks")) Prefs.setWeeks(ctx, p.optInt("totalWeeks", DEFAULT_TOTAL_WEEKS).coerceIn(1, 30))
            p.optString("city").let { Prefs.setCity(ctx, it) }
            if (p.has("dark") && !p.isNull("dark")) Prefs.setDark(ctx, p.optBoolean("dark")) else Prefs.clearDark(ctx)
            p.optJSONArray("collapsed")?.let { a -> Prefs.setCollapsed(ctx, (0 until a.length()).map { a.optString(it) }.toSet()) }
            p.optJSONArray("doneOpen")?.let { a -> Prefs.setDoneOpen(ctx, (0 until a.length()).map { a.optString(it) }.toSet()) }
            p.optJSONArray("courseOrder")?.let { a -> Prefs.setCourseOrder(ctx, (0 until a.length()).map { a.optString(it) }) }
            p.optJSONArray("randOrder")?.let { a ->
                val ids = (0 until a.length()).mapNotNull { i -> uuidToId[a.optString(i)] }
                Prefs.setRandOrder(ctx, ids)
            }
        }
        Prefs.setSeeded(ctx)   // 恢复过就不再播种演示任务
        RestoreResult(courses.size, tasks.size)
    }

    // ---------------- 本地快照 ----------------

    fun dir(ctx: Context): File = File(ctx.filesDir, "snapshots").apply { if (!exists()) mkdirs() }

    fun list(ctx: Context): List<Snap> {
        val files = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            val ts = f.name.removePrefix("cq-").removeSuffix(".json").toLongOrNull() ?: f.lastModified()
            runCatching {
                val o = JSONObject(f.readText())
                Snap(f, ts, o.optJSONArray("courses")?.length() ?: 0, o.optJSONArray("tasks")?.length() ?: 0)
            }.getOrNull()
        }.sortedByDescending { it.ts }
    }

    suspend fun writeSnapshot(ctx: Context, json: String): File = withContext(Dispatchers.IO) {
        val f = File(dir(ctx), "cq-${System.currentTimeMillis()}.json")
        f.writeText(json)
        prune(ctx)
        f
    }

    fun prune(ctx: Context) {
        val all = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (all.size <= MAX_SNAPSHOTS) return
        all.sortedByDescending { it.name }.drop(MAX_SNAPSHOTS).forEach { runCatching { it.delete() } }
    }

    /** 每次启动调一次:距上一份快照超过 20 小时就再存一份(最多留 7 份,约一周) */
    suspend fun autoSnapshot(ctx: Context): File? {
        val last = list(ctx).firstOrNull()
        if (last != null && System.currentTimeMillis() - last.ts < AUTO_GAP_MS) return null
        return writeSnapshot(ctx, exportJson(ctx))
    }

    // ---------------- 与系统文件选择器对接 ----------------

    suspend fun writeToUri(ctx: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { out -> out.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("无法写入这个位置")
    }

    suspend fun readFromUri(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        ctx.contentResolver.openInputStream(uri)?.use { input -> input.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("无法读取这个文件")
    }

    fun suggestFileName(): String {
        val d = java.time.LocalDateTime.now()
        return String.format("campusquest-%04d%02d%02d-%02d%02d.json",
            d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute)
    }

    /** 快照时间戳 → 「今天 22:10」/「10-20 08:15」 */
    fun fmtSnap(ts: Long): String {
        val t = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
        val now = java.time.LocalDate.now()
        val hm = String.format("%02d:%02d", t.hour, t.minute)
        return when (t.toLocalDate()) {
            now -> "今天 $hm"
            now.minusDays(1) -> "昨天 $hm"
            else -> String.format("%d-%02d %s", t.monthValue, t.dayOfMonth, hm)
        }
    }
}
