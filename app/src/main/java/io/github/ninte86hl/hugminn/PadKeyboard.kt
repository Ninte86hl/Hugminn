package io.github.ninte86hl.hugminn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import kotlin.math.abs

/**
 * Hugminn's own on-screen keyboard (German layout), drawn in the chosen style and made for the
 * controller: four slim rows; numbers, umlauts and special characters sit behind a long press
 * (hold A or the key). D-pad moves, A types, X deletes, Y upper/lower case, L1/R1 cursor,
 * Start confirms. [onDone] runs for the OK key.
 */
class PadKeyboard(ctx: Context, private val pal: Palette, private val target: EditText, private val onDone: () -> Unit) : View(ctx) {
    private enum class Act { CHAR, SHIFT, BACK, SYM, SPACE, OK, LEFT, RIGHT }
    private class Key(val label: String, val out: String?, val w: Float, val act: Act = Act.CHAR, val more: String = "") {
        val rect = RectF()
    }

    private val style = Prefs(ctx).uiStyle
    private val pixel = style == UiStyle.PIXEL
    private val dots = style == UiStyle.DOTS
    private var shift = false
    private var symbols = false
    private var rows: List<List<Key>> = emptyList()
    private var row = 1
    private var col = 0
    var showCursor = true

    /** Long-press choice: the key's extra characters. */
    private var popup: List<String>? = null
    private var popupSel = 0
    private val popupRects = mutableListOf<RectF>()
    private var popupKey: Key? = null

    private val keyFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        if (dots) {
            strokeWidth = ctx.dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.1f, ctx.dp(5).toFloat()), 0f)
        } else strokeWidth = ctx.dp(3).toFloat()
        if (pixel) isAntiAlias = false
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Look.font(ctx, pixel, true)
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Look.font(ctx, pixel, false)
    }
    private val unit = ctx.dp(2).toFloat()
    private val radius = ctx.dp(if (dots) 6 else 10).toFloat()

    /** Extra characters per key (long press). */
    private val extras = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3€éèê", 'r' to "4", 't' to "5", 'z' to "6", 'u' to "7üúù", 'i' to "8íì", 'o' to "9öóò", 'p' to "0",
        'a' to "äàáâæ", 's' to "ß$§", 'd' to "&", 'f' to "%", 'g' to "#", 'h' to "(", 'j' to ")", 'k' to "'", 'l' to "\"",
        'y' to "!", 'x' to "?", 'c' to "ç©", 'v' to ":", 'b' to ";", 'n' to "ñ", 'm' to "*",
    )

    init {
        isFocusable = false
        // opaque panel: nothing of the screen behind shows between the keys
        background = Look.shape(ctx, pixel, pal.surface, ctx.dp(16).toFloat())
        build()
    }

    private fun build() {
        fun chars(s: String) = s.map { c ->
            val o = if (shift) c.uppercaseChar().toString() else c.toString()
            val more = extras[c].orEmpty().let { if (shift) it.uppercase() else it }
            Key(o, o, 1f, more = more)
        }
        // German: QWERTZ; every other language: QWERTY
        val de = Lang.de
        val space = if (de) "Leerzeichen" else "Space"
        rows = if (!symbols) listOf(
            chars(if (de) "qwertzuiop" else "qwertyuiop") + Key("⌫", null, 1.4f, Act.BACK),
            chars("asdfghjkl") + Key("@", "@", 1f, more = "#&%"),
            listOf(Key(if (shift) "⇪" else "⇧", null, 1.4f, Act.SHIFT)) + chars(if (de) "yxcvbnm" else "zxcvbnm") +
                Key("-", "-", 1f, more = "_+=/") + Key(".", ".", 1f, more = ",;:!?"),
            listOf(Key("?123", null, 1.6f, Act.SYM), Key("‹", null, 1f, Act.LEFT),
                Key(space, " ", 4.6f, Act.SPACE), Key("›", null, 1f, Act.RIGHT), Key("OK", null, 1.8f, Act.OK)),
        ) else listOf(
            "1234567890".map { Key(it.toString(), it.toString(), 1f) } + Key("⌫", null, 1.4f, Act.BACK),
            "!\"§$%&/()=?".map { Key(it.toString(), it.toString(), 1f) },
            "+*#'<>;:,_~".map { Key(it.toString(), it.toString(), 1f) } + Key(".", ".", 1f),
            listOf(Key("ABC", null, 1.6f, Act.SYM), Key("@", "@", 1f), Key("‹", null, 1f, Act.LEFT),
                Key(space, " ", 4.6f, Act.SPACE), Key("›", null, 1f, Act.RIGHT), Key("OK", null, 1.8f, Act.OK)),
        )
        row = row.coerceIn(0, rows.lastIndex)
        col = col.coerceIn(0, rows[row].lastIndex)
        layoutKeys()
        invalidate()
    }

    private fun layoutKeys() {
        if (width == 0) return
        val gap = context.dp(5).toFloat()
        val kh = (height - gap * (rows.size + 1)) / rows.size
        rows.forEachIndexed { y, r ->
            val total = r.sumOf { it.w.toDouble() }.toFloat()
            val kw = (width - gap * (r.size + 1)) / total
            var x = gap
            val top = gap + y * (kh + gap)
            r.forEach { k ->
                k.rect.set(x, top, x + kw * k.w, top + kh)
                x += kw * k.w + gap
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = layoutKeys()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val want = minOf(context.dp(184), (w * 0.26f).toInt())
        // never taller than the room the window has left
        val h = if (MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED) want else minOf(want, MeasureSpec.getSize(hSpec))
        setMeasuredDimension(w, h)
    }

    private fun shape(r: RectF): Path = if (pixel) pixelPath(r, unit, 2) else Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }

    private fun drawKey(c: Canvas, r: RectF, label: String, sel: Boolean, accent: Boolean, special: Boolean, more: String) {
        keyFill.color = when {
            accent -> pal.primaryContainer
            dots -> pal.surface
            special -> pal.surfaceHighest
            else -> pal.surfaceHigh
        }
        val p = shape(r)
        c.drawPath(p, keyFill)
        if (dots) {
            keyEdge.color = if (sel) pal.onSurface else pal.outline
            c.drawPath(p, keyEdge)
        } else if (sel) {
            keyEdge.color = pal.onSurface
            c.drawPath(p, keyEdge)
        }
        text.color = if (accent) pal.onPrimaryContainer else if (special) pal.onSurfaceVariant else pal.onSurface
        text.textSize = minOf(r.height() * 0.46f, context.dp(19).toFloat()) * if (label.length > 3) 0.72f else 1f
        val fm = text.fontMetrics
        c.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, text)
        if (more.isNotEmpty()) {
            // first extra character small in the corner, like on a phone keyboard
            hint.color = pal.onSurfaceVariant
            hint.textSize = r.height() * 0.24f
            c.drawText(more.take(1), r.right - r.height() * 0.12f, r.top + hint.textSize * 1.05f, hint)
        }
    }

    override fun onDraw(c: Canvas) {
        rows.forEachIndexed { y, r ->
            r.forEachIndexed { x, k ->
                drawKey(c, k.rect, k.label, showCursor && popup == null && y == row && x == col,
                    k.act == Act.OK || (k.act == Act.SHIFT && shift), k.act != Act.CHAR && k.act != Act.SPACE, k.more)
            }
        }
        val p = popup ?: return
        val k = popupKey ?: return
        // the extra characters in a row right over the key
        val w = k.rect.width().coerceAtLeast(context.dp(36).toFloat())
        val gap = context.dp(4).toFloat()
        val total = p.size * w + (p.size - 1) * gap
        var x = (k.rect.centerX() - total / 2f).coerceIn(gap, (width - total - gap).coerceAtLeast(gap))
        val top = (k.rect.top - k.rect.height() * 0.15f).coerceAtLeast(0f)
        val panel = RectF(x - gap, top - gap, x + total + gap, top + k.rect.height() + gap)
        keyFill.color = pal.surfaceHighest
        c.drawPath(shape(panel), keyFill)
        popupRects.clear()
        p.forEachIndexed { i, s ->
            val r = RectF(x, top, x + w, top + k.rect.height())
            popupRects += r
            drawKey(c, r, s, i == popupSel, i == popupSel, false, "")
            x += w + gap
        }
    }

    private fun type(s: String) {
        val e = target.text
        val start = target.selectionStart.coerceAtLeast(0)
        val end = target.selectionEnd.coerceAtLeast(0)
        e.replace(minOf(start, end), maxOf(start, end), s)
    }

    private fun press(k: Key) {
        val e = target.text
        val start = target.selectionStart.coerceAtLeast(0)
        val end = target.selectionEnd.coerceAtLeast(0)
        when (k.act) {
            Act.CHAR, Act.SPACE -> {
                type(k.out ?: "")
                if (shift && k.act == Act.CHAR && !symbols) { shift = false; build() }
            }
            Act.BACK -> if (start != end) e.delete(minOf(start, end), maxOf(start, end)) else if (start > 0) e.delete(start - 1, start)
            Act.SHIFT -> { shift = !shift; build() }
            Act.SYM -> { symbols = !symbols; shift = false; build() }
            Act.LEFT -> target.setSelection((start - 1).coerceAtLeast(0))
            Act.RIGHT -> target.setSelection((start + 1).coerceAtMost(e.length))
            Act.OK -> onDone()
        }
    }

    /** Opens the extra characters of [k] (long press); false if it has none. */
    private fun openPopup(k: Key): Boolean {
        if (k.more.isEmpty()) return false
        popup = listOf(k.label) + k.more.map { it.toString() }
        popupSel = 1
        popupKey = k
        invalidate()
        return true
    }

    private fun choosePopup() {
        val p = popup ?: return
        p.getOrNull(popupSel)?.let { type(it) }
        if (shift && !symbols) { shift = false; build() }
        closePopup()
    }

    private fun closePopup() {
        popup = null
        popupKey = null
        invalidate()
    }

    private fun move(dx: Int, dy: Int) {
        if (dy != 0) {
            val cur = rows[row][col].rect.centerX()
            val ny = (row + dy).coerceIn(0, rows.lastIndex)
            if (ny != row) {
                row = ny
                col = rows[row].indices.minByOrNull { abs(rows[row][it].rect.centerX() - cur) } ?: 0
            }
        } else col = (col + dx).let { if (it < 0) rows[row].lastIndex else if (it > rows[row].lastIndex) 0 else it }
        invalidate()
    }

    // ---------- controller ----------

    private var aLong = false
    private val aHold = Runnable { aLong = openPopup(rows[row][col]) }

    /** Key events from the activity (down and up); true when handled. */
    fun onKeyEvent(e: KeyEvent): Boolean {
        // by position: north (Y) shift, west (X) delete – on every layout
        val code = Glyphs.logical(e.keyCode)
        val isA = code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (isA) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                if (popup != null) { choosePopup(); aLong = true; return true }
                aLong = false
                postDelayed(aHold, 450)
            } else if (e.action == KeyEvent.ACTION_UP) {
                removeCallbacks(aHold)
                if (!aLong) press(rows[row][col])
                aLong = false
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return false
        return onKey(code)
    }

    /** One controller key press (also from the D-pad reader); true when handled. */
    fun onKey(code: Int): Boolean {
        val p = popup
        if (p != null) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> popupSel = (popupSel - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> popupSel = (popupSel + 1).coerceAtMost(p.lastIndex)
                KeyEvent.KEYCODE_BUTTON_A -> { choosePopup(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BUTTON_X -> { closePopup(); return true }
                else -> return false
            }
            invalidate()
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> move(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(0, 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> move(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(1, 0)
            KeyEvent.KEYCODE_BUTTON_A -> press(rows[row][col])
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_DEL -> press(Key("⌫", null, 1f, Act.BACK))
            KeyEvent.KEYCODE_BUTTON_Y -> { shift = !shift; build() }
            KeyEvent.KEYCODE_BUTTON_L1 -> press(Key("‹", null, 1f, Act.LEFT))
            KeyEvent.KEYCODE_BUTTON_R1 -> press(Key("›", null, 1f, Act.RIGHT))
            KeyEvent.KEYCODE_BUTTON_START -> onDone()
            else -> return false
        }
        return true
    }

    /** B: closes the long-press choice first; false when nothing was open. */
    fun back(): Boolean {
        if (popup == null) return false
        closePopup()
        return true
    }

    // ---------- touch ----------

    private var touchKey: Key? = null
    private val touchHold = Runnable { touchKey?.let { openPopup(it) } }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchKey = null
                rows.forEachIndexed { y, r -> r.forEachIndexed { x, k -> if (k.rect.contains(e.x, e.y)) { row = y; col = x; touchKey = k } } }
                invalidate()
                postDelayed(touchHold, 400)
            }
            MotionEvent.ACTION_MOVE -> if (popup != null) {
                popupRects.indexOfFirst { e.x >= it.left && e.x <= it.right }.takeIf { it >= 0 }?.let { popupSel = it; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(touchHold)
                if (popup != null) choosePopup() else touchKey?.let { press(it) }
                touchKey = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(touchHold); closePopup() }
        }
        return true
    }
}
