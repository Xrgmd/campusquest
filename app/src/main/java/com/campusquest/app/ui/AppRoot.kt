package com.campusquest.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campusquest.app.MainActivity
import com.campusquest.app.data.TaskItem
import com.campusquest.app.logic.DAYCN
import com.campusquest.app.logic.todayWeekLabel
import com.campusquest.app.logic.parseDate
import com.campusquest.app.theme.LocalCqColors
import com.campusquest.app.vm.AppViewModel
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 弹层状态 */
sealed class Overlay {
    object None : Overlay()
    object Settings : Overlay()
    object Import : Overlay()
    data class Confirm(val task: TaskItem) : Overlay()
    data class Add(val course: String?) : Overlay() // null = 支线
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val c = LocalCqColors.current
    val overlay = remember { mutableStateOf<Overlay>(Overlay.None) }
    val toast = remember { mutableStateOf<String?>(null) }
    val tab by vm.tab.collectAsState()
    // 从桌面小组件点进来:处理一次「直达页签」的请求
    LaunchedEffect(Unit) {
        MainActivity.pendingTab?.let { t ->
            MainActivity.pendingTab = null
            if (t == "hw" || t == "rand") vm.setTab(t)
        }
    }
    val actPopFor = remember { mutableStateOf<Long?>(null) }
    val rootView = androidx.compose.ui.platform.LocalView.current
    // 任何弹层关闭路径都收起键盘
    val closeOverlay = {
        KeyboardUtil.hide(rootView)
        overlay.value = Overlay.None
        Unit
    }

    LaunchedEffect(Unit) { vm.toast.collectLatest { toast.value = it } }
    LaunchedEffect(toast.value) {
        if (toast.value != null) { kotlinx.coroutines.delay(1900); toast.value = null }
    }

    Box(Modifier.fillMaxSize()) {
        BackgroundLayer()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 96.dp)
        ) {
            TopBar(vm, onTheme = { vm.setDark(!(vm.dark.value ?: false)) }, onThemeAuto = { vm.clearDark() },
                onSettings = { overlay.value = Overlay.Settings })
            SchedulePanel(vm, onImport = { overlay.value = Overlay.Import })
            Spacer(Modifier.height(16.dp))
            TasksPanel(vm, actPopFor, onAddHw = { overlay.value = Overlay.Add(it) }, onConfirm = { overlay.value = Overlay.Confirm(it) })
            Spacer(Modifier.height(30.dp))
        }

        // 支线悬浮「新增」
        if (tab == "rand") {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 26.dp)
                    // 深色模式不放黑色投影:投影落在暗背景上就是一块会跟着内容动的灰斑
                    .shadow(if (c.isDark) 0.dp else 12.dp, CircleShape)
                    .clip(CircleShape)
                    .background(c.accent)
                    .clickable { overlay.value = Overlay.Add(null) }
                    .padding(horizontal = 40.dp, vertical = 12.dp)
            ) {
                Text("新增", color = if (c.isDark) androidx.compose.ui.graphics.Color(0xFF0B0B12) else androidx.compose.ui.graphics.Color.White,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            }
        }

        // toast
        toast.value?.let {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(androidx.compose.ui.graphics.Color(0xE014141A))
                    .padding(horizontal = 20.dp, vertical = 11.dp)
            ) { Text(it, color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp) }
        }

        // 弹层
        when (val o = overlay.value) {
            is Overlay.Settings -> SettingsSheet(vm) { closeOverlay() }
            is Overlay.Confirm -> ConfirmDialog(o.task, vm) { closeOverlay() }
            is Overlay.Add -> AddDialog(o.course, vm) { closeOverlay() }
            is Overlay.Import -> ImportSheet(vm) { closeOverlay() }
            else -> {}
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopBar(vm: AppViewModel, onTheme: () -> Unit, onThemeAuto: () -> Unit, onSettings: () -> Unit) {
    val c = LocalCqColors.current
    val now by vm.now.collectAsState()
    val wx by vm.weather.collectAsState()
    val start = parseDate(vm.semesterStart.collectAsState().value)
    val (week, _) = todayWeekLabel(start)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column {
            Text("CAMPUS QUEST", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.ink3, letterSpacing = 3.4.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${now.monthValue}月${now.dayOfMonth}日", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.ink)
                Spacer(Modifier.width(8.dp))
                Text("周${DAYCN[(now.dayOfWeek.value - 1)]} · 第${week}周", fontSize = 13.sp, color = c.ink2, modifier = Modifier.padding(bottom = 2.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text(wx.icon, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(wx.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.ink2, letterSpacing = 1.sp)
                if (wx.loc.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(wx.loc, fontSize = 11.sp, color = c.ink3)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBtn("◐", onTheme, onLongClick = onThemeAuto)
            IconBtn("⚙", onSettings)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IconBtn(text: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val c = LocalCqColors.current
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.panel)
            .border(1.dp, c.panelBorder, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 16.sp, color = c.ink) }
}
