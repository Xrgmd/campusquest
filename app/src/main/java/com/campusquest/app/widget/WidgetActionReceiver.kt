package com.campusquest.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.campusquest.app.data.AppDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 点小组件上的圆圈 → 直接把那条待办标成已完成,然后刷新桌面。 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetActions.ACTION_COMPLETE) return
        val id = intent.getLongExtra(WidgetActions.EXTRA_ID, -1L)
        if (id <= 0) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDb.get(app).taskDao()
                val t = dao.allNow().firstOrNull { it.id == id }
                if (t != null && !t.done) {
                    dao.update(t.copy(done = true, updatedAt = System.currentTimeMillis()))
                }
            } catch (_: Throwable) {
                // 单条失败不影响刷新
            } finally {
                WidgetUpdater.refresh(app)
                pending.finish()
            }
        }
    }
}
