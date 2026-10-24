package com.campusquest.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campusquest.app.data.CourseEntry
import com.campusquest.app.logic.*
import com.campusquest.app.theme.LocalCqColors
import com.campusquest.app.vm.AppViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun SchedulePanel(vm: AppViewModel, onImport: () -> Unit) {
    val c = LocalCqColors.current
    val courses by vm.courses.collectAsState()
    val start = parseDate(vm.semesterStart.collectAsState().value)
    val total = vm.totalWeeks.collectAsState().value
    val offset by vm.weekOffset.collectAsState()
    val minute by vm.minute.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    val viewWeek = currentWeek(start) + offset
    val ws = mondayOf(start).plusWeeks((viewWeek - 1).toLong())
    val dates = (0..6).map { ws.plusDays(it.toLong()) }
    val today = LocalDate.now()
    val todayIdx = if (today in dates) dates.indexOf(today) else -1
    val weekCourses = courses.filter { weekMatch(it.weeks(), viewWeek) }
    val byCell = remember(weekCourses) { weekCourses.groupBy { it.day to it.band } }

    Column(
        Modifier
            .fillMaxWidth()
            // 不再给玻璃面板加投影:面板是半透明的,投影会从面板内部透出来,
            // 在浅色下就是一圈「灰框」(用户反馈的课表四周灰框)。
            // 玻璃质感靠 1dp 亮边(border)+ 半透明白底表达。
            .clip(RoundedCornerShape(26.dp))
            .background(c.panel)
            .border(1.dp, c.panelBorder, RoundedCornerShape(26.dp))
            .padding(14.dp)
    ) {
        // 头部
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(3.5.dp, 14.dp).clip(RoundedCornerShape(99.dp)).background(c.accent))
            Spacer(Modifier.width(10.dp))
            Text("本周课表", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.ink)
            Spacer(Modifier.width(8.dp))
            Text("第 $viewWeek 周 / 共 $total 周", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = c.accent, modifier = Modifier
                    .clip(RoundedCornerShape(99.dp)).background(c.accentSoft)
                    .padding(horizontal = 8.dp, vertical = 3.dp))
            Spacer(Modifier.weight(1f))
            Text("‹", fontSize = 18.sp, color = c.ink2, modifier = Modifier
                .clip(CircleShape).clickable(enabled = offset > -(currentWeek(start) - 1)) { vm.setWeekOffset { it - 1 } }
                .padding(horizontal = 10.dp, vertical = 2.dp))
            Text("›", fontSize = 18.sp, color = c.ink2, modifier = Modifier
                .clip(CircleShape).clickable(enabled = offset < total - currentWeek(start)) { vm.setWeekOffset { it + 1 } }
                .padding(horizontal = 10.dp, vertical = 2.dp))
            Box {
                Text("☰", fontSize = 15.sp, color = c.ink2, modifier = Modifier
                    .clip(RoundedCornerShape(10.dp)).clickable { menuOpen = true }
                    .padding(horizontal = 9.dp, vertical = 4.dp))
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen, onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(c.sheet).border(1.dp, c.panelBorder, RoundedCornerShape(16.dp)),
                ) {
                    DropdownItem("回到本周") { menuOpen = false; vm.backToThisWeek() }
                    DropdownItem("导入课表(xls/照片)") { menuOpen = false; onImport() }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // 表头
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.width(40.dp))
            dates.forEachIndexed { i, _ ->
                val isToday = i == todayIdx
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (isToday) c.todayCol else androidx.compose.ui.graphics.Color.Transparent)
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("周${DAYCN[i]}", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = if (isToday) c.accent else c.ink2)
                    if (isToday) Box(Modifier.padding(top = 3.dp).size(3.dp).clip(CircleShape).background(c.accent))
                }
            }
        }

        // 网格:6 段 × 7 天
        BANDS.forEachIndexed { band, bd ->
            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Column(Modifier.width(40.dp).height(88.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(bd.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.ink2)
                    Text(bd.time, fontSize = 9.sp, color = c.ink3, modifier = Modifier.padding(top = 2.dp))
                }
                (1..7).forEach { day ->
                    val es = byCell[day to band].orEmpty()
                    Box(
                        Modifier
                            .weight(1f).height(88.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (es.isEmpty() && day - 1 == todayIdx) c.todayCol
                                else androidx.compose.ui.graphics.Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        val e = es.firstOrNull()
                        if (e != null) {
                            val cc = c.course[(e.colorIdx - 1).mod(7)]
                            Column(
                                Modifier.fillMaxSize().padding(2.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(cc.copy(alpha = 0.16f))
                                    .border(1.dp, cc.copy(alpha = 0.36f), RoundedCornerShape(10.dp)),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    VerticalText(e.short.take(6), 10.sp, FontWeight.Bold, cc, maxLines = 6)
                                    if (e.room.isNotEmpty()) {
                                        Spacer(Modifier.width(4.dp))
                                        VerticalText(e.room, 9.sp, FontWeight.Normal, c.chipRoom, maxLines = 7)
                                    }
                                }
                                // 上课中徽标(课程色底 + 白字,对齐原型 chip.now)
                                val live = day - 1 == todayIdx && minute in bd.s until bd.e
                                if (live) {
                                    Text("上课中", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(99.dp)).background(cc)
                                            .padding(horizontal = 5.dp, vertical = 2.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 底部 note
        val todayCount = if (todayIdx >= 0) weekCourses.count { it.day == todayIdx + 1 } else 0
        val f = DateTimeFormatter.ofPattern("M/d")
        Text(
            "第 $viewWeek 周 · ${ws.format(f)}-${ws.plusDays(6).format(f)} · 今天 $todayCount 节课",
            fontSize = 11.sp, color = c.ink3, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
    }
}

/** 竖排文字(逐字换行模拟 writing-mode:vertical-rl) */
@Composable
fun VerticalText(text: String, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight,
                 color: androidx.compose.ui.graphics.Color, maxLines: Int) {
    val t = text.take(maxLines).toCharArray().joinToString("\n")
    Text(t, fontSize = size, fontWeight = weight, color = color, textAlign = TextAlign.Center,
        lineHeight = size * 1.15, overflow = TextOverflow.Clip)
}

@Composable
fun DropdownItem(text: String, onClick: () -> Unit) {
    val c = LocalCqColors.current
    Text(text, fontSize = 13.sp, color = c.ink, modifier = Modifier
        .fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp))
}
