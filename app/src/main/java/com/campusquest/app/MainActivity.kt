package com.campusquest.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campusquest.app.theme.CqTheme
import com.campusquest.app.ui.AppRoot
import com.campusquest.app.vm.AppViewModel
import com.campusquest.app.widget.Widget2x1
import com.campusquest.app.widget.Widget2x2
import com.campusquest.app.widget.Widget4x1
import com.campusquest.app.widget.Widget4x2
import com.campusquest.app.widget.Widget4x4
import com.campusquest.app.widget.Widget5x4
import com.campusquest.app.widget.WidgetUpdater

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 点桌面小组件进来:tab=hw/rand 直接切到对应页签
        handleIntent(intent)
        setContent {
            val vm: AppViewModel = viewModel()
            val darkPref by vm.dark.collectAsState()
            // 主题:手动选择优先;未手动选择时按时间自动——18:00-次日6:00 深色,6:00-18:00 浅色
            val dark = darkPref ?: run {
                val h = java.time.LocalTime.now().hour
                h >= 18 || h < 6
            }
            // 每次回到前台刷新一次桌面小组件(数据可能在别处变过)
            LaunchedEffect(Unit) { WidgetUpdater.refresh(this@MainActivity) }
            CqTheme(dark = dark) {
                AppRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        WidgetUpdater.refresh(this)
    }

    /**
     * 两个额外参数:
     *  tab=hw|rand —— 小组件点进来直达页签(AppViewModel 在 setContent 里才起来,这里先存着)
     *  pin=2x1|2x2|4x1|4x2|4x4|5x4 —— 自动化测试用:直接请求把某个尺寸钉到桌面
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra("tab")?.let { pendingTab = it }
        intent.getStringExtra("pin")?.let { pinWidget(it) }
    }

    private fun pinWidget(name: String) {
        val cls = when (name) {
            "2x1" -> Widget2x1::class.java
            "2x2" -> Widget2x2::class.java
            "4x1" -> Widget4x1::class.java
            "4x2" -> Widget4x2::class.java
            "4x4" -> Widget4x4::class.java
            "5x4" -> Widget5x4::class.java
            else -> return
        }
        runCatching {
            AppWidgetManager.getInstance(this)
                .requestPinAppWidget(ComponentName(this, cls), null, null)
        }
    }

    companion object {
        /** 冷启动时 setContent 之前拿到的页签,交给 AppRoot 消费一次 */
        var pendingTab: String? = null
    }
}
