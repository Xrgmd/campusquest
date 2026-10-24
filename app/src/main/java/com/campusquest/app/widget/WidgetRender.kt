package com.campusquest.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.campusquest.app.MainActivity
import com.campusquest.app.R

/**
 * 把 [WidgetData.Snap] 渲染成 6 种尺寸的 RemoteViews。
 *
 * 为什么用 RemoteViews 而不是 Glance:
 *  1. 零新增依赖 —— Glance 1.1 会把 compose runtime 拉到 1.7,和现在的 BOM 2024.06(1.6)冲突;
 *     为了几个小组件去动整个 App 的 Compose 版本,风险不值得;
 *  2. 六种尺寸全是「文字 + 色块 + 细线」,RemoteViews 完全够用;
 *  3. 出问题好定位 —— 渲染结果就是 XML,没有额外框架层。
 * 代价:没有 backdrop blur / 渐变噪点 / 自定义字体,小组件用实色纸面 + 描边替代玻璃质感。
 */
object WidgetRender {

    const val K2x1 = "2x1"
    const val K2x2 = "2x2"
    const val K4x1 = "4x1"
    const val K4x2 = "4x2"
    const val K4x4 = "4x4"
    const val K5x4 = "5x4"

    fun layoutOf(kind: String): Int = when (kind) {
        K2x1 -> R.layout.widget_2x1
        K2x2 -> R.layout.widget_2x2
        K4x1 -> R.layout.widget_4x1
        K4x2 -> R.layout.widget_4x2
        K4x4 -> R.layout.widget_4x4
        else -> R.layout.widget_5x4
    }

    fun render(ctx: Context, kind: String, s: WidgetData.Snap): RemoteViews {
        val rv = RemoteViews(ctx.packageName, layoutOf(kind))
        // 主题要在渲染内容之前刷:之后的 render* 只会覆盖文字内容和 chip 颜色
        val style = WidgetTheme.style(ctx)
        if (style != WidgetTheme.AUTO) WidgetTheme.apply(ctx, rv, style, WidgetTheme.Ids(ctx), withCards = kind == K4x4)
        when (kind) {
            K2x1 -> render2x1(ctx, rv, s)
            K2x2 -> render2x2(ctx, rv, s)
            K4x1 -> render4x1(ctx, rv, s)
            K4x2 -> render4x2(ctx, rv, s)
            K4x4 -> render4x4(ctx, rv, s)
            else -> render5x4(ctx, rv, s)
        }
        rv.setOnClickPendingIntent(R.id.widget_root, WidgetActions.openApp(ctx, null))
        return rv
    }

    // ---------------- ① 2×1 ----------------

    private fun render2x1(ctx: Context, rv: RemoteViews, s: WidgetData.Snap) {
        val t = s.tasks.firstOrNull()
        if (t == null) {
            rv.setTextViewText(R.id.one_title, "今天没有待办")
            rv.setTextViewText(R.id.one_sub, s.classes.firstOrNull()?.let { "下一节 ${it.hhmm} ${it.name}" } ?: "点一下打开 App")
            rv.setViewVisibility(R.id.one_chip, View.GONE)
            rv.setViewVisibility(R.id.one_chk, View.GONE)
        } else {
            rv.setTextViewText(R.id.one_title, t.title)
            rv.setTextViewText(R.id.one_sub, t.sub)
            bindChip(ctx, rv, R.id.one_chip, t)
            rv.setOnClickPendingIntent(R.id.one_chk, WidgetActions.complete(ctx, t.id))
        }
        rv.setOnClickPendingIntent(R.id.one_row, WidgetActions.openApp(ctx, "rand"))
    }

    // ---------------- ② 2×2 ----------------

    private fun render2x2(ctx: Context, rv: RemoteViews, s: WidgetData.Snap) {
        rv.setTextViewText(R.id.hd, "待办 · ${s.pending}")
        rv.setTextViewText(R.id.hd_sub, s.weekday)
        if (s.hasTasks) {
            bindRows(ctx, rv, listOf("t1", "t2", "t3"), s.tasks.take(3))
        } else {
            hideRows(ctx, rv, listOf("t1", "t2", "t3"))
            rv.setViewVisibility(R.id.empty, View.VISIBLE)
        }
        if (s.nextName.isBlank()) {
            rv.setTextViewText(R.id.nx_time, "今天")
            rv.setTextViewText(R.id.nx_name, "没课了")
            rv.setTextViewText(R.id.nx_room, "")
        } else {
            rv.setTextViewText(R.id.nx_time, (if (s.nextDayLabel.isBlank()) "" else s.nextDayLabel + " ") + s.nextTime)
            rv.setTextViewText(R.id.nx_name, s.nextName)
            rv.setTextViewText(R.id.nx_room, s.nextRoom)
        }
        rv.setOnClickPendingIntent(R.id.nx_row, WidgetActions.openApp(ctx, "hw"))
    }

    // ---------------- ③ 4×1 ----------------

    private fun render4x1(ctx: Context, rv: RemoteViews, s: WidgetData.Snap) {
        rv.setTextViewText(R.id.date, s.dateLabel)
        rv.setTextViewText(R.id.date_sub, "第 ${s.week} 周 · ${s.weekday}")
        rv.setOnClickPendingIntent(R.id.date_box, WidgetActions.openApp(ctx, "hw"))

        val t = s.tasks.firstOrNull()
        if (t == null) {
            rv.setTextViewText(R.id.one_title, "今天没有待办")
            rv.setTextViewText(R.id.one_sub, if (s.classes.isEmpty()) "点一下打开 App" else "今天还有 ${s.classes.size} 节课")
            rv.setViewVisibility(R.id.one_chip, View.GONE)
            rv.setViewVisibility(R.id.one_chk, View.GONE)
        } else {
            rv.setTextViewText(R.id.one_title, t.title)
            val more = s.pending - 1
            rv.setTextViewText(R.id.one_sub, t.sub + if (more > 0) " · 还有 $more 件" else "")
            // 4×1 只有一行高:标题宽度优先。把「剩 17 时」这类胶囊让位给标题,
            // 紧急度改用左边圆圈的颜色表达(红=逾期/今天,橙=48h内,灰=其余)。
            rv.setViewVisibility(R.id.one_chip, View.GONE)
            rv.setTextColor(
                R.id.one_chk, ContextCompat.getColor(
                    ctx,
                    when (t.bucket) {
                        WidgetData.B_OVERDUE, WidgetData.B_TODAY -> R.color.cq_w_danger
                        WidgetData.B_SOON -> R.color.cq_w_warn
                        else -> R.color.cq_w_ink3
                    },
                ),
            )
            rv.setOnClickPendingIntent(R.id.one_chk, WidgetActions.complete(ctx, t.id))
        }
        if (s.nextName.isBlank()) {
            rv.setTextViewText(R.id.nx_time, "今天")
            rv.setTextViewText(R.id.nx_name, "没课了")
        } else {
            rv.setTextViewText(R.id.nx_time, s.nextDayLabel.ifBlank { "下一节" } + " " + s.nextTime)
            rv.setTextViewText(R.id.nx_name, s.nextName)
        }
        rv.setOnClickPendingIntent(R.id.nx_row, WidgetActions.openApp(ctx, "hw"))
    }

    // ---------------- ④ 4×2 ----------------

    private fun render4x2(ctx: Context, rv: RemoteViews, s: WidgetData.Snap) {
        rv.setTextViewText(R.id.hd, "待办 · ${s.pending}")
        rv.setTextViewText(R.id.hd_sub, "${s.weekday} ${s.dateLabel} · 第 ${s.week} 周")
        // 今天没课的时候,右列整块让给待办:标题能多显示一倍的字,也能多放一条
        val noCls = s.classes.isEmpty()
        if (noCls) {
            rv.setViewVisibility(R.id.cls_box, View.GONE)
            rv.setViewVisibility(R.id.col_div, View.GONE)
        } else {
            rv.setViewVisibility(R.id.cls_box, View.VISIBLE)
            rv.setViewVisibility(R.id.col_div, View.VISIBLE)
        }
        val rowIds = if (noCls) listOf("t1", "t2", "t3", "t4") else listOf("t1", "t2", "t3")
        if (!noCls) rv.setViewVisibility(R.id.t4_row, View.GONE)
        if (s.hasTasks) {
            bindRows(ctx, rv, rowIds, s.tasks.take(rowIds.size))
        } else {
            hideRows(ctx, rv, rowIds)
            rv.setViewVisibility(R.id.empty, View.VISIBLE)
        }
        rv.setTextViewText(R.id.cls_hd, if (noCls) "今天没课了" else "今天还要上")
        bindClasses(ctx, rv, listOf("c1", "c2", "c3", "c4"), s.classes.take(4))
        rv.setOnClickPendingIntent(R.id.cls_box, WidgetActions.openApp(ctx, "hw"))
    }

    // ---------------- ⑤ 4×4 ----------------

    private fun render4x4(ctx: Context, rv: RemoteViews, s: WidgetData.Snap) {
        rv.setTextViewText(R.id.hd, "待办 · ${s.pending}")
        rv.setTextViewText(R.id.hd_sub, "${s.weekday} ${s.dateLabel} · 第 ${s.week} 周")

        val a = s.groupA
        val b = s.groupB
        val c = s.groupC
        bindGroup(ctx, rv, "hA", listOf("A1", "A2", "A3"), "逾期 · ${a.size}", a)
        bindGroup(ctx, rv, "hB", listOf("B1", "B2", "B3"), "两天内 · ${b.size}", b)
        bindGroup(ctx, rv, "hC", listOf("C1", "C2", "C3"), if (c.any { it.bucket == WidgetData.B_LATER }) "本周 · ${c.size}" else "稍后 · ${c.size}", c)
        if (!s.hasTasks) {
            rv.setViewVisibility(R.id.hA, View.GONE)
            rv.setViewVisibility(R.id.hB, View.GONE)
            rv.setViewVisibility(R.id.hC, View.VISIBLE)
            rv.setTextViewText(R.id.hC, "今天没有待办")
        }

        rv.setTextViewText(R.id.cls_hd, if (s.classes.isEmpty()) "今日课表 · 今天没课了" else "今日课表")
        bindClasses(ctx, rv, listOf("c1", "c2", "c3"), s.classes.take(3))
        rv.setProgressBar(R.id.week_bar, s.totalWeeks, s.week.coerceIn(1, s.totalWeeks), false)
        rv.setTextViewText(R.id.week_lbl, "第 ${s.week} / ${s.totalWeeks} 周")
        rv.setOnClickPendingIntent(R.id.cls_box, WidgetActions.openApp(ctx, "hw"))
    }

    // ---------------- ⑥ 5×4 ----------------

    private fun render5x4(ctx: Context, rv: RemoteViews, s: WidgetData.Snap) {
        rv.setTextViewText(R.id.hd, "待办 · ${s.pending}")
        rv.setTextViewText(R.id.hd_sub, "${s.weekday} ${s.dateLabel} · 第 ${s.week} 周")
        if (s.hasTasks) {
            bindRows(ctx, rv, listOf("t1", "t2", "t3", "t4", "t5", "t6"), s.tasks.take(6))
        } else {
            hideRows(ctx, rv, listOf("t1", "t2", "t3", "t4", "t5", "t6"))
            rv.setViewVisibility(R.id.empty, View.VISIBLE)
        }
        rv.setTextViewText(R.id.cls_hd, if (s.classes.isEmpty()) "今天没课了" else "今天还要上")
        bindClasses(ctx, rv, listOf("c1", "c2", "c3", "c4"), s.classes.take(4))
        rv.setProgressBar(R.id.week_bar, s.totalWeeks, s.week.coerceIn(1, s.totalWeeks), false)
        rv.setTextViewText(R.id.week_lbl, "第 ${s.week} / ${s.totalWeeks} 周")
        rv.setOnClickPendingIntent(R.id.cls_box, WidgetActions.openApp(ctx, "hw"))
    }

    // ---------------- 绑定工具 ----------------

    private fun bindRows(ctx: Context, rv: RemoteViews, ids: List<String>, tasks: List<WidgetData.WTask>) {
        val wStyle = WidgetTheme.style(ctx)
        ids.forEachIndexed { i, p ->
            val t = tasks.getOrNull(i)
            if (t == null) {
                rv.setViewVisibility(id(ctx, "${p}_row"), View.GONE)
            } else {
                rv.setViewVisibility(id(ctx, "${p}_row"), View.VISIBLE)
                rv.setTextViewText(id(ctx, "${p}_title"), t.title)
                rv.setTextViewText(id(ctx, "${p}_sub"), t.sub)
                WidgetTheme.paintRow(ctx, rv, wStyle, id(ctx, "${p}_title"), id(ctx, "${p}_sub"))
                bindChip(ctx, rv, id(ctx, "${p}_chip"), t)
                rv.setOnClickPendingIntent(id(ctx, "${p}_chk"), WidgetActions.complete(ctx, t.id))
                rv.setOnClickPendingIntent(
                    id(ctx, "${p}_row"),
                    WidgetActions.openApp(ctx, if (t.kind == "rand") "rand" else "hw"),
                )
            }
        }
    }

    private fun hideRows(ctx: Context, rv: RemoteViews, ids: List<String>) {
        ids.forEach { p -> rv.setViewVisibility(id(ctx, "${p}_row"), View.GONE) }
    }

    private fun bindGroup(
        ctx: Context, rv: RemoteViews, hId: String, rows: List<String>,
        label: String, tasks: List<WidgetData.WTask>,
    ) {
        val hid = id(ctx, hId)
        if (tasks.isEmpty()) {
            rv.setViewVisibility(hid, View.GONE)
        } else {
            rv.setViewVisibility(hid, View.VISIBLE)
            rv.setTextViewText(hid, label)
        }
        val wStyle = WidgetTheme.style(ctx)
        rows.forEachIndexed { i, p ->
            val t = tasks.getOrNull(i)
            if (t == null) {
                rv.setViewVisibility(id(ctx, "${p}_row"), View.GONE)
            } else {
                rv.setViewVisibility(id(ctx, "${p}_row"), View.VISIBLE)
                rv.setTextViewText(id(ctx, "${p}_title"), t.title)
                rv.setTextViewText(id(ctx, "${p}_sub"), t.sub)
                WidgetTheme.paintRow(ctx, rv, wStyle, id(ctx, "${p}_title"), id(ctx, "${p}_sub"))
                bindChip(ctx, rv, id(ctx, "${p}_chip"), t)
                rv.setOnClickPendingIntent(id(ctx, "${p}_chk"), WidgetActions.complete(ctx, t.id))
                rv.setOnClickPendingIntent(
                    id(ctx, "${p}_row"),
                    WidgetActions.openApp(ctx, if (t.kind == "rand") "rand" else "hw"),
                )
            }
        }
    }

    private fun bindClasses(ctx: Context, rv: RemoteViews, ids: List<String>, list: List<WidgetData.WCourse>) {
        ids.forEachIndexed { i, p ->
            val cls = list.getOrNull(i)
            if (cls == null) {
                rv.setViewVisibility(id(ctx, "${p}_row"), View.GONE)
            } else {
                rv.setViewVisibility(id(ctx, "${p}_row"), View.VISIBLE)
                rv.setTextViewText(id(ctx, "${p}_time"), cls.hhmm)
                rv.setTextViewText(id(ctx, "${p}_name"), cls.name)
                rv.setTextViewText(id(ctx, "${p}_room"), cls.room)
                rv.setInt(id(ctx, "${p}_bar"), "setBackgroundResource", barRes(cls.colorIdx))
            }
        }
    }

    private fun bindChip(ctx: Context, rv: RemoteViews, viewId: Int, t: WidgetData.WTask) {
        rv.setTextViewText(viewId, t.chip)
        val styled = WidgetTheme.chipColor(ctx, WidgetTheme.style(ctx), t.bucket)
        if (styled != null) {
            // 非「跟随系统」主题:不贴胶囊底色(底色是资源里的固定色),只把文字染色
            rv.setInt(viewId, "setBackgroundResource", 0)
            rv.setTextColor(viewId, styled)
            return
        }
        when (t.bucket) {
            WidgetData.B_OVERDUE, WidgetData.B_TODAY -> {
                rv.setInt(viewId, "setBackgroundResource", R.drawable.w_chip_danger)
                rv.setTextColor(viewId, ContextCompat.getColor(ctx, R.color.cq_w_danger))
            }
            WidgetData.B_SOON -> {
                rv.setInt(viewId, "setBackgroundResource", R.drawable.w_chip_warn)
                rv.setTextColor(viewId, ContextCompat.getColor(ctx, R.color.cq_w_warn))
            }
            else -> {
                rv.setInt(viewId, "setBackgroundResource", R.drawable.w_chip_mute)
                rv.setTextColor(viewId, ContextCompat.getColor(ctx, R.color.cq_w_ink3))
            }
        }
    }

    private fun barRes(colorIdx: Int): Int = when (colorIdx) {
        1 -> R.drawable.w_bar_c1
        2 -> R.drawable.w_bar_c2
        3 -> R.drawable.w_bar_c3
        4 -> R.drawable.w_bar_c4
        5 -> R.drawable.w_bar_c5
        6 -> R.drawable.w_bar_c6
        else -> R.drawable.w_bar_c7
    }

    // id 解析:用 resources.getIdentifier 而不是反射 R.id,避免 R8 裁字段后找不到
    private val idCache = HashMap<String, Int>()
    private fun id(ctx: Context, name: String): Int = idCache.getOrPut(name) {
        ctx.resources.getIdentifier(name, "id", ctx.packageName)
    }

}
