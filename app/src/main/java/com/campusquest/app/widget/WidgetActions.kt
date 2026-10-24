package com.campusquest.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.campusquest.app.MainActivity

/** 小组件的点击行为:勾选完成 / 打开 App 到某个页签。 */
object WidgetActions {

    const val ACTION_COMPLETE = "com.campusquest.app.widget.COMPLETE"
    const val EXTRA_ID = "task_id"
    const val EXTRA_TAB = "tab"

    /** 点圆圈 = 直接完成这条待办(不需要进 App) */
    fun complete(ctx: Context, taskId: Long): PendingIntent {
        val i = Intent(ctx, WidgetActionReceiver::class.java).apply {
            action = ACTION_COMPLETE
            putExtra(EXTRA_ID, taskId)
            // 同一个 taskId 复用同一个 PendingIntent,点击后意图不重复投递
            data = android.net.Uri.parse("campusquest://complete/$taskId")
        }
        return PendingIntent.getBroadcast(
            ctx,
            (taskId and 0x7FFFFFFF).toInt(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 点非勾选区域 = 打开 App;tab = "hw" 课程作业 / "rand" 支线任务 */
    fun openApp(ctx: Context, tab: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (tab != null) putExtra(EXTRA_TAB, tab)
        }
        val code = 500 + (tab?.hashCode() ?: 0)
        return PendingIntent.getActivity(
            ctx, code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
