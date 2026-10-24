package com.campusquest.app.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.campusquest.app.data.*
import com.campusquest.app.logic.*
import com.campusquest.app.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

data class CourseMeta(val name: String, val tea: String, val colorIdx: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDb.get(app)
    private val ctx = app

    val dark: StateFlow<Boolean?> = Prefs.dark(ctx).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val semesterStart: StateFlow<String> = Prefs.start(ctx).stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_SEMESTER_START)
    val totalWeeks: StateFlow<Int> = Prefs.weeks(ctx).stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_TOTAL_WEEKS)
    val city: StateFlow<String> = Prefs.city(ctx).stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val collapsed: StateFlow<Set<String>> = Prefs.collapsed(ctx).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val doneOpen: StateFlow<Set<String>> = Prefs.doneOpen(ctx).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    /** 顺序必须是「同步可见」的状态:拖动时一帧内可能连换好几格,
     *  若等 DataStore 回灌,第二格会拿着过期的下标算错位置,表现为只能挪一位。*/
    private val _courseOrder = MutableStateFlow<List<String>>(emptyList())
    private val _randOrder = MutableStateFlow<List<Long>>(emptyList())
    val courseOrder: StateFlow<List<String>> = _courseOrder
    val randOrder: StateFlow<List<Long>> = _randOrder
    /** 顺序落盘走合并通道:永远只写最新一份,且保证先后次序 */
    private val orderWrites = Channel<Unit>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            _courseOrder.value = Prefs.courseOrder(ctx).first()
            _randOrder.value = Prefs.randOrder(ctx).first()
        }
        viewModelScope.launch {
            for (u in orderWrites) {
                Prefs.setCourseOrder(ctx, _courseOrder.value)
                Prefs.setRandOrder(ctx, _randOrder.value)
            }
        }
    }

    private fun flushOrder() { orderWrites.trySend(Unit) }

    val courses: StateFlow<List<CourseEntry>> = db.courseDao().all()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tasks: StateFlow<List<TaskItem>> = db.taskDao().all()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _weekOffset = MutableStateFlow(0)
    val weekOffset: StateFlow<Int> = _weekOffset

    private val _tab = MutableStateFlow("hw")
    val tab: StateFlow<String> = _tab

    private val _weather = MutableStateFlow(WeatherInfo("◌", "天气读取中…", ""))
    val weather: StateFlow<WeatherInfo> = _weather

    private val _now = MutableStateFlow(LocalDateTime.now())
    val now: StateFlow<LocalDateTime> = _now

    /** 分钟粒度时钟:供列表刷新使用,秒跳不再触发大面积重组(性能) */
    val minute: StateFlow<Int> = _now.map { it.hour * 60 + it.minute }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast

    /** 每写一份快照就 +1,界面据此刷新「恢复快照(N)」的计数 */
    private val _snapshotTick = MutableStateFlow(0)
    val snapshotTick: StateFlow<Int> = _snapshotTick

    init {
        viewModelScope.launch {
            if (db.courseDao().count() == 0) db.courseDao().insertAll(Seed.toEntries())
            if (!Prefs.seeded(ctx).first()) {
                // 首次运行预置演示任务(与原型一致)
                val zone = ZoneId.systemDefault()
                fun at(days: Long, h: Int, m: Int) = LocalDateTime.now().plusDays(days)
                    .withHour(h).withMinute(m).withSecond(0).atZone(zone).toInstant().toEpochMilli()
                db.taskDao().upsertAll(listOf(
                    TaskItem(kind = "hw", course = "人工智能基础及应用", title = "第三章 搜索算法 课后习题", due = at(3, 23, 59), note = "学习通传 PDF,命名:学号_姓名"),
                    TaskItem(kind = "hw", course = "概率论与数理统计A", title = "习题集 4(第四、五章)", due = at(1, 23, 59)),
                    TaskItem(kind = "hw", course = "离散数学", title = "实验报告 2:谓词逻辑推理", due = at(5, 23, 59), note = "第 3 节实验课当堂交纸质版"),
                    TaskItem(kind = "hw", course = "中国近现代史纲要", title = "读书论文(第八章)", due = at(-1, 23, 59), note = "800 字以上,引用 2 篇文献"),
                    TaskItem(kind = "rand", title = "英语六级口语考试", note = "带学生证、身份证", pin = true).withEpic(),
                    TaskItem(kind = "rand", title = "校园跑").withEpic(),
                    TaskItem(kind = "rand", title = "拿快递").withEpic(),
                    TaskItem(kind = "rand", title = "和宝宝出去玩", due = at(2, 18, 0)).withEpic(),
                ))
                Prefs.setSeeded(ctx)
            }
            refreshWeather()
            // 每天首次打开时静默存一份快照:距上次超过 20 小时才写,最多留 7 份。
            runCatching { Backup.autoSnapshot(ctx) }
        }
        viewModelScope.launch { while (true) { _now.value = LocalDateTime.now(); delay(1000) } }
        // 桌面小组件:任务或课程一变就重画(300ms 合并,拖动/连续操作只刷一次)
        viewModelScope.launch {
            tasks.collectLatest { delay(300); WidgetUpdater.refresh(ctx) }
        }
        viewModelScope.launch {
            courses.collectLatest { delay(300); WidgetUpdater.refresh(ctx) }
        }
    }

    private fun TaskItem.withEpic() = copy(epic = Epic.makeEpic(title), epicVer = Epic.EPIC_VER)

    fun toastMsg(s: String) { _toast.tryEmit(s) }

    fun setTab(t: String) { _tab.value = t }
    fun setWeekOffset(f: (Int) -> Int) {
        val cur = currentWeek(parseDate(semesterStart.value))
        _weekOffset.value = f(_weekOffset.value).coerceIn(-(cur - 1), totalWeeks.value - cur)
    }
    fun backToThisWeek() { _weekOffset.value = 0; toastMsg("已回到本周") }

    fun setDark(v: Boolean) = viewModelScope.launch { Prefs.setDark(ctx, v) }
    fun clearDark() = viewModelScope.launch { Prefs.clearDark(ctx); toastMsg("已恢复按时间自动切换(18:00-6:00 深色)") }
    fun setStart(v: String) = viewModelScope.launch {
        Prefs.setStart(ctx, v); _weekOffset.value = 0; toastMsg("校历已更新(周一起始)")
    }
    fun setWeeks(v: Int) = viewModelScope.launch { Prefs.setWeeks(ctx, v) }
    fun setCity(v: String) = viewModelScope.launch {
        Prefs.setCity(ctx, v); refreshWeather()
        toastMsg(if (v.isNotBlank()) "天气城市已设为 $v" else "天气恢复自动定位")
    }

    fun toggleCollapsed(key: String) = viewModelScope.launch {
        val s = collapsed.value.toMutableSet()
        if (!s.remove(key)) s.add(key)
        Prefs.setCollapsed(ctx, s)
    }
    fun toggleDoneOpen(key: String) = viewModelScope.launch {
        val s = doneOpen.value.toMutableSet()
        if (!s.remove(key)) s.add(key)
        Prefs.setDoneOpen(ctx, s)
    }

    fun refreshWeather() = viewModelScope.launch { _weather.value = Weather.fetch(ctx, city.value) }

    /** 课程组顺序:手动顺序优先,未登记的课程按「课表首次出现」顺序排在后面 */
    fun courseMeta(list: List<CourseEntry>, tasks: List<TaskItem>, order: List<String> = courseOrder.value): List<CourseMeta> {
        val seen = LinkedHashMap<String, CourseMeta>()
        list.forEach { c -> if (!seen.containsKey(c.name)) seen[c.name] = CourseMeta(c.name, c.tea, c.colorIdx) }
        tasks.filter { it.kind == "hw" }.forEach { h ->
            if (!seen.containsKey(h.course)) seen[h.course] = CourseMeta(h.course, "", (seen.size % 7) + 1)
        }
        if (order.isEmpty()) return seen.values.toList()
        val pos = order.withIndex().associate { (i, n) -> n to i }
        return seen.values.sortedBy { pos[it.name] ?: Int.MAX_VALUE }
    }

    /** 置顶始终在前;支线(未置顶)按手动顺序,未登记位置的按截止时间兜底;课程作业仍按截止时间 */
    fun sortItems(arr: List<TaskItem>): List<TaskItem> {
        val pos = randOrder.value.withIndex().associate { (i, id) -> id to i }
        return arr.sortedWith(
            compareBy(
                { if (it.pin) 0 else 1 },
                { if (it.pin) 0 else (if (it.kind == "rand") pos[it.id] ?: Int.MAX_VALUE else 0) },
                { it.due ?: Long.MAX_VALUE },
                { it.id },
            )
        )
    }

    /** 课程组上/下移一位(delta=±1);内存态立刻生效,落盘异步 */
    fun moveCourse(name: String, delta: Int) {
        val names = courseMeta(courses.value, tasks.value, _courseOrder.value).map { it.name }.toMutableList()
        val i = names.indexOf(name)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, names.size - 1)
        if (j == i) return
        names.removeAt(i)
        names.add(j, name)
        _courseOrder.value = names
        flushOrder()
    }

    /** 支线(未置顶)上/下移一位;内存态立刻生效,落盘异步 */
    fun moveRand(id: Long, delta: Int) {
        val ids = sortItems(tasks.value.filter { it.kind == "rand" && !it.done && !it.pin })
            .map { it.id }.toMutableList()
        val i = ids.indexOf(id)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, ids.size - 1)
        if (j == i) return
        ids.removeAt(i)
        ids.add(j, id)
        _randOrder.value = ids
        flushOrder()
    }

    fun completeTask(t: TaskItem) = viewModelScope.launch {
        db.taskDao().update(t.copy(done = true)); toastMsg("已完成")
    }
    fun uncompleteTask(t: TaskItem) = viewModelScope.launch {
        db.taskDao().update(t.copy(done = false)); toastMsg("已恢复未完成")
    }
    fun togglePin(t: TaskItem) = viewModelScope.launch {
        db.taskDao().update(t.copy(pin = !t.pin)); toastMsg(if (!t.pin) "已置顶 · 灯带为记" else "已取消置顶")
    }
    fun deleteTask(t: TaskItem) = viewModelScope.launch {
        db.taskDao().delete(t)
        if (t.kind == "rand" && _randOrder.value.contains(t.id)) {
            _randOrder.value = _randOrder.value - t.id
            flushOrder()
        }
        toastMsg("已删除")
    }
    fun recastEpic(t: TaskItem) = viewModelScope.launch {
        db.taskDao().update(t.copy(epic = Epic.makeEpic(t.title), epicVer = Epic.EPIC_VER)); toastMsg("题记已重铸")
    }

    fun addHw(course: String, title: String, note: String, due: LocalDateTime) = viewModelScope.launch {
        db.taskDao().upsert(TaskItem(kind = "hw", course = course, title = title, note = note,
            due = due.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
        toastMsg("已添加到「${autoShort(course)}」")
    }
    fun addRand(title: String, note: String, due: LocalDateTime?) = viewModelScope.launch {
        val id = db.taskDao().upsert(TaskItem(kind = "rand", title = title, note = note,
            due = due?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()).withEpic())
        // 新建支线放到手动顺序最前:第一次拖动前先把手头顺序固化成显式顺序
        val cur = _randOrder.value.toMutableList()
        if (cur.isEmpty()) {
            cur.addAll(sortItems(tasks.value.filter { it.kind == "rand" && !it.done && !it.pin }).map { it.id })
        }
        cur.remove(id)
        cur.add(0, id)
        _randOrder.value = cur
        flushOrder()
        toastMsg("支线已开启 · 题记已生成")
    }

    fun applyImported(entries: List<ImportEntry>) = viewModelScope.launch {
        val colorOf = LinkedHashMap<String, Int>()
        entries.forEach { e -> if (!colorOf.containsKey(e.name)) colorOf[e.name] = (colorOf.size % 7) + 1 }
        val list = entries.map { e ->
            CourseEntry(name = e.name, short = autoShort(e.name), tea = e.tea, day = e.day, band = e.band,
                weeksStr = weeksToText(e.weeks), room = e.room, colorIdx = colorOf[e.name] ?: 1)
        }
        db.courseDao().clear()
        db.courseDao().insertAll(list)
        _courseOrder.value = emptyList() // 课程换了一批,旧的手动顺序作废
        flushOrder()
        toastMsg("课表已更新 · ${entries.size} 条记录")
    }

    fun dueOf(t: TaskItem): LocalDateTime? = t.due?.let {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
    }

    // ---------------- 备份 / 恢复(0.3.4) ----------------

    /** 导出到用户选定的位置(系统文件选择器给回来的 uri) */
    fun exportTo(uri: Uri) = viewModelScope.launch {
        runCatching {
            val json = Backup.exportJson(ctx)
            Backup.writeToUri(ctx, uri, json)
        }.onSuccess {
            toastMsg("已导出备份 · 换个手机装回来就能恢复")
        }.onFailure {
            toastMsg("导出失败:${it.message ?: "未知原因"}")
        }
    }

    /** 导入会**整体覆盖**本地数据,所以先把当前状态快照一份,后悔还能退回去 */
    fun importFrom(uri: Uri) = viewModelScope.launch {
        var backupMade = false
        val result = runCatching {
            Backup.writeSnapshot(ctx, Backup.exportJson(ctx)).also { backupMade = true; _snapshotTick.value++ }
            val text = Backup.readFromUri(ctx, uri)
            Backup.restore(ctx, text)
        }
        result.onSuccess { r ->
            try {
                _courseOrder.value = Prefs.courseOrder(ctx).first()
                _randOrder.value = Prefs.randOrder(ctx).first()
            } catch (_: Exception) { }
            toastMsg("已恢复 ${r.courses} 门课 · ${r.tasks} 条任务" + (if (backupMade) " · 旧数据已存快照" else ""))
        }.onFailure {
            toastMsg("恢复失败:${it.message ?: "未知原因"}")
        }
    }

    fun makeSnapshot() = viewModelScope.launch {
        runCatching { Backup.writeSnapshot(ctx, Backup.exportJson(ctx)) }
            .onSuccess { toastMsg("已存一份快照 · 最多保留 7 份"); _snapshotTick.value++ }
            .onFailure { toastMsg("快照失败:${it.message ?: "未知原因"}") }
    }

    /** 备份列表(IO 里读文件,别卡主线程) */
    suspend fun snapshots(): List<Backup.Snap> = withContext(Dispatchers.IO) { Backup.list(ctx) }

    fun restoreSnapshot(s: Backup.Snap) = viewModelScope.launch {
        runCatching {
            Backup.writeSnapshot(ctx, Backup.exportJson(ctx))   // 先给现状也留一份
            Backup.restore(ctx, s.file.readText())
        }.onSuccess { r ->
            try {
                _courseOrder.value = Prefs.courseOrder(ctx).first()
                _randOrder.value = Prefs.randOrder(ctx).first()
            } catch (_: Exception) { }
            _snapshotTick.value++
            toastMsg("已回到 ${Backup.fmtSnap(s.ts)} · ${r.courses} 门课 · ${r.tasks} 任务")
        }.onFailure {
            toastMsg("恢复失败:${it.message ?: "未知原因"}")
        }
    }

    /** 启动时静默存一份,距上次超过 20 小时才会真的写 */
    fun autoSnapshot() = viewModelScope.launch {
        runCatching { Backup.autoSnapshot(ctx) }
    }
}
