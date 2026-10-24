package com.campusquest.app.data

import com.campusquest.app.logic.weeksToText

/** 真实课表 2026-2027-1(周一起始),与原型 DEFAULT_SCHEDULE 一致 */
object Seed {
    data class S(val name: String, val short: String, val tea: String, val day: Int, val band: Int,
                 val weeks: List<IntArray>, val room: String, val c: Int)

    val SCHEDULE = listOf(
        S("人工智能基础及应用", "人工智能基础", "刘丽珏", 1, 0, listOf(intArrayOf(11, 18)), "D座120", 1),
        S("大学物理B(二)", "大学物理", "邹慧", 2, 0, listOf(intArrayOf(3, 18)), "A座317", 4),
        S("中国近现代史纲要", "近代史纲要", "秦勤", 5, 0, listOf(intArrayOf(3, 18)), "C座207", 7),
        S("模拟电子技术", "模拟电子技术", "周晓君", 3, 1, listOf(intArrayOf(3, 16)), "B座518", 3),
        S("体育(三)", "体育(三)", "何志斌", 4, 1, listOf(intArrayOf(3, 18)), "", 5),
        S("人工智能基础及应用", "人工智能基础", "刘丽珏", 5, 1, listOf(intArrayOf(11, 18)), "B座404", 1),
        S("概率论与数理统计A", "概率论", "牛原玲", 1, 2, listOf(intArrayOf(3, 16)), "B座414", 2),
        S("离散数学", "离散数学", "马山", 2, 2, listOf(intArrayOf(2, 9)), "B座101", 6),
        S("离散数学", "离散数学", "马山", 2, 2, listOf(intArrayOf(10, 17)), "B座301", 6),
        S("复变函数与积分变换", "复变函数", "汪泓泽", 4, 2, listOf(intArrayOf(3, 12)), "C座206", 5),
        S("大学物理B(二)", "大学物理", "邹慧", 5, 2, listOf(intArrayOf(3, 18)), "A座401", 4),
        S("复变函数与积分变换", "复变函数", "汪泓泽", 1, 3, listOf(intArrayOf(3, 12)), "C座207", 5),
        S("概率论与数理统计A", "概率论", "牛原玲", 4, 3, listOf(intArrayOf(3, 16)), "B座514", 2),
        S("模拟电子技术", "模拟电子技术", "周晓君", 5, 3, listOf(intArrayOf(3, 16)), "B座510", 3),
        S("形势与政策", "形势与政策", "欧旭理", 1, 4, listOf(intArrayOf(8, 8), intArrayOf(12, 12)), "B座219", 7),
        S("微观经济学", "微观经济学", "程继鑫", 2, 4, listOf(intArrayOf(3, 18)), "B座411", 2),
        S("金融学", "金融学", "朱宁", 4, 4, listOf(intArrayOf(3, 18)), "B座517", 3),
        S("离散数学", "离散数学", "马山", 5, 4, listOf(intArrayOf(2, 9)), "B座101", 6),
    )

    fun toEntries(): List<CourseEntry> = SCHEDULE.map {
        CourseEntry(name = it.name, short = it.short, tea = it.tea, day = it.day, band = it.band,
            weeksStr = weeksToText(it.weeks), room = it.room, colorIdx = it.c)
    }
}
