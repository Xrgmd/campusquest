package com.campusquest.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 6 个 provider 共用一个渲染逻辑,只是 kind 不同。
 * 每次刷新都从数据库重新取数 —— 不做本地缓存,避免「App 里改了、桌面不更新」。
 */
open class CqWidgetProvider : AppWidgetProvider() {
    open val kind: String get() = WidgetRender.K2x1

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.refresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager,
        appWidgetId: Int, newOptions: Bundle,
    ) {
        WidgetUpdater.refresh(context)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdater.refresh(context)
    }
}

class Widget2x1 : CqWidgetProvider() {
    override val kind get() = WidgetRender.K2x1
}

class Widget2x2 : CqWidgetProvider() {
    override val kind get() = WidgetRender.K2x2
}

class Widget4x1 : CqWidgetProvider() {
    override val kind get() = WidgetRender.K4x1
}

class Widget4x2 : CqWidgetProvider() {
    override val kind get() = WidgetRender.K4x2
}

class Widget4x4 : CqWidgetProvider() {
    override val kind get() = WidgetRender.K4x4
}

class Widget5x4 : CqWidgetProvider() {
    override val kind get() = WidgetRender.K5x4
}

object WidgetUpdater {

    /** 全部 6 种尺寸:class → kind */
    val all: List<Pair<Class<out CqWidgetProvider>, String>> = listOf(
        Widget2x1::class.java to WidgetRender.K2x1,
        Widget2x2::class.java to WidgetRender.K2x2,
        Widget4x1::class.java to WidgetRender.K4x1,
        Widget4x2::class.java to WidgetRender.K4x2,
        Widget4x4::class.java to WidgetRender.K4x4,
        Widget5x4::class.java to WidgetRender.K5x4,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** App 内数据一变就调它;桌面上的小组件会跟着重画 */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val snap = runCatching { WidgetData.build(app) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                val mgr = AppWidgetManager.getInstance(app)
                all.forEach { (cls, kind) ->
                    val ids = runCatching { mgr.getAppWidgetIds(ComponentName(app, cls)) }.getOrDefault(IntArray(0))
                    if (ids.isEmpty()) return@forEach
                    val rv = runCatching { WidgetRender.render(app, kind, snap) }.getOrNull() ?: return@forEach
                    ids.forEach { id -> runCatching { mgr.updateAppWidget(id, rv) } }
                }
            }
        }
    }
}
