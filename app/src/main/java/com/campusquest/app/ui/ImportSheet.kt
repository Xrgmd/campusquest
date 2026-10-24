package com.campusquest.app.ui

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.campusquest.app.logic.BANDS
import com.campusquest.app.logic.DAYCN
import com.campusquest.app.logic.Import
import com.campusquest.app.logic.ImportEntry
import com.campusquest.app.logic.parseWeeksStr
import com.campusquest.app.logic.weeksToText
import com.campusquest.app.theme.LocalCqColors
import com.campusquest.app.vm.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImportSheet(vm: AppViewModel, onClose: () -> Unit) {
    val c = LocalCqColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val totalWeeks = vm.totalWeeks.collectAsState().value
    var entries by remember { mutableStateOf<List<ImportEntry>?>(null) }
    var status by remember { mutableStateOf("支持 xls/xlsx 精确解析,或课表照片 OCR 识别") }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; status = "识别中…"
        scope.launch {
            try {
                val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                    val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cur.moveToFirst() && i >= 0) cur.getString(i) else ""
                } ?: ""
                val got = withContext(Dispatchers.IO) {
                    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val head = bytes.copyOfRange(0, minOf(8, bytes.size))
                    if (Import.isSheetBytes(head)) {
                        // 按内容识别为 xls/xlsx(选择器拿不到文件名也能正确分流)
                        if (name.endsWith(".csv", true)) throw IllegalArgumentException("CSV 请改存 xls/xlsx")
                        Import.parseWorkbook(ctx, bytes, totalWeeks)
                    } else {
                        Import.parsePhoto(ctx, uri, totalWeeks)
                    }
                }
                entries = got.sortedWith(compareBy({ it.day }, { it.band }))
                status = "识别完成,共 ${got.size} 条 · 请核对修正后应用"
            } catch (e: Exception) {
                status = "识别失败:${e.message ?: "未知错误"}(可换 xls 文件或重拍)"
            }
            busy = false
        }
    }

    Mask(onDismiss = onClose) {
        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .shadow(20.dp, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .background(c.sheet)
                .border(1.dp, c.panelBorder, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .clickable(enabled = false) {}
                .padding(20.dp)
                .imePadding()
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("导入课表", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.weight(1f))
                if (entries != null) GhostBtn("重新选择", Modifier.width(86.dp)) { entries = null; picker.launch("*/*") }
            }
            Spacer(Modifier.height(8.dp))
            Text(status, fontSize = 12.sp, color = c.ink2, lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))

            val list = entries
            if (list == null) {
                PrimaryBtn(if (busy) "识别中…" else "选择文件(xls / 照片)", Modifier.fillMaxWidth()) {
                    if (!busy) picker.launch("*/*")
                }
            } else {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    list.forEachIndexed { idx, e ->
                        ImportRow(e, c,
                            onChange = { ne -> entries = list.toMutableList().also { it[idx] = ne } },
                            onDelete = { entries = list.toMutableList().also { it.removeAt(idx) } })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostBtn("取消", Modifier.weight(1f), onClose)
                    PrimaryBtn("应用(${list.size} 条)", Modifier.weight(1f)) {
                        if (list.isEmpty()) { vm.toastMsg("列表为空"); return@PrimaryBtn }
                        vm.applyImported(list); onClose()
                    }
                }
            }
        }
    }
}

/** 单行编辑:原生 EditText(输入法绑定走系统原生路径,任何 ROM 可用) */
@Composable
private fun MiniField(
    value: String, onChange: (String) -> Unit,
    color: androidx.compose.ui.graphics.Color, textSize: Float,
    modifier: Modifier = Modifier, hint: String? = null,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.EditText(ctx).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize)
                setSingleLine(true)
                background = null
                setPadding(0, 0, 0, 0)
                setOnClickListener { KeyboardUtil.show(it) }
                setOnFocusChangeListener { v, hasFocus -> if (hasFocus) KeyboardUtil.show(v) }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { onChange(s?.toString() ?: "") }
                })
            }
        },
        update = { et ->
            if (et.text.toString() != value) et.setText(value)
            et.hint = hint
            et.setTextColor(color.toArgb())
            et.setHintTextColor(color.copy(alpha = 0.55f).toArgb())
        },
    )
}

@Composable
fun ImportRow(e: ImportEntry, c: com.campusquest.app.theme.CqColors,
              onChange: (ImportEntry) -> Unit, onDelete: () -> Unit) {
    var dayOpen by remember { mutableStateOf(false) }
    var bandOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp)).background(c.card)
            .border(1.dp, c.panelBorder, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                MiniField(
                    value = e.name, onChange = { onChange(e.copy(name = it)) },
                    color = c.ink, textSize = 13f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("✕", fontSize = 13.sp, color = c.danger, modifier = Modifier
                .clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete).padding(4.dp))
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // 星期
            Box {
                Text("周${DAYCN[e.day - 1]}", fontSize = 11.sp, color = c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.accentSoft)
                        .clickable { dayOpen = true }.padding(horizontal = 8.dp, vertical = 4.dp))
                androidx.compose.material3.DropdownMenu(expanded = dayOpen, onDismissRequest = { dayOpen = false },
                    modifier = Modifier.background(c.sheet)) {
                    DAYCN.forEachIndexed { i, d ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("周$d", color = c.ink, fontSize = 12.sp) },
                            onClick = { onChange(e.copy(day = i + 1)); dayOpen = false })
                    }
                }
            }
            // 节次
            Box {
                Text(BANDS[e.band].label, fontSize = 11.sp, color = c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.accentSoft)
                        .clickable { bandOpen = true }.padding(horizontal = 8.dp, vertical = 4.dp))
                androidx.compose.material3.DropdownMenu(expanded = bandOpen, onDismissRequest = { bandOpen = false },
                    modifier = Modifier.background(c.sheet)) {
                    BANDS.forEachIndexed { i, b ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(b.label, color = c.ink, fontSize = 12.sp) },
                            onClick = { onChange(e.copy(band = i)); bandOpen = false })
                    }
                }
            }
            // 周次
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 4.dp)) {
                MiniField(
                    value = weeksToText(e.weeks),
                    onChange = { s -> onChange(e.copy(weeks = parseWeeksStr(s))) },
                    color = c.accent, textSize = 11f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 教室
            Box(Modifier.width(84.dp).clip(RoundedCornerShape(8.dp)).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 4.dp)) {
                MiniField(
                    value = e.room,
                    onChange = { s -> onChange(e.copy(room = s)) },
                    color = c.accent, textSize = 11f,
                    modifier = Modifier.fillMaxWidth(),
                    hint = "教室",
                )
            }
        }
    }
}
