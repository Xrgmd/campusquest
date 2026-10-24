package com.campusquest.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.graphics.drawable.GradientDrawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.campusquest.app.data.TaskItem
import com.campusquest.app.logic.Backup
import com.campusquest.app.logic.fmtDue
import com.campusquest.app.theme.LocalCqColors
import com.campusquest.app.vm.AppViewModel
import com.campusquest.app.widget.Widget2x1
import com.campusquest.app.widget.WidgetTheme
import com.campusquest.app.widget.WidgetUpdater
import com.campusquest.app.widget.Widget2x2
import com.campusquest.app.widget.Widget4x1
import com.campusquest.app.widget.Widget4x2
import com.campusquest.app.widget.Widget4x4
import com.campusquest.app.widget.Widget5x4
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Composable
fun Mask(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val c = LocalCqColors.current
    Box(Modifier.fillMaxSize().background(c.mask).clickable(onClick = onDismiss)) {
        content()
    }
}

@Composable
fun PrimaryBtn(text: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val c = LocalCqColors.current
    Box(
        modifier.clip(RoundedCornerShape(99.dp))
            .background(if (danger) c.danger else c.accent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            color = if (c.isDark && !danger) Color(0xFF0B0B12) else Color.White)
    }
}

@Composable
fun GhostBtn(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalCqColors.current
    Box(
        modifier.clip(RoundedCornerShape(99.dp))
            .background(c.cellLine)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.ink2) }
}

@Composable
fun ConfirmDialog(task: TaskItem, vm: AppViewModel, onClose: () -> Unit) {
    val c = LocalCqColors.current
    Mask(onDismiss = onClose) {
        Column(
            Modifier.align(Alignment.Center)
                .padding(horizontal = 36.dp)
                .shadow(20.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(c.sheet)
                .border(1.dp, c.panelBorder, RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}
                .padding(22.dp)
        ) {
            Text("确认完成这项任务?", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.ink)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.accentSoft).padding(12.dp)) {
                Text(task.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.ink)
                Text("截止:${fmtDue(vm.dueOf(task))}", fontSize = 11.sp, color = c.ink3, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostBtn("再想想", Modifier.weight(1f), onClose)
                PrimaryBtn("完成 ✓", Modifier.weight(1f)) { vm.completeTask(task); onClose() }
            }
        }
    }
}

@Composable
fun FieldLabel(text: String) {
    val c = LocalCqColors.current
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.ink3, letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
fun CqTextField(
    value: String, onChange: (String) -> Unit, placeholder: String,
    singleLine: Boolean = true, minLines: Int = 1,
) {
    val c = LocalCqColors.current
    val density = LocalDensity.current
    val padH = with(density) { 12.dp.roundToPx() }
    val padV = with(density) { 10.dp.roundToPx() }
    val radius = with(density) { 10.dp.toPx() }
    val strokeW = with(density) { 1.dp.roundToPx() }
    val ml = minLines
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            android.widget.EditText(ctx).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setSingleLine(singleLine)
                if (!singleLine) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    setMinLines(ml)
                    maxLines = 6
                } else {
                    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                }
                setPadding(padH, padV, padH, padV)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(c.card.toArgb())
                    setStroke(strokeW, c.panelBorder.toArgb())
                }
                // 原生焦点路径:获焦即弹键盘(平台 EditText 在任何 ROM 上行为一致)
                setOnClickListener { KeyboardUtil.show(it) }
                setOnFocusChangeListener { v, hasFocus -> if (hasFocus) KeyboardUtil.show(v) }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        onChange(s?.toString() ?: "")
                    }
                })
            }
        },
        update = { et ->
            // 外部状态 → 控件(相等时跳过,防光标跳动)
            if (et.text.toString() != value) et.setText(value)
            et.hint = placeholder
            et.setTextColor(c.ink.toArgb())
            et.setHintTextColor(c.ink3.toArgb())
            (et.background as? android.graphics.drawable.GradientDrawable)?.apply {
                setColor(c.card.toArgb())
                setStroke(strokeW, c.panelBorder.toArgb())
            }
        },
    )
}

@Composable
fun AddDialog(course: String?, vm: AppViewModel, onClose: () -> Unit) {
    val c = LocalCqColors.current
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<LocalDateTime?>(null) }
    val isHw = course != null

    fun pickDate(set: (LocalDateTime) -> Unit) {
        val now = LocalDateTime.now().plusDays(1)
        DatePickerDialog(ctx, { _, y, mo, d ->
            TimePickerDialog(ctx, { _, h, mi -> set(LocalDateTime.of(y, mo + 1, d, h, mi)) },
                23, 59, true).show()
        }, now.year, now.monthValue - 1, now.dayOfMonth).show()
    }

    Mask(onDismiss = onClose) {
        Column(
            Modifier.align(Alignment.Center)
                .imePadding()
                .padding(horizontal = 28.dp)
                .shadow(20.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(c.sheet)
                .border(1.dp, c.panelBorder, RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}
                .padding(20.dp)
        ) {
            Text(if (isHw) "新增作业任务" else "新增支线任务", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.ink)
            if (isHw) Text("归属课程:$course", fontSize = 12.sp, color = c.accent, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            FieldLabel("标题")
            CqTextField(title, { title = it }, "任务标题")
            Spacer(Modifier.height(12.dp))
            FieldLabel(if (isHw) "小记(保存后不再可改)" else "小记(可选,保存后不再可改)")
            CqTextField(note, { note = it }, "备注…", singleLine = false, minLines = 3)
            Spacer(Modifier.height(12.dp))
            FieldLabel(if (isHw) "截止时间" else "截止时间(可选)")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(c.card).border(1.dp, c.panelBorder, RoundedCornerShape(10.dp))
                        .clickable { pickDate { due = it } }.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(due?.let { fmtDue(it) } ?: "点击选择…", fontSize = 13.sp, color = if (due == null) c.ink3 else c.ink)
                }
                if (!isHw && due != null) {
                    GhostBtn("清除", Modifier.width(64.dp).padding(0.dp)) { due = null }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostBtn("取消", Modifier.weight(1f), onClose)
                PrimaryBtn("确认添加", Modifier.weight(1f)) {
                    if (title.isBlank()) { vm.toastMsg("标题必填"); return@PrimaryBtn }
                    if (isHw) {
                        if (due == null) { vm.toastMsg("请选择截止时间"); return@PrimaryBtn }
                        vm.addHw(course!!, title.trim(), note.trim(), due!!)
                    } else vm.addRand(title.trim(), note.trim(), due)
                    onClose()
                }
            }
        }
    }
}

@Composable
fun SettingsSheet(vm: AppViewModel, onClose: () -> Unit) {
    val c = LocalCqColors.current
    val ctx = LocalContext.current
    val start by vm.semesterStart.collectAsState()
    val weeks by vm.totalWeeks.collectAsState()
    val city by vm.city.collectAsState()
    var cityTxt by remember { mutableStateOf("") }
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showSnaps by remember { mutableStateOf(false) }
    var snaps by remember { mutableStateOf<List<Backup.Snap>>(emptyList()) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { vm.exportTo(it) } }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> pendingUri = uri }
    val snapTick by vm.snapshotTick.collectAsState()
    LaunchedEffect(snapTick) { snaps = vm.snapshots() }

    Mask(onDismiss = onClose) {
        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .shadow(20.dp, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .background(c.sheet)
                .border(1.dp, c.panelBorder, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .imePadding()
                .navigationBarsPadding()
        ) {
            Text("校历设置", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.ink)
            Spacer(Modifier.height(14.dp))
            FieldLabel("开学日期(第一周周一)")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(c.card).border(1.dp, c.panelBorder, RoundedCornerShape(10.dp))
                        .clickable {
                            val d = LocalDate.parse(start)
                            DatePickerDialog(ctx, { _, y, mo, day ->
                                val sel = LocalDate.of(y, mo + 1, day)
                                val monday = sel.minusDays((sel.dayOfWeek.value - 1).toLong())
                                vm.setStart(monday.toString())
                            }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                        }.padding(horizontal = 12.dp, vertical = 10.dp)
                ) { Text(start, fontSize = 13.sp, color = c.ink) }
                Text("学期总周数", fontSize = 11.sp, color = c.ink3)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("−", fontSize = 16.sp, color = c.ink2, modifier = Modifier
                        .clip(RoundedCornerShape(8.dp)).clickable { vm.setWeeks((weeks - 1).coerceAtLeast(1)) }
                        .padding(horizontal = 10.dp, vertical = 4.dp))
                    Text("$weeks", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.ink,
                        modifier = Modifier.padding(horizontal = 8.dp))
                    Text("＋", fontSize = 15.sp, color = c.ink2, modifier = Modifier
                        .clip(RoundedCornerShape(8.dp)).clickable { vm.setWeeks((weeks + 1).coerceAtMost(30)) }
                        .padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            FieldLabel("天气城市(留空=自动定位)")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { CqTextField(cityTxt, { cityTxt = it }, "如:长沙") }
                GhostBtn("保存城市", Modifier.width(90.dp)) { vm.setCity(cityTxt.trim()) }
                GhostBtn("刷新", Modifier.width(64.dp)) { vm.refreshWeather(); vm.toastMsg("天气已刷新") }
            }
            if (city.isNotBlank()) Text("当前:$city", fontSize = 11.sp, color = c.ink3, modifier = Modifier.padding(top = 6.dp))

            Spacer(Modifier.height(16.dp))
            FieldLabel("数据备份")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostBtn("导出备份", Modifier.weight(1f)) { saveLauncher.launch(Backup.suggestFileName()) }
                GhostBtn("从备份恢复", Modifier.weight(1f)) { openLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostBtn("立即快照", Modifier.weight(1f)) { vm.makeSnapshot() }
                GhostBtn("恢复快照" + if (snaps.isNotEmpty()) "(${snaps.size})" else "", Modifier.weight(1f)) { showSnaps = true }
            }
            Text(
                "每天自动留一份快照(最多 7 份,存在手机本地)。换手机或重装系统请先「导出备份」存成文件。",
                fontSize = 10.5.sp, color = c.ink3, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(16.dp))
            FieldLabel("小组件外观")
            var wStyle by remember { mutableStateOf(WidgetTheme.style(ctx)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetTheme.ALL.forEachIndexed { i, key ->
                    val on = wStyle == key
                    Box(
                        Modifier.weight(1f).height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) c.accent else c.cellLine)
                            .clickable {
                                wStyle = key
                                WidgetTheme.setStyle(ctx, key)
                                WidgetUpdater.refresh(ctx)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            WidgetTheme.LABELS[i], fontSize = 11.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) (if (c.isDark) Color(0xFF0B0B12) else Color.White) else c.ink2
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            FieldLabel("桌面小组件")
            val mgr = remember { AppWidgetManager.getInstance(ctx) }
            val canPin = remember { runCatching { mgr.isRequestPinAppWidgetSupported }.getOrDefault(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PinChip("2×1", Widget2x1::class.java, canPin, Modifier.weight(1f))
                PinChip("2×2", Widget2x2::class.java, canPin, Modifier.weight(1f))
                PinChip("4×1", Widget4x1::class.java, canPin, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PinChip("4×2", Widget4x2::class.java, canPin, Modifier.weight(1f))
                PinChip("4×4", Widget4x4::class.java, canPin, Modifier.weight(1f))
                PinChip("5×4", Widget5x4::class.java, canPin, Modifier.weight(1f))
            }
            Text(
                if (canPin) "点一下挑尺寸,系统会问要不要放到桌面。待办优先、课表次之;点圆圈直接完成。"
                else "这台手机的桌面不支持从应用内一键添加,请长按桌面空白处 → 小组件 → CampusQuest。",
                fontSize = 10.5.sp, color = c.ink3, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(16.dp))
            PrimaryBtn("完成", Modifier.fillMaxWidth(), onClick = onClose)
        }
    }

    // ---- 导入前确认(会整体覆盖) ----
    if (pendingUri != null) {
        Dialog(onDismissRequest = { pendingUri = null }) {
            Column(
                Modifier.fillMaxWidth().shadow(24.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp)).background(c.sheet)
                    .border(1.dp, c.panelBorder, RoundedCornerShape(24.dp)).padding(20.dp)
            ) {
                Text("用这份备份覆盖当前数据?", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.ink)
                Spacer(Modifier.height(8.dp))
                Text("现在课程与任务会被备份里的内容整体替换。覆盖前会自动给当前状态存一份快照,后悔还能退回来。",
                    fontSize = 12.5.sp, color = c.ink2, lineHeight = 19.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostBtn("再想想", Modifier.weight(1f)) { pendingUri = null }
                    PrimaryBtn("覆盖恢复", Modifier.weight(1f)) {
                        pendingUri?.let { vm.importFrom(it) }
                        pendingUri = null
                    }
                }
            }
        }
    }

    // ---- 快照列表 ----
    if (showSnaps) {
        Dialog(onDismissRequest = { showSnaps = false }) {
            Column(
                Modifier.fillMaxWidth().shadow(24.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp)).background(c.sheet)
                    .border(1.dp, c.panelBorder, RoundedCornerShape(24.dp)).padding(20.dp)
            ) {
                Text("恢复快照", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.ink)
                Spacer(Modifier.height(4.dp))
                Text("每天首次打开时自动存一份,最多 7 份。", fontSize = 11.sp, color = c.ink3)
                Spacer(Modifier.height(12.dp))
                if (snaps.isEmpty()) {
                    Text("还没有快照", fontSize = 12.5.sp, color = c.ink3, modifier = Modifier.padding(vertical = 14.dp))
                } else {
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        snaps.forEach { s ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(10.dp)).background(c.card)
                                    .border(1.dp, c.panelBorder, RoundedCornerShape(10.dp))
                                    .clickable { vm.restoreSnapshot(s); showSnaps = false }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(Backup.fmtSnap(s.ts), fontSize = 13.sp, color = c.ink)
                                    Text("${s.courses} 门课 · ${s.tasks} 条任务", fontSize = 10.5.sp, color = c.ink3)
                                }
                                Text("恢复", fontSize = 11.5.sp, color = c.accent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                GhostBtn("关闭", Modifier.fillMaxWidth()) { showSnaps = false }
            }
        }
    }
}
/**
 * 「添加到桌面」胶囊:调系统的 requestPinAppWidget(API 26+),
 * 用户确认后桌面就会多一个对应尺寸的小组件。
 */
@Composable
private fun PinChip(
    label: String,
    provider: Class<out AppWidgetProvider>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = LocalCqColors.current
    val ctx = LocalContext.current
    Text(
        label,
        fontSize = 11.5.sp,
        color = if (enabled) c.accent else c.ink3,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) c.accentSoft else c.cellLine)
            .clickable(enabled = enabled) {
                runCatching {
                    AppWidgetManager.getInstance(ctx)
                        .requestPinAppWidget(ComponentName(ctx, provider), null, null)
                }
            }
            .padding(vertical = 9.dp),
    )
}