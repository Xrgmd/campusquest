package com.campusquest.app.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.campusquest.app.data.TaskItem
import com.campusquest.app.logic.cdState
import com.campusquest.app.logic.fmtDue
import com.campusquest.app.theme.LocalCqColors
import com.campusquest.app.vm.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.abs

/** 把半透明色 fg 叠在不透明底色 bg 上,算出一个等价的不透明色。
 *  拖动时用它当底:避免半透明卡片把下层文字透上来(看起来像重影乱字)。 */
private fun solidOver(fg: Color, bg: Color): Color = Color(
    red = fg.red * fg.alpha + bg.red * (1f - fg.alpha),
    green = fg.green * fg.alpha + bg.green * (1f - fg.alpha),
    blue = fg.blue * fg.alpha + bg.blue * (1f - fg.alpha),
    alpha = 1f,
)

@Composable
fun TasksPanel(
    vm: AppViewModel,
    actPopFor: MutableState<Long?>,
    onAddHw: (String) -> Unit,
    onConfirm: (TaskItem) -> Unit,
) {
    val c = LocalCqColors.current
    val tab by vm.tab.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val courses by vm.courses.collectAsState()
    val collapsed by vm.collapsed.collectAsState()
    val doneOpen by vm.doneOpen.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            // 同课表面板:半透明面板 + 投影 = 内部透出一圈灰框,所以不再加投影
            .clip(RoundedCornerShape(26.dp))
            .background(c.panel)
            .border(1.dp, c.panelBorder, RoundedCornerShape(26.dp))
            .padding(14.dp)
    ) {
        // 分段控件
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.cellLine).padding(3.dp)
        ) {
            listOf("hw" to "课程作业", "rand" to "支线任务").forEach { (k, label) ->
                val sel = tab == k
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                        .background(if (sel) c.card else Color.Transparent)
                        .clickable { vm.setTab(k) }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (sel) c.ink else c.ink2)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (tab == "hw") {
            HwTab(vm, tasks.filter { it.kind == "hw" }, courses, collapsed, doneOpen, actPopFor, onAddHw, onConfirm)
        } else {
            RandTab(vm, tasks.filter { it.kind == "rand" }, doneOpen, actPopFor, onConfirm)
        }
    }
}

/** 上下拖拽排序的共用状态机。
 *
 *  三个要点(对应 0.3.1 修的两个 bug):
 *  ① 手势一帧可能跨好几格:用本地 session 列表迭代换位,能连续上下挪,不再「只挪一位」;
 *  ② 邻居被挤走时先给一个反向位移再弹回(spring),这就是苹果那种跟手又跟不上的滑动感;
 *  ③ 松手时拖拽项从当前位置弹回槽位(而不是瞬间归零,瞬间归零看起来就是 UI 重叠)。
 *
 *  阈值:两项中心距 = (hA + hB)/2 + 间距,越过即换位 —— 等价于「重合超过 50% 就换」。 */
private class Reorder<K : Any>(
    private val gapPx: Float,
    private val heights: MutableMap<K, Int>,
    private val shifts: MutableMap<K, MutableFloatState>,
    private val onMove: (K, Int) -> Unit,
    private val haptic: HapticFeedback,
) {
    var active by mutableStateOf<K?>(null)
        private set
    var dy by mutableFloatStateOf(0f)
        private set
    /** 松手后的归位动画(与 dy 相加渲染) */
    var settle by mutableFloatStateOf(0f)
        private set
    private var session: MutableList<K>? = null
    private var scope: CoroutineScope? = null
    private val jobs = HashMap<K, Job>()
    private var settleJob: Job? = null

    fun begin(scope: CoroutineScope, k: K, order: List<K>) {
        this.scope = scope
        settleJob?.cancel()
        settle = 0f
        dy = 0f
        active = k
        session = order.toMutableList()
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun drag(k: K, delta: Float) {
        val list = session ?: return
        dy += delta
        val h = heights[k] ?: return
        if (h <= 0) return
        var guard = 0
        while (guard++ < 24) {
            val i = list.indexOf(k)
            if (i < 0) break
            // 向下:越过下一项的中心距就换位,并把自身偏移扣掉这一格
            if (i < list.size - 1) {
                val nb = list[i + 1]
                val nh = heights[nb] ?: h
                if (dy > (h + nh) * 0.5f + gapPx) {
                    list.removeAt(i)
                    list.add(i + 1, k)
                    onMove(k, 1)
                    dy -= nh + gapPx
                    nudge(nb, h + gapPx)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    continue
                }
            }
            // 向上
            if (i > 0) {
                val pb = list[i - 1]
                val ph = heights[pb] ?: h
                if (dy < -((h + ph) * 0.5f + gapPx)) {
                    list.removeAt(i)
                    list.add(i - 1, k)
                    onMove(k, -1)
                    dy += ph + gapPx
                    nudge(pb, -(h + gapPx))
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    continue
                }
            }
            break
        }
    }

    fun finish() {
        val k = active ?: return
        session = null
        val leftover = dy
        dy = 0f
        val s = scope
        if (abs(leftover) < 1f || s == null) {
            settle = 0f
            active = null
            return
        }
        // 同步接住当前位置再弹回:若等进了协程才 snapTo,会先闪一帧「已经跳到新槽位」
        settle = leftover
        settleJob?.cancel()
        settleJob = s.launch {
            animate(settle, 0f, animationSpec = spring(dampingRatio = 0.86f, stiffness = 460f)) { v, _ ->
                settle = v
            }
            if (active == k) active = null
        }
    }

    /** 被挤走的邻居:先钉在原地(同步生效),再滑到自己的新槽位 */
    private fun nudge(k: K, from: Float) {
        val st = shifts[k] ?: return
        val s = scope ?: return
        st.floatValue = from
        jobs[k]?.cancel()
        jobs[k] = s.launch {
            animate(st.floatValue, 0f, animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f)) { v, _ ->
                st.floatValue = v
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HwTab(
    vm: AppViewModel, hw: List<TaskItem>, courses: List<com.campusquest.app.data.CourseEntry>,
    collapsed: Set<String>, doneOpen: Set<String>, actPopFor: MutableState<Long?>,
    onAddHw: (String) -> Unit, onConfirm: (TaskItem) -> Unit,
) {
    val c = LocalCqColors.current
    val order by vm.courseOrder.collectAsState()
    val metas = remember(courses, hw, order) { vm.courseMeta(courses, hw, order) }
    val byCourse = remember(hw) { hw.groupBy { it.course } }

    // 拖动排序:握把直接拖,或长按组标题后上下拖
    val gap = 16.dp
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    val heights = remember { HashMap<String, Int>() }
    val shifts = remember { HashMap<String, MutableFloatState>() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val reorder = remember {
        Reorder<String>(gapPx, heights, shifts, { name, d -> vm.moveCourse(name, d) }, haptic)
    }
    val liveMetas = rememberUpdatedState(metas)
    val orderNow: () -> List<String> = { liveMetas.value.map { it.name } }
    // 拖拽中的那一块必须是「实心」的:面板本身是半透明的,
    // 若拖起来还是半透明,底下的组标题会透上来,看起来就是一片重叠的乱字。
    val solid = remember(c.panel, c.bg) { solidOver(c.panel, c.bg) }
    val groupShape = remember { RoundedCornerShape(16.dp) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        metas.forEach { m ->
            val items = byCourse[m.name].orEmpty()
            val open = items.filter { !it.done }
            val done = items.filter { it.done }
            val isCollapsed = collapsed.contains("c:${m.name}")
            val dragging = reorder.active == m.name
            val shift = shifts.getOrPut(m.name) { mutableFloatStateOf(0f) }
            key(m.name) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (dragging) 2f else 0f)
                        // 一层常驻图层:位移/抬起只改属性,不增删修饰符节点
                        // (拖动中增删节点会掉帧、甚至闪出上一帧的残影)
                        .graphicsLayer {
                            translationY = if (dragging) reorder.dy + reorder.settle else shift.floatValue
                            shadowElevation = if (dragging) 16.dp.toPx() else 0f
                            shape = groupShape
                            clip = false
                        }
                        .background(if (dragging) solid else Color.Transparent, groupShape)
                        .onGloballyPositioned { heights[m.name] = it.size.height }
                ) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { vm.toggleCollapsed("c:${m.name}") }
                            .pointerInput(m.name) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { reorder.begin(scope, m.name, orderNow()) },
                                    onDragEnd = { reorder.finish() },
                                    onDragCancel = { reorder.finish() },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        reorder.drag(m.name, amount.y)
                                    },
                                )
                            }
                            .padding(vertical = 7.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 握把:按下即吃掉事件,可以直接拖(不会误触发折叠)
                        DragGrip(c.ink3, m.name, height = 30.dp,
                            onStart = { reorder.begin(scope, m.name, orderNow()) },
                            onDrag = { d -> reorder.drag(m.name, d) },
                            onEnd = { reorder.finish() })
                        Spacer(Modifier.width(3.dp))
                        Text(if (isCollapsed) "▸" else "▾", fontSize = 11.sp, color = c.ink2)
                        Spacer(Modifier.width(6.dp))
                        // 不再给这个小圆点加彩色投影:8dp 的节点挂 spotColor 阴影在部分机型上
                        // 会投出一块偏灰的色斑(用户反馈「背景上有个灰色色块随滑动变化」的来源之一)
                        Box(Modifier.size(8.dp).clip(CircleShape)
                            .background(c.course[(m.colorIdx - 1) % 7]))
                        Spacer(Modifier.width(8.dp))
                        Text(m.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.ink)
                        if (isCollapsed && open.isNotEmpty()) {
                            Spacer(Modifier.width(7.dp))
                            Box(Modifier.size(16.dp).clip(CircleShape).background(c.danger),
                                contentAlignment = Alignment.Center) {
                                Text("${open.size}", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        if (m.tea.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(m.tea, fontSize = 11.sp, color = c.ink3)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("＋ 添加", fontSize = 12.sp, color = c.accent, modifier = Modifier
                            .clip(RoundedCornerShape(99.dp)).border(1.dp, c.accent, RoundedCornerShape(99.dp))
                            .clickable { onAddHw(m.name) }.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                    if (!isCollapsed) {
                        // 这门课底下一张卡都没有:展开后给一行「无」,而不是一片空白
                        val nothing = open.isEmpty() && done.isEmpty()
                        if (nothing) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, top = 9.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(3.dp).clip(CircleShape).background(c.ink3.copy(alpha = .5f)))
                                Spacer(Modifier.width(8.dp))
                                Text("无", fontSize = 12.sp, color = c.ink3, letterSpacing = 1.sp)
                            }
                        }
                        open.let { vm.sortItems(it) }.forEach { t ->
                            key(t.id, t.pin) { TaskCard(vm, t, actPopFor, onConfirm) }
                        }
                        if (done.isNotEmpty()) {
                            DoneGroup(vm, "done:${m.name}", done, doneOpen, actPopFor)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RandTab(
    vm: AppViewModel, rand: List<TaskItem>, doneOpen: Set<String>,
    actPopFor: MutableState<Long?>, onConfirm: (TaskItem) -> Unit,
) {
    val c = LocalCqColors.current
    val order by vm.randOrder.collectAsState()
    val open = remember(rand, order) { vm.sortItems(rand.filter { !it.done }) }
    val done = rand.filter { it.done }

    // 卡片拖动排序:左侧握把上下拖(置顶项不参与,始终在最前)
    val gap = 12.dp
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    val heights = remember { HashMap<Long, Int>() }
    val shifts = remember { HashMap<Long, MutableFloatState>() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val reorder = remember {
        Reorder<Long>(gapPx, heights, shifts, { id, d -> vm.moveRand(id, d) }, haptic)
    }
    val liveOpen = rememberUpdatedState(open)
    val orderNow: () -> List<Long> = { liveOpen.value.filter { !it.pin }.map { it.id } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        open.forEach { t ->
            val dragging = reorder.active == t.id
            val shift = shifts.getOrPut(t.id) { mutableFloatStateOf(0f) }
            key(t.id, t.pin) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (dragging) 2f else 0f)
                        .onGloballyPositioned { heights[t.id] = it.size.height }
                ) {
                    TaskCard(
                        vm, t, actPopFor, onConfirm,
                        dragEnabled = !t.pin,
                        dragActive = dragging,
                        yOffset = { if (dragging) reorder.dy + reorder.settle else shift.floatValue },
                        onDragStart = { reorder.begin(scope, t.id, orderNow()) },
                        onDrag = { d -> reorder.drag(t.id, d) },
                        onDragEnd = { reorder.finish() },
                    )
                }
            }
        }
    }
    if (open.isEmpty()) {
        Text("暂无支线,点下方「新增」开启", fontSize = 12.sp, color = c.ink3,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    if (done.isNotEmpty()) DoneGroup(vm, "done:_rand", done, doneOpen, actPopFor)
}

@Composable
fun DoneGroup(vm: AppViewModel, key: String, done: List<TaskItem>, doneOpen: Set<String>, actPopFor: MutableState<Long?>) {
    val c = LocalCqColors.current
    val opened = doneOpen.contains(key)
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .clickable { vm.toggleDoneOpen(key) }.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (opened) "▾" else "▸", fontSize = 11.sp, color = c.ink3)
            Spacer(Modifier.width(6.dp))
            Text("已完成任务 (${done.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.ink3)
        }
        if (opened) done.forEach { t -> key(t.id) { TaskCard(vm, t, actPopFor, null) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskCard(
    vm: AppViewModel, t: TaskItem, actPopFor: MutableState<Long?>, onConfirm: ((TaskItem) -> Unit)?,
    dragEnabled: Boolean = false,
    dragActive: Boolean = false,
    yOffset: () -> Float = { 0f },
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    val c = LocalCqColors.current
    // 分钟粒度时钟:秒跳不触发卡片重组(滚动/弹窗不再被拖慢)
    val minute by vm.minute.collectAsState()
    val popped by remember(t.id) { derivedStateOf { actPopFor.value == t.id } }
    val cd = cdState(t.done, vm.dueOf(t), LocalDateTime.now())
    val cdColor = when (cd.level) { 3 -> c.ok; 2 -> c.danger; 1 -> c.warn; else -> c.ink }
    val dim = t.done
    val cardShape = remember { RoundedCornerShape(18.dp) }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            // 常驻图层:拖动位移与抬起阴影都在这里,不增删节点(不缩放,避免文字重新栅格化)
            .graphicsLayer {
                translationY = yOffset()
                shadowElevation = if (dragActive) 16.dp.toPx() else 0f
                shape = cardShape
                clip = false
            }
            // 常挂链上、靠 active 淡入淡出:取消置顶时不再整段增删修饰符节点(见 pinGlowBorder 注释)
            .pinGlowBorder(active = t.pin && !t.done)
            .clip(RoundedCornerShape(18.dp))
            // 拿起时换成实心底色:半透明卡片会透出下面的卡片文字,看起来像重影乱字
            .background(
                if (dragActive) solidOver(c.card, solidOver(c.panel, c.bg)) else c.card
            )
            .border(1.dp, c.cardBorder, RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = {
                    if (actPopFor.value == t.id) actPopFor.value = null
                },
                onLongClick = { actPopFor.value = if (actPopFor.value == t.id) null else t.id },
            )
            .padding(12.dp)
    ) {
        Row {
            if (dragEnabled) DragGrip(c.ink3, t.id, onStart = onDragStart, onDrag = onDrag, onEnd = onDragEnd)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // checkbox
                    Box(
                        Modifier.size(24.dp).clip(RoundedCornerShape(9.dp))
                            .background(if (t.done) c.ok else Color.Transparent)
                            .border(1.6.dp, if (t.done) c.ok else c.ink3, RoundedCornerShape(9.dp))
                            .clickable {
                                if (t.done) vm.uncompleteTask(t) else onConfirm?.invoke(t)
                            },
                        contentAlignment = Alignment.Center,
                    ) { if (t.done) Text("✓", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    Spacer(Modifier.width(10.dp))
                    Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = if (dim) c.ink3 else c.ink, modifier = Modifier.weight(1f))
                    Text(cd.txt, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cdColor,
                        modifier = Modifier.clip(RoundedCornerShape(99.dp))
                            .background(cdColor.copy(alpha = .14f))
                            .padding(horizontal = 8.dp, vertical = 3.dp))
                }
                // 题记(支线)
                if (t.kind == "rand" && !t.done) {
                    Row(Modifier.padding(start = 34.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        // 题记不再用 Serif + 斜体:中文衬线体在部分 ROM 上没有对应字形,
                        // 合成斜体 + 回退字体会把整行画成一团认不出的「乱码汉字」
                        // (用户反馈「支线板块上下滑动时中侧左边跳动乱码」最可能的来源)
                        Text("「 ${t.epic ?: ""} 」", fontSize = 12.5.sp, fontStyle = FontStyle.Normal,
                            fontFamily = FontFamily.Default, color = c.ink2, lineHeight = 22.sp,
                            modifier = Modifier.weight(1f))
                        Text("重铸", fontSize = 10.sp, color = c.accent, modifier = Modifier
                            .clip(RoundedCornerShape(99.dp)).clickable { vm.recastEpic(t) }
                            .padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                // 截止行
                Row(Modifier.padding(start = 34.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⏰", fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(fmtDue(vm.dueOf(t)), fontSize = 11.sp, color = c.ink3)
                }
                // 小记(只读)
                if (t.note.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 34.dp, top = 8.dp)
                            .clip(RoundedCornerShape(10.dp)).background(c.accentSoft)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text("小记", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = c.accent, letterSpacing = 2.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(t.note, fontSize = 12.sp, color = if (dim) c.ink3 else c.ink2, lineHeight = 18.sp)
                    }
                }
            }
        }
        // 长按小格子(置顶/删除;已完成仅删除)
        if (popped) {
            Column(
                Modifier.align(Alignment.TopEnd)
                    .shadow(12.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.sheet)
                    .border(1.dp, c.panelBorder, RoundedCornerShape(12.dp))
                    .width(96.dp)
            ) {
                if (!t.done) {
                    Text(if (t.pin) "取消置顶" else "置顶", fontSize = 12.sp, color = c.ink,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { vm.togglePin(t); actPopFor.value = null }
                            .padding(horizontal = 12.dp, vertical = 10.dp))
                }
                Text("删除", fontSize = 12.sp, color = c.danger,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { vm.deleteTask(t); actPopFor.value = null }
                        .padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
    }
}

/** 拖动握把(三个竖点)。
 *  这里手写手势而不用 detectDragGestures:必须在按下的一瞬间就把事件吃掉,
 *  否则外层 verticalScroll 会先把它判成滚动(实测握把拖不动、整页在滚)。 */
@Composable
private fun DragGrip(
    color: Color, dragKey: Any,
    height: androidx.compose.ui.unit.Dp = 44.dp,
    onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit,
) {
    Box(
        Modifier
            .width(14.dp)
            .height(height)
            .pointerInput(dragKey) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var started = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val dy = ch.positionChange().y
                        if (!started) {
                            if (abs(dy) < 1f) {
                                ch.consume()
                                continue
                            }
                            started = true
                            onStart()
                        }
                        ch.consume()
                        onDrag(dy)
                    }
                    if (started) onEnd()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.width(4.dp).height(20.dp)) {
            for (i in 0 until 3) {
                drawCircle(
                    color,
                    radius = 1.3.dp.toPx(),
                    center = Offset(size.width / 2f, 2.dp.toPx() + i * 7.dp.toPx()),
                )
            }
        }
    }
}
