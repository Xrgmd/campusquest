package com.campusquest.app.logic

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 用隐藏 WebView 执行 assets/parser.html 里的 SheetJS 课表解析(与 HTML 原型同一份已验证逻辑)。
 * 关键:必须等 onPageFinished(parser.html 及 xlsx.full.min.js 加载执行完毕)后再 evaluate,
 * 之前的竞态会让 CQ_PARSE_SHEET 未定义导致 xls 导入必失败。就绪前的请求进队列,就绪后依次执行。
 */
@SuppressLint("SetJavaScriptEnabled")
object SheetBridge {

    @Volatile private var web: WebView? = null
    @Volatile private var ready = false
    private val queue = ArrayDeque<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private fun obtain(ctx: Context): WebView {
        web?.let { return it }
        val wv = WebView(ctx.applicationContext)
        wv.settings.javaScriptEnabled = true
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                ready = true
                flush()
            }
        }
        wv.loadUrl("file:///android_asset/parser.html")
        web = wv
        return wv
    }

    private fun flush() {
        while (ready && queue.isNotEmpty()) queue.removeFirst().invoke()
    }

    /** 解析 xls/xlsx 字节,失败抛异常(错误信息来自 JS 侧) */
    suspend fun parseWorkbook(ctx: Context, bytes: ByteArray, totalWeeks: Int): List<ImportEntry> =
        withTimeout(30_000) {
            suspendCancellableCoroutine { cont ->
                val settle = { result: Result<List<ImportEntry>> ->
                    if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
                main.post {
                    try {
                        val wv = obtain(ctx)
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val job = {
                            wv.evaluateJavascript("CQ_PARSE_SHEET('$b64', $totalWeeks)") { raw ->
                                main.post {
                                    try {
                                        if (raw == null || raw == "null") throw IllegalStateException("解析器未就绪")
                                        // evaluateJavascript 对 JS 字符串返回 JSON 字面量,先去引号
                                        val json = JSONTokener(raw).nextValue().toString()
                                        val o = JSONObject(json)
                                        if (!o.optBoolean("ok")) throw IllegalArgumentException(o.optString("error", "解析失败"))
                                        val arr = o.getJSONArray("entries")
                                        val out = ArrayList<ImportEntry>(arr.length())
                                        for (i in 0 until arr.length()) {
                                            val e = arr.getJSONObject(i)
                                            out.add(ImportEntry(
                                                name = e.getString("n"),
                                                tea = e.optString("t"),
                                                weeks = parseWeeksStr(e.getString("w"), null, totalWeeks),
                                                room = e.optString("r"),
                                                day = e.getInt("d"),
                                                band = e.getInt("b"),
                                            ))
                                        }
                                        settle(Result.success(out))
                                    } catch (t: Throwable) {
                                        settle(Result.failure(t))
                                    }
                                }
                            }
                        }
                        if (ready) job() else { queue.addLast(job); flush() }
                    } catch (t: Throwable) {
                        settle(Result.failure(t))
                    }
                }
            }
        }
}
