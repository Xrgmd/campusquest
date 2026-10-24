package com.campusquest.app.logic

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

const val DEFAULT_SEMESTER_START = "2026-09-07" // 第 1 周周一
const val DEFAULT_TOTAL_WEEKS = 22

data class Band(val label: String, val time: String, val s: Int, val e: Int)

val BANDS = listOf(
    Band("1-2节", "08:00", 480, 580),
    Band("3-4节", "10:00", 600, 700),
    Band("5-6节", "14:00", 840, 940),
    Band("7-8节", "16:00", 960, 1060),
    Band("9-10节", "19:00", 1140, 1240),
    Band("11-12节", "20:50", 1250, 1350),
)

val DAYCN = listOf("一", "二", "三", "四", "五", "六", "日")

fun mondayOf(d: LocalDate): LocalDate =
    d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** 当前周次(周一起始):第 1 周 = 开学周一所在周 */
fun currentWeek(start: LocalDate, today: LocalDate = LocalDate.now()): Int =
    (ChronoUnit.DAYS.between(mondayOf(start), mondayOf(today)) / 7).toInt() + 1

/** 周次匹配:ranges 元素 [a,b] 或 [a,b,"odd"|"even"] */
fun weekMatch(ranges: List<IntArray>, week: Int): Boolean =
    ranges.any { r ->
        val a = r[0]; val b = r[1]
        if (week < a || week > b) false
        else when (r.getOrNull(2)) {
            1 -> week % 2 == 1 // odd
            2 -> week % 2 == 0 // even
            else -> true
        }
    }

fun weeksToText(ws: List<IntArray>): String = ws.joinToString(",") { w ->
    val base = if (w[0] == w[1]) "${w[0]}" else "${w[0]}-${w[1]}"
    base + when (w.getOrNull(2)) { 1 -> "单"; 2 -> "双"; else -> "" }
}

/** 解析周次串,含 OCR 粘连修复(116→1-16;10-1214-16→10-12,14-16),与原型 parseWeeksStr 对齐 */
fun parseWeeksStr(s: String, parity: Int? = null, totalWeeks: Int = DEFAULT_TOTAL_WEEKS): List<IntArray> {
    val out = mutableListOf<IntArray>()
    s.split(Regex("[,、;；\\s]+")).forEach { tok0 ->
        if (tok0.isEmpty()) return@forEach
        val pm = Regex("(单|双)$").find(tok0)
        val par = when (pm?.value) { "单" -> 1; "双" -> 2; else -> parity }
        val tok = tok0.replace(Regex("[单双]$"), "")
        val segs = tok.split(Regex("[-－~]")).map { it.trim() }.filter { it.isNotEmpty() }
        val flat = mutableListOf<Int>()
        segs.forEach segLoop@{ seg ->
            if (!Regex("^\\d+$").matches(seg)) return@segLoop
            val n = seg.toInt()
            if (n > 30) {
                when {
                    seg.length == 3 -> { flat.add(seg[0].digitToInt()); flat.add(seg.substring(1).toInt()) }
                    seg.length == 4 -> { flat.add(seg.substring(0, 2).toInt()); flat.add(seg.substring(2).toInt()) }
                    seg.length % 2 == 0 -> { var i = 0; while (i < seg.length) { flat.add(seg.substring(i, i + 2).toInt()); i += 2 } }
                    else -> flat.add(n)
                }
            } else flat.add(n)
        }
        var i = 0
        while (i + 1 < flat.size) {
            var a = flat[i]; var b = flat[i + 1]
            i += 2
            if (a > b) { val t = a; a = b; b = t }          // OCR "3-1" → 1-3
            if (a > totalWeeks && b > totalWeeks) continue  // OCR 噪声数字(如 60)丢弃
            a = a.coerceIn(1, totalWeeks); b = b.coerceIn(1, totalWeeks)
            out.add(if (par != null) intArrayOf(a, b, par) else intArrayOf(a, b))
        }
        if (flat.size % 2 == 1) {
            val a0 = flat.last()
            if (a0 in 1..totalWeeks) out.add(if (par != null) intArrayOf(a0, a0, par) else intArrayOf(a0, a0))
        }
    }
    return if (out.isEmpty()) listOf(intArrayOf(1, totalWeeks)) else out
}

val SHORT_OVERRIDE = mapOf(
    "人工智能基础及应用" to "人工智能基础",
    "概率论与数理统计A" to "概率论",
    "复变函数与积分变换" to "复变函数",
    "中国近现代史纲要" to "近代史纲要",
    "大学物理B(二)" to "大学物理",
)

fun autoShort(name: String): String {
    SHORT_OVERRIDE[name]?.let { return it }
    val t = name.replace(Regex("\\(.*?\\)"), "").replace(Regex("（.*?）"), "")
        .replace(Regex("[A-Z]$"), "")
    return if (t.length > 6) t.substring(0, 6) else t.ifEmpty { name.take(6) }
}

/** 倒计时状态:与原型 cdState 一致 */
data class CdState(val level: Int, val txt: String) // level: 0 常态 1 warn 2 danger 3 done

fun fmtDur(ms: Long): String {
    val m = ms / 60000; val h = m / 60; val d = h / 24
    return when {
        d > 0 -> "$d 天 ${h % 24} 小时"
        h > 0 -> "$h 小时 ${m % 60} 分"
        else -> "${maxOf(m, 0)} 分钟"
    }
}

fun fmtDue(d: LocalDateTime?): String =
    d?.let { "${it.monthValue}/${it.dayOfMonth} %02d:%02d".format(it.hour, it.minute) } ?: "不限时间"

fun cdState(done: Boolean, due: LocalDateTime?, now: LocalDateTime = LocalDateTime.now()): CdState {
    if (done) return CdState(3, "已完成")
    if (due == null) return CdState(0, "不限时")
    val diff = ChronoUnit.MILLIS.between(now, due)
    if (diff < 0) return CdState(2, "逾期 ${fmtDur(-diff)}")
    if (diff < 48L * 3600000) return CdState(if (diff < 6L * 3600000) 2 else 1, "剩 ${fmtDur(diff)}")
    return CdState(0, "剩 ${fmtDur(diff)}")
}

fun todayWeekLabel(start: LocalDate): Pair<Int, String> {
    val w = currentWeek(start)
    val ws = mondayOf(start).plusWeeks((w - 1).toLong())
    val we = ws.plusDays(6)
    val f = DateTimeFormatter.ofPattern("M/d")
    return w to "${ws.format(f)}-${we.format(f)}"
}

fun parseDate(s: String): LocalDate = LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE)
fun nowTime(): LocalTime = LocalTime.now()
