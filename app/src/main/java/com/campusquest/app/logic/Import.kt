package com.campusquest.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ImportEntry(
    var name: String, var tea: String, var weeks: List<IntArray>, var room: String,
    var day: Int, var band: Int,
)

object Import {

    /** OCR 易混字修复:<→C;座 被 OCR 成 E/量/酚/栓/… 时还原 */
    fun roomFix(ln: String): String {
        var s = ln.replace(Regex("[<＜]"), "C")
        s = s.replace(Regex("([A-Z])[量酚栓蛤棱枉星溺E](?=\\d)"), "$1座")
        s = s.replace(Regex("[量酚栓蛤棱枉星溺](?=\\d)"), "座")
        return s
    }

    private val TITLE_RE = Regex("(副教授|教授|讲师|助教|研究员|教师)")

    /**
     * 教师/姓名混排行拆分(照片 OCR 常把两行粘成一行、表格竖线被读成 |):
     * "刘丽珏(副教授)" → (null, 刘丽珏)
     * "人工智能基础及应用|刘丽珏[副教授]" → (人工智能基础及应用, 刘丽珏)
     * "复变函数与积分变换汪泓泽" → (复变函数与积分变换, 汪泓泽)
     */
    private fun splitTeacher(lnRaw: String): Pair<String?, String>? {
        val ln = lnRaw.trim()
        if (ln.length > 24 || !TITLE_RE.containsMatchIn(ln)) return null
        val s = ln.replace(Regex("[()（）\\[\\]〔〕\"\"'']"), "")
        val m = TITLE_RE.find(s) ?: return null
        val head = s.substring(0, m.range.first).trim()
        if (head.isEmpty()) return null
        val segs = head.split(Regex("[|｜│]")).map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return null
        var name: String? = null
        var tea: String? = null
        for (seg in segs) {
            if (tea == null && seg.length in 2..4 && !seg.any { it in '0'..'9' }) tea = seg
            else name = (name ?: "") + seg
        }
        if (tea == null) {
            val h = segs.joinToString("")
            if (h.length in 2..4) { tea = h }
            else if (h.length > 5) { tea = h.takeLast(3); name = (name ?: "") + h.dropLast(3) }
            else return null
        }
        return name to tea
    }

    /** 单元格行序列 → 课程条目(虚线/空行分块、(周)/(单周)、多教师),与原型 parseCellLines 对齐 */
    fun parseCellLines(lines: List<String>, totalWeeks: Int = DEFAULT_TOTAL_WEEKS): List<ImportEntry> {
        val entries = mutableListOf<ImportEntry>()
        var cur: ImportEntry? = null
        var hasWeeks = false
        fun close() {
            cur?.let {
                if (it.name.isNotEmpty() && !Regex("备注|周次|星期|点击查看").containsMatchIn(it.name)) entries.add(it)
            }
            cur = null; hasWeeks = false
        }
        lines.forEach { raw ->
            val ln = raw.trim()
            if (ln.isEmpty()) return@forEach
            if (Regex("^[-—－=――]{4,}$").matches(ln)) { close(); return@forEach }
            val nz = ln.replace(Regex("[\\[（()\\]\\)<＞>]"), "").replace(Regex("[同合]"), "周")
            val wmk = Regex("([\\d,\\-－~、]{1,})\\s*(单|双)?\\s*[^\\d]{0,2}周$").find(nz)
            if (wmk != null && cur != null && nz.length <= 20) {
                val par = when (wmk.groupValues[2]) { "单" -> 1; "双" -> 2; else -> null }
                cur!!.weeks = parseWeeksStr(wmk.groupValues[1], par, totalWeeks)
                hasWeeks = true
                return@forEach
            }
            if (cur != null && cur!!.name.isNotEmpty()) {
                val st = splitTeacher(ln)
                if (st != null) {
                    st.first?.let { cur!!.name += it }
                    cur!!.tea = st.second
                    return@forEach
                }
            }
            val rf = roomFix(ln)
            val rf2 = Regex("^([A-Z])座?(\\d{3,4})$").find(rf)?.let { "${it.groupValues[1]}座${it.groupValues[2]}" } ?: rf
            if (cur != null && cur!!.name.isNotEmpty() &&
                Regex("[座]\\s*\\d|楼\\s*\\d|教室|操场|场馆|^[A-Z]座?\\d{3,4}$").containsMatchIn(rf2) && rf2.length <= 14) {
                cur!!.room = rf2; return@forEach
            }
            if (cur != null && hasWeeks) close()
            val clean = ln.replace(Regex("^[。,.，、·\\s]+"), "")
            if (cur == null) {
                cur = ImportEntry(clean, "", listOf(intArrayOf(1, totalWeeks)), "", 1, 0)
            } else if (cur!!.name.isEmpty()) cur!!.name = clean
            else cur!!.name += clean
        }
        close()
        return entries
    }

    /** xls/xlsx 精确解析:由 SheetBridge(WebView 内 SheetJS)执行,与原型同一份已验证逻辑 */
    suspend fun parseWorkbook(ctx: Context, bytes: ByteArray, totalWeeks: Int = DEFAULT_TOTAL_WEEKS): List<ImportEntry> =
        SheetBridge.parseWorkbook(ctx, bytes, totalWeeks)

    /** 字节魔数嗅探:D0 CF 11 E0 = OLE2(.xls);PK = zip 容器(.xlsx);不再依赖文件名(选择器常拿不到) */
    fun isSheetBytes(head: ByteArray): Boolean {
        if (head.size >= 4) {
            val ole2 = head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() &&
                head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()
            val zip = head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
            if (ole2 || zip) return true
        }
        return false
    }

    /* ================= 照片 OCR 管道 ================= */

    data class Word(val t: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    /** 解码并降采样(最长边约 1600px,防大图 OOM/卡顿)+ 按 EXIF 方向矫正(相机照片常带旋转) */
    private fun decodeScaled(ctx: Context, uri: Uri, maxSide: Int = 1600): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("无法读取图片")
        var sample = 1
        var long = maxOf(bounds.outWidth, bounds.outHeight)
        while (long / 2 >= maxSide) { sample *= 2; long /= 2 }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("无法读取图片")
        val deg = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                when (androidx.exifinterface.media.ExifInterface(s).getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        return if (deg != 0f)
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                android.graphics.Matrix().apply { postRotate(deg) }, true)
        else raw
    }

    suspend fun ocrWords(ctx: Context, uri: Uri): Pair<Bitmap, List<Word>> = withContext(Dispatchers.IO) {
        val bmp = decodeScaled(ctx, uri)
        val image = InputImage.fromBitmap(bmp, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val text = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val words = mutableListOf<Word>()
        text.textBlocks.forEach { b -> b.lines.forEach { l -> l.elements.forEach { e ->
            e.boundingBox?.let { r ->
                if (e.text.isNotBlank()) words.add(Word(e.text.trim(), r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()))
            }
        } } }
        bmp to words
    }

    /** 像素级表格线检测,与原型 detectGridBounds 对齐 */
    fun detectGridBounds(bmp: Bitmap): Pair<List<Int>, List<Int>> {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        fun isLinePix(i: Int): Boolean {
            val c = px[i]
            val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            return (mx - mn) < 30 && mn > 110 && mn < 240
        }
        fun group(arr: List<Int>): List<Int> {
            val raw = mutableListOf<MutableList<Int>>()
            arr.forEach { v ->
                val c = raw.lastOrNull()
                if (c != null && v - c.last() <= 3) c.add(v) else raw.add(mutableListOf(v))
            }
            return raw.filter { it.size <= 5 }.map { (it.sum() / it.size) }
        }
        val hs = mutableListOf<Int>()
        for (y in 0 until h) {
            var cnt = 0
            var x = 0; while (x < w) { if (isLinePix(y * w + x)) cnt++; x += 2 }
            if (cnt / (w / 2f) > 0.7f) hs.add(y)
        }
        val vs = mutableListOf<Int>()
        for (x in 0 until w) {
            var cnt = 0
            var y = 0; while (y < h) { if (isLinePix(y * w + x)) cnt++; y += 2 }
            if (cnt / (h / 2f) > 0.5f) vs.add(x)
        }
        return group(hs) to group(vs)
    }

    /** 词坐标落格 → 单元格行文本,与原型 wordsToCellLines 对齐 */
    fun wordsToCellLines(words: List<Word>, V: List<Int>, H: List<Int>): Map<String, List<String>> {
        val cells = mutableMapOf<String, MutableList<Word>>()
        words.forEach { wd ->
            val x = (wd.x0 + wd.x1) / 2; val y = (wd.y0 + wd.y1) / 2
            var ci = -1
            for (i in 0 until V.size - 1) if (x >= V[i] && x < V[i + 1]) { ci = i; break }
            var ri = -1
            for (i in 0 until H.size - 1) if (y >= H[i] && y < H[i + 1]) { ri = i; break }
            if (ci < 0 || ri < 0) return@forEach
            cells.getOrPut("$ri:$ci") { mutableListOf() }.add(wd)
        }
        val out = mutableMapOf<String, List<String>>()
        for ((k, ws) in cells) {
            val hs = ws.map { it.y1 - it.y0 }.sorted()
            val medH = hs.getOrElse(hs.size / 2) { 12f }
            val thr = maxOf(6f, medH * 0.6f)
            val centers = ws.sortedWith(compareBy({ (it.y0 + it.y1) / 2 }, { it.x0 }))
            val lines = mutableListOf<MutableList<Word>>()
            var lastY: Float? = null
            centers.forEach { c ->
                val cy = (c.y0 + c.y1) / 2
                if (lastY == null || cy - lastY!! > thr) lines.add(mutableListOf())
                lines.last().add(c); lastY = cy
            }
            val txt = lines.map { L -> L.sortedBy { it.x0 }.joinToString("") { it.t }.trim() }.filter { it.isNotEmpty() }
            if (txt.isNotEmpty()) out[k] = txt
        }
        return out
    }

    /** 单元格行文本 → 课程条目,与原型 entriesFromCells 对齐 */
    fun entriesFromCells(cellMap: Map<String, List<String>>, V: List<Int>?, totalWeeks: Int = DEFAULT_TOTAL_WEEKS): List<ImportEntry> {
        val ORDER = listOf(7, 1, 2, 3, 4, 5, 6)
        val dayOfGap = mutableListOf<Int?>()
        if (V != null && V.size > 2) {
            val widths = (0 until V.size - 1).map { V[it + 1] - V[it] }
            val median = widths.sorted()[widths.size / 2]
            val bandCount = mutableMapOf<Int, Int>()
            for ((k, lines) in cellMap) {
                val ci = k.split(":")[1].toInt()
                if (lines.any { Regex("^\\d{1,2}\\s*[-－~—]\\s*\\d{1,2}$").matches(it) })
                    bandCount[ci] = (bandCount[ci] ?: 0) + 1
            }
            var di = 0
            for (i in widths.indices) {
                if (widths[i] < median * 0.62 || (bandCount[i] ?: 0) >= 2) { dayOfGap.add(null); continue }
                dayOfGap.add(ORDER.getOrNull(di)); di++
            }
        }
        val entries = mutableListOf<ImportEntry>()
        for ((k, lines) in cellMap) {
            val (ri, ci) = k.split(":").map { it.toInt() }
            if (ri < 1) continue
            val band = ri - 1
            if (band >= BANDS.size) continue
            if (Regex("备注|周次|星期").containsMatchIn(lines[0])) continue
            val day = if (V != null) dayOfGap.getOrNull(ci) else ORDER.getOrNull(ci - 1)
            if (day == null) continue
            parseCellLines(lines, totalWeeks).forEach { e -> entries.add(e.copy(day = day, band = band)) }
        }
        return entries
    }

    /** 照片完整管道:OCR → 表格线 → 词归格 → 条目 */
    suspend fun parsePhoto(ctx: Context, uri: Uri, totalWeeks: Int = DEFAULT_TOTAL_WEEKS): List<ImportEntry> {
        val (bmp, words) = ocrWords(ctx, uri)
        val (H, V) = detectGridBounds(bmp)
        val v2 = V.toMutableList(); val h2 = H.toMutableList()
        if (v2.firstOrNull()?.let { it > 20 } == true) v2.add(0, 0)
        if (v2.lastOrNull()?.let { it < bmp.width - 20 } == true) v2.add(bmp.width - 1)
        if (h2.firstOrNull()?.let { it > 20 } == true) h2.add(0, 0)
        if (h2.lastOrNull()?.let { it < bmp.height - 20 } == true) h2.add(bmp.height - 1)
        if (v2.size >= 8 && h2.size >= 7) {
            val got = entriesFromCells(wordsToCellLines(words, v2, h2), v2, totalWeeks)
            if (got.isNotEmpty()) return got
        }
        throw IllegalArgumentException("未识别到课程文本,请手动录入")
    }

    fun isSheetFile(name: String): Boolean = Regex("\\.(xls|xlsx|csv)$", RegexOption.IGNORE_CASE).matches(name)
}
