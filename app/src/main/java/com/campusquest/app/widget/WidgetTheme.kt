package com.campusquest.app.widget

import android.content.Context
import android.content.SharedPreferences
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.campusquest.app.R

/**
 * 小组件的「外观主题」。
 *
 * 系统桌面小组件只能用 RemoteViews,颜色不能像 Compose 那样随手写,所以这里集中管:
 *  - 风格(style)存在独立的 SharedPreferences("cq_widget") 里,读写都是同步的,
 *    因为 WidgetProvider 的 onUpdate 是 BroadcastReceiver,不能等 DataStore 的挂起函数;
 *  - 每种风格给一套「背景 drawable + 文字色」,渲染时统一刷一遍;
 *  - auto = 跟随系统深色(values / values-night 里的 cq_w_* 资源,行为和 0.4.0 一致)。
 */
object WidgetTheme {

    const val AUTO = "auto"
    const val LIGHT = "light"
    const val DARK = "dark"
    const val RAINBOW = "rainbow"

    val ALL = listOf(AUTO, LIGHT, DARK, RAINBOW)
    val LABELS = listOf("跟随系统", "浅色", "深色", "彩虹")

    private const val PREF = "cq_widget"
    private const val KEY = "style"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun style(ctx: Context): String {
        val s = sp(ctx).getString(KEY, AUTO) ?: AUTO
        return if (s in ALL) s else AUTO
    }

    fun setStyle(ctx: Context, style: String) {
        sp(ctx).edit { putString(KEY, if (style in ALL) style else AUTO) }
    }

    private fun c(v: Long): Int = v.toInt()

    // 固定色板(不跟随系统):保证「选了深色就用深色」,哪怕系统是浅色
    private val LIGHT_INK = c(0xFF26221CL)
    private val LIGHT_INK2 = c(0xFF6F675CL)
    private val LIGHT_INK3 = c(0xFFA29A8DL)
    private val LIGHT_ACCENT = c(0xFF4A5AA8L)
    private val LIGHT_DANGER = c(0xFFB3402FL)
    private val LIGHT_WARN = c(0xFFA06A1CL)

    private val DARK_INK = c(0xFFEDEDF2L)
    private val DARK_INK2 = c(0xFFA7A7B4L)
    private val DARK_INK3 = c(0xFF8A8A99L)
    private val DARK_ACCENT = c(0xFF93A5FFL)
    private val DARK_DANGER = c(0xFFFC8181L)
    private val DARK_WARN = c(0xFFF6AD55L)

    // 彩虹版:亮底 + 偏冷紫的深墨色,比纯黑柔和,和渐变一起看更「新鲜」
    private val RB_INK = c(0xFF2C2338L)
    private val RB_INK2 = c(0xFF5E5370L)
    private val RB_INK3 = c(0xFF7D7391L)
    private val RB_ACCENT = c(0xFF3552B8L)
    private val RB_DANGER = c(0xFFB3325AL)
    private val RB_WARN = c(0xFF8A5A10L)

    private class Palette(
        val bgRes: Int?,
        val cardRes: Int?,
        val ink: Int?,
        val ink2: Int?,
        val ink3: Int?,
        val accent: Int?,
        val danger: Int?,
        val warn: Int?,
    )

    private fun palette(ctx: Context, style: String): Palette = when (style) {
        LIGHT -> Palette(R.drawable.w_bg_light, R.drawable.w_card_light, LIGHT_INK, LIGHT_INK2, LIGHT_INK3, LIGHT_ACCENT, LIGHT_DANGER, LIGHT_WARN)
        DARK -> Palette(R.drawable.w_bg_dark, R.drawable.w_card_dark, DARK_INK, DARK_INK2, DARK_INK3, DARK_ACCENT, DARK_DANGER, DARK_WARN)
        RAINBOW -> Palette(R.drawable.w_bg_rainbow, R.drawable.w_card_rainbow, RB_INK, RB_INK2, RB_INK3, RB_ACCENT, RB_DANGER, RB_WARN)
        else -> Palette(null, null, null, null, null, null, null, null)
    }

    /** 每种风格要刷的文字色:head / 次级 / 三级 / 强调 / 危险 / 提醒 */
    private val HEAD = listOf("hd", "date", "week_lbl", "one_title", "cls_hd")
    private val INK = buildList {
        add("one_title")
        (1..4).forEach { add("t${it}_title") }
        (1..4).forEach { add("c${it}_name") }
        listOf("A", "B", "C").forEach { g -> (1..3).forEach { add("${g}${it}_title") } }
    }
    private val INK2 = buildList {
        add("one_sub"); add("nx_name"); add("week_lbl")
        (1..4).forEach { add("t${it}_sub"); add("c${it}_room") }
        listOf("A", "B", "C").forEach { g -> (1..3).forEach { add("${g}${it}_sub") } }
    }
    private val INK3 = buildList {
        add("hd_sub"); add("date_sub"); add("empty"); add("cls_hd")
        (1..4).forEach { add("c${it}_time") }
    }

    /**
     * 把主题刷到 RemoteViews 上。auto 时什么都不做(XML 里的 @color/cq_w_* 已经跟随系统)。
     * 注意顺序:这里只设颜色/背景,不会覆盖 render*() 之后设置的内容,
     * 所以必须在渲染内容**之前**调用一次,渲染内容里再按 bucket 覆盖 chip 颜色。
     */
    fun apply(ctx: Context, rv: RemoteViews, style: String, ids: Ids, withCards: Boolean) {
        if (style == AUTO) return
        val p = palette(ctx, style)
        p.bgRes?.let { rv.setInt(ids.root, "setBackgroundResource", it) }
        if (withCards) p.cardRes?.let { res ->
            CLASS_CARDS.forEach { rv.setInt(ids[it], "setBackgroundResource", res) }
        }
        p.ink?.let { col -> INK.forEach { rv.setTextColor(ids[it], col) }; rv.setTextColor(ids["hd"], col) }
        p.ink2?.let { col -> INK2.forEach { rv.setTextColor(ids[it], col) } }
        p.ink3?.let { col -> INK3.forEach { rv.setTextColor(ids[it], col) } }
        p.accent?.let { col -> rv.setTextColor(ids["nx_time"], col) }
        p.ink?.let { col -> rv.setTextColor(ids["one_title"], col) }
    }

    // 只有 4×4 的课程卡是独立卡片(w_card),其它尺寸的 c*_row 是裸行,
    // 给裸行加背景会凭空多出三个方块,所以这里只刷 4×4 的三张卡。
    private val CLASS_CARDS = listOf("c1_row", "c2_row", "c3_row")

    /** 非 auto 时的 chip 文字色(null = 交给 XML/资源,保持 0.4.0 行为) */
    /** 逐行刷字色:主题切换时行可能多于 apply() 里写死的 id 列表,
     *  所以在真正绑定每一行数据时再刷一次,避免漏行的字色还是 XML 里的深色。 */
    fun paintRow(ctx: Context, rv: RemoteViews, style: String, titleId: Int, subId: Int) {
        if (style == AUTO) return
        val p = palette(ctx, style)
        p.ink?.let { rv.setTextColor(titleId, it) }
        p.ink3?.let { rv.setTextColor(subId, it) }
    }
    fun chipColor(ctx: Context, style: String, bucket: Int): Int? {
        if (style == AUTO) return null
        val p = palette(ctx, style)
        return when (bucket) {
            WidgetData.B_OVERDUE, WidgetData.B_TODAY -> p.danger ?: ContextCompat.getColor(ctx, R.color.cq_w_danger)
            WidgetData.B_SOON -> p.warn ?: ContextCompat.getColor(ctx, R.color.cq_w_warn)
            else -> p.ink3 ?: ContextCompat.getColor(ctx, R.color.cq_w_ink3)
        }
    }

    /** 通过 resources 解析 id(和 WidgetRender 里的做法一致,避免 R8 裁字段) */
    class Ids(private val ctx: Context) {
        private val cache = HashMap<String, Int>()
        operator fun get(name: String): Int = cache.getOrPut(name) {
            ctx.resources.getIdentifier(name, "id", ctx.packageName)
        }
        val root: Int get() = get("widget_root")
    }
}
