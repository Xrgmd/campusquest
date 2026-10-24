package com.campusquest.app.widget

import android.content.Context
import com.campusquest.app.data.AppDb
import com.campusquest.app.data.CourseEntry
import com.campusquest.app.data.Prefs
import com.campusquest.app.data.TaskItem
import com.campusquest.app.logic.BANDS
import com.campusquest.app.logic.DAYCN
import com.campusquest.app.logic.DEFAULT_TOTAL_WEEKS
import com.campusquest.app.logic.currentWeek
import com.campusquest.app.logic.parseDate
import com.campusquest.app.logic.weekMatch
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 桌面小组件的数据模型 + 取数逻辑(0.4.0)。
 *
 * 排序原则(用户要求):**待办优先,课表次之**。
 *   置顶插队 → 逾期 → 今天到期 → 48 小时内 → 更远 → 不限时间。
 * 所有文案都在这里算好,渲染层只负责把字符串塞进 RemoteViews。
 */
object WidgetData {

    /** 紧急度分桶:0 逾期 / 1 今天 / 2 两天内 / 3 更远 / 4 不限时 */
    const val B_OVERDUE = 0
    const val B_TODAY = 1
    const val B_SOON = 2
    const val B_LATER = 3
    const val B_NONE = 4

    data class WTask(
        val id: Long,
        val title: String,
        val sub: String,      // 课程名(hw)或「支线」
        val chip: String,     // 「逾期 2 天」「剩 17 时」「不限时」
        val bucket: Int,
        val pin: Boolean,
        val kind: String = "hw",   // "hw" 课程作业 / "rand" 支线,决定点行时跳哪个页签
    )

    data class WCourse(
        val name: String,
        val room: String,
        val hhmm: String,     // 08:00
        val colorIdx: Int,    // 1..7
        val startMin: Int,
        val endMin: Int,
    )

    data class Snap(
        val dateLabel: String,   // 10/21
        val weekday: String,     // 周三
        val week: Int,
        val totalWeeks: Int,
        val tasks: List<WTask>,
        val classes: List<WCourse>,   // 今天还没结束的课(按时间)
        val nextTime: String,         // 10:00
        val nextName: String,         // 模拟电子技术
        val nextRoom: String,         // B座518 · 还剩 1 小时 12 分
        /** 下一节课落在几天后:0=今天,1=明天,2+=周几 */
        val nextDayLabel: String,   // "下一节" / "明天" / "周一"
        val overdue: Int,
        val today: Int,
        val soon: Int,
        val later: Int,
    ) {
        val pending get() = tasks.size
        val hasTasks get() = tasks.isNotEmpty()
        val groupA get() = tasks.filter { it.bucket == B_OVERDUE }        // 逾期
        val groupB get() = tasks.filter { it.bucket == B_TODAY || it.bucket == B_SOON }  // 两天内
        val groupC get() = tasks.filter { it.bucket == B_LATER || it.bucket == B_NONE }  // 本周及以后
    }

    @JvmStatic
    suspend fun build(ctx: Context): Snap {
        val db = AppDb.get(ctx)
        val rawTasks = db.taskDao().allNow()
        val rawCourses = db.courseDao().allNow()
        val start = runCatching { Prefs.start(ctx).first() }.getOrDefault("2026-09-07")
        val totalWeeks = runCatching { Prefs.weeks(ctx).first() }.getOrDefault(DEFAULT_TOTAL_WEEKS)
        val now = LocalDateTime.now()
        val today = now.toLocalDate()

        val snapTasks = rawTasks
            .filter { !it.done }
            .map { t -> t to bucketOf(t.due, now) }
            .sortedWith(
                compareBy(
                    { if (it.first.pin) 0 else 1 },
                    { it.second },
                    { it.first.due ?: Long.MAX_VALUE },
                    { it.first.id },
                )
            )
            .map { (t, b) -> toWTask(t, b, now) }

        val week = currentWeek(parseDate(start), today)
        // 今天还没上完的课
        val todayCourses = rawCourses
            .filter { it.day == today.dayOfWeek.value && weekMatch(it.weeks(), week) }
            .map { toWCourse(it) }
            .filter { it.endMin > now.hour * 60 + now.minute }
            .sortedBy { it.startMin }

        val nextToday = todayCourses.firstOrNull { it.startMin > now.hour * 60 + now.minute }
        val ahead = if (nextToday == null) {
            nextAfter(rawCourses, today, week)
        } else null
        val nextTomorrow = ahead?.first
        val nextOffset = ahead?.second ?: 0

        return Snap(
            dateLabel = "${today.monthValue}/${today.dayOfMonth}",
            weekday = "周" + DAYCN[today.dayOfWeek.value - 1],
            week = week,
            totalWeeks = totalWeeks,
            tasks = snapTasks,
            classes = todayCourses,
            nextTime = nextToday?.hhmm ?: nextTomorrow?.hhmm ?: "",
            nextName = nextToday?.name ?: nextTomorrow?.name ?: "",
            nextRoom = nextToday?.let { joinDot(it.room, untilStart(it.startMin, now)) }
                ?: nextTomorrow?.let { it.room.ifBlank { if (nextOffset == 1) "明天第一节" else "第 ${it.hhmm} 节" } } ?: "",
            // 今天还有课 → 「下一节」;否则按真实天数:明天 / 周一 / 周四 …(旧版一律写「明天」,周六凌晨会说周一那节课是明天)
            nextDayLabel = when {
                nextToday != null -> "下一节"
                nextOffset == 1 -> "明天"
                nextOffset > 1 -> "周" + DAYCN[today.plusDays(nextOffset.toLong()).dayOfWeek.value - 1]
                else -> ""
            },
            overdue = snapTasks.count { it.bucket == B_OVERDUE },
            today = snapTasks.count { it.bucket == B_TODAY },
            soon = snapTasks.count { it.bucket == B_SOON },
            later = snapTasks.count { it.bucket == B_LATER || it.bucket == B_NONE },
        )
    }

    // ---------------- 内部 ----------------

    private fun bucketOf(due: Long?, now: LocalDateTime): Int {
        if (due == null) return B_NONE
        val d = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(due), java.time.ZoneId.systemDefault())
        if (d.isBefore(now)) return B_OVERDUE
        if (!d.toLocalDate().isAfter(now.toLocalDate())) return B_TODAY
        if (ChronoUnit.HOURS.between(now, d) < 48) return B_SOON
        return B_LATER
    }

    /** 教室为空时不要留下孤零零的「·」 */
    private fun joinDot(room: String, tail: String): String =
        if (room.isBlank()) tail else "$room · $tail"

    private fun toWTask(t: TaskItem, bucket: Int, now: LocalDateTime): WTask {
        val sub = if (t.kind == "hw" && t.course.isNotBlank()) t.course else "支线"
        return WTask(
            id = t.id,
            title = t.title,
            sub = sub,
            chip = chipOf(t.due, bucket, now),
            bucket = bucket,
            pin = t.pin,
            kind = t.kind,
        )
    }

    /** 小组件用的短文案:逾期 2 天 / 剩 17 时 / 不限时 */
    fun chipOf(due: Long?, bucket: Int, now: LocalDateTime = LocalDateTime.now()): String {
        if (due == null) return "不限时"
        val d = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(due), java.time.ZoneId.systemDefault())
        if (bucket == B_OVERDUE) {
            val mins = ChronoUnit.MINUTES.between(d, now)
            return "逾期 " + shortDur(mins)
        }
        val mins = ChronoUnit.MINUTES.between(now, d)
        return "剩 " + shortDur(mins)
    }

    /** 把分钟数压成「2 天」「17 时」「25 分」这种小组件能用的一小截 */
    private fun shortDur(mins: Long): String {
        val m = if (mins < 0) -mins else mins
        return when {
            m >= 1440 -> {
                val d = m / 1440
                val h = (m % 1440) / 60
                if (h > 0) "$d 天 $h 时" else "$d 天"
            }
            m >= 60 -> "${m / 60} 时"
            else -> "${if (m < 1) 1 else m} 分"
        }
    }

    private fun untilStart(startMin: Int, now: LocalDateTime): String {
        val mins = startMin - (now.hour * 60 + now.minute)
        return if (mins <= 0) "正在进行" else "还剩 " + longDur(mins)
    }

    private fun longDur(mins: Int): String = when {
        mins >= 60 -> {
            val h = mins / 60
            val m = mins % 60
            if (m > 0) "$h 小时 $m 分" else "$h 小时"
        }
        else -> "$mins 分钟"
    }

    private fun toWCourse(c: CourseEntry): WCourse {
        val band = BANDS.getOrNull(c.band)
        return WCourse(
            name = c.short.ifBlank { c.name },
            room = c.room,
            hhmm = band?.time ?: "",
            colorIdx = ((c.colorIdx - 1).mod(7)) + 1,
            startMin = band?.s ?: 0,
            endMin = band?.e ?: 0,
        )
    }

    /**
     * 今天之后的第一节课 + 它在几天后(1=明天,2=后天…)。
     * 注意跨周:周五之后找到周一的课,偏移是 3(周六)或 2(周日),标签必须写「周一」而不是「明天」。
     */
    private fun nextAfter(courses: List<CourseEntry>, today: LocalDate, week: Int): Pair<WCourse, Int>? {
        for (i in 1..7) {
            val d = today.plusDays(i.toLong())
            // 跨周精确:今天周几(周一=0)+ 前进天数,满 7 就进下一周
            val w = week + ((today.dayOfWeek.value - 1 + i) / 7)
            val list = courses
                .filter { it.day == d.dayOfWeek.value && weekMatch(it.weeks(), w) }
                .map { toWCourse(it) }
                .sortedBy { it.startMin }
            if (list.isNotEmpty()) return list.first() to i
        }
        return null
    }
}
