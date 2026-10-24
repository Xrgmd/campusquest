package com.campusquest.app.ui

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager

/**
 * 键盘唤起(绑定优先):先让 Compose 输入框聚焦(建立输入会话),再向宿主 View 请求显示键盘。
 * 不使用 toggleSoftInput——它会弹出"不绑定任何输入框"的键盘,导致打字无处落。
 *  0) View 层聚焦(Compose 节点聚焦≠View 聚焦,多数 ROM 以此决定 IME 服务对象);
 *  1) IMM 标准请求;
 *  2) 200ms 重试;
 *  3) 450ms 后仍未接受输入 → SHOW_FORCED(仍携带 View,绑定不丢)。
 */
object KeyboardUtil {

    fun show(view: View) {
        if (!view.isFocused) view.requestFocus()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        view.postDelayed({
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
        view.postDelayed({
            runCatching { if (!imm.isAcceptingText) imm.showSoftInput(view, InputMethodManager.SHOW_FORCED) }
        }, 450)
    }

    /** 关闭键盘(弹层关闭时调用;原生 EditText 移除后系统通常也会自动收起,此处显式保险) */
    fun hide(view: View) {
        view.clearFocus()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
