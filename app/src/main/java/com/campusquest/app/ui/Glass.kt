package com.campusquest.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.campusquest.app.theme.CqColors
import com.campusquest.app.theme.LocalCqColors

/**
 * 背景层。
 *
 * 0.4.1 起刻意「变笨」:0.4.0 及更早用的是
 *   ① 一个 26 秒无限动画驱动整层 graphicsLayer 平移(每帧都在合成器里动全屏图层)
 *   ② 再叠一层 160px 噪点 ImageShader + BlendMode.Multiply/Overlay(高级混合模式,
 *      Skia 要整块离屏保存/回读),
 * 在部分机型(红魔 6 Pro / Adreno)上这两条组合起来会偶发把字形图集弄花 ——
 * 屏幕上就是一小块跳动的白色/灰色乱字。
 *
 * 现在:只有一次静态绘制(底层实色 + 几个径向渐变光斑),没有动画、没有 shader 平铺、
 * 没有混合模式。视觉差异只有噪点颗粒和极缓慢的漂移,视觉收益远小于稳定性收益。
 */
@Composable
fun BackgroundLayer() {
    val c = LocalCqColors.current
    Canvas(Modifier.fillMaxSize()) {
        val m = 40f
        drawRect(c.bg, topLeft = Offset(-m, -m), size = Size(size.width + m * 2, size.height + m * 2))
        if (!c.isDark) {
            fun wash(color: Color, r: Float, center: Offset) {
                drawCircle(
                    Brush.radialGradient(0f to color, 1f to color.copy(alpha = 0f), center = center, radius = r),
                    r, center,
                )
            }
            wash(Color(0xD9FFFFFF), size.minDimension * .95f, Offset(size.width * .5f, -size.height * .05f))
            wash(Color(0x29D6B27C), size.minDimension * .55f, Offset(size.width * .12f, size.height * .88f))
            wash(Color(0x1F7A8CBE), size.minDimension * .48f, Offset(size.width * .88f, size.height * .7f))
        } else {
            fun blob(color: Color, r: Float, center: Offset) {
                drawCircle(
                    Brush.radialGradient(0f to color, 1f to color.copy(alpha = 0f), center = center, radius = r),
                    r, center,
                )
            }
            blob(Color(0x3D7C3AED), size.minDimension * .42f, Offset(size.width * .18f, size.height * .12f))
            blob(Color(0x3306B6D4), size.minDimension * .36f, Offset(size.width * .86f, size.height * .22f))
            blob(Color(0x28DB2777), size.minDimension * .46f, Offset(size.width * .72f, size.height * .82f))
            blob(Color(0x1EF59E0B), size.minDimension * .32f, Offset(size.width * .12f, size.height * .74f))
        }
    }
}

/** 磨砂面板外观(半透明近似 backdrop blur) */
@Composable
fun glassPanel(colors: CqColors = LocalCqColors.current): Pair<Color, Color> =
    colors.panel to colors.panelBorder

/**
 * 置顶流动灯带边框。
 *
 * 0.4.1 关键的稳定性改动(「屏幕上一小块跳动的乱码」的元凶级改造):
 *  - 渐变的 Brush **只创建一次**(remember),动画只改画布平移量。
 *    以前是每帧用新的色标数组 `new Brush.horizontalGradient(stops)`,
 *    等于每帧新建一个 Skia shader —— Adreno 上长时间跑会把字形图集搞坏。
 *  - 仍然不用 BlendMode.Plus(加法混合会冲白白)。
 *  - 未激活时(live<=0.01)直接不画,连动画都不读,避免无意义的每帧失效。
 */
@Composable
fun Modifier.pinGlowBorder(corner: Dp = 18.dp, active: Boolean = true): Modifier {
    val c = LocalCqColors.current
    val palette = c.rgb
    // 静态色标:四色循环两遍,收尾回环,保证平移时无缝
    val stops = remember(palette) {
        val n = palette.size * 2
        Array(n + 1) { k ->
            val f = k.toFloat() / n
            (f) to palette[k % palette.size]
        }
    }
    val brush = remember(stops) { Brush.horizontalGradient(colorStops = stops) }
    val trans = rememberInfiniteTransition(label = "rgb")
    val p by trans.animateFloat(0f, 1f, infiniteRepeatable(tween(3500, easing = LinearEasing)), label = "rgb")
    val live by animateFloatAsState(if (active) 1f else 0f, tween(200, easing = FastOutSlowInEasing), label = "pinLive")
    return this.drawBehind {
        if (live <= 0.01f) return@drawBehind
        val w = size.width
        val shift = p * w
        // 画两遍,首尾相接,滚动时看不出接缝
        for (dx in floatArrayOf(0f, -w)) {
            translate(left = shift + dx) {
                drawRoundRect(
                    brush,
                    topLeft = Offset(-shift - dx, 0f),
                    size = Size(w, size.height),
                    cornerRadius = CornerRadius(corner.toPx()),
                    style = Stroke(1.6.dp.toPx()),
                    alpha = live,
                )
            }
        }
        drawRoundRect(
            c.pinGlow,
            cornerRadius = CornerRadius(corner.toPx() + 5.dp.toPx()),
            style = Stroke(9.dp.toPx()),
            alpha = live,
        )
    }
}
