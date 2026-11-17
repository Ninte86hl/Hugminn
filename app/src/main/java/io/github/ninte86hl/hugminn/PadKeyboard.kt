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
 * controller: D-pad moves, A types, X deletes, Y switches upper/lower case, Start confirms.
 * Works with touch too. Writes into [target]; [onDone] runs for the OK key.
 */
class PadKeyboard(ctx: Context, private val pal: Palette, private val target: EditText, private val onDone: () -> Unit) : View(ctx) {
    private enum class Act { CHAR, SHIFT, BACK, SYM, SPACE, OK, LEFT, RIGHT }
    private class Key(val label: String, val out: String?, val w: Float, val act: Act = Act.CHAR) {
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
    /** The cursor is only drawn once the controller is used. */
    var showCursor = true

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
    private val unit = ctx.dp(2).toFloat()
    private val radius = ctx.dp(if (dots) 6 else 10).toFloat()

    init {
        isFocusable = false
        build()
    }

    private fun build() {
        fun chars(s: String) = s.map { c -> val o = if (shift) c.uppercaseChar().toString() else c.toString(); Key(o, o, 1f) }
        rows = if (!symbols) listOf(
            "1234567890".map { Key(it.toString(), it.toString(), 1f) } + Key("⌫", null, 1.4f, Act.BACK),
            chars("qwertzuiopü"),
            chars("asdfghjklöä"),
            listOf(Key(if (shift) "⇪" else "⇧", null, 1.5f, Act.SHIFT)) + chars("yxcvbnmß") + Key("-", "-", 1f) + Key("_", "_", 1f),
            listOf(Key("?123", null, 1.6f, Act.SYM), Key("@", "@", 1f), Key("‹", null, 1f, Act.LEFT), Key("Leerzeichen", " ", 4.4f, Act.SPACE),
                Key("›", null, 1f, Act.RIGHT), Key(".", ".", 1f), Key("OK", null, 1.8f, Act.OK)),
        ) else listOf(
            "1234567890".map { Key(it.toString(), it.toString(), 1f) } + Key("⌫", null, 1.4f, Act.BACK),
            "!\"§$%&/()=?".map { Key(it.toString(), it.toString(), 1f) },
            "+*#'<>;:,|~".map { Key(it.toString(), it.toString(), 1f) },
            "{}[]\\^°€`´".map { Key(it.toString(), it.toString(), 1f) },
            listOf(Key("ABC", null, 1.6f, Act.SYM), Key("@", "@", 1f), Key("‹", null, 1f, Act.LEFT), Key("Leerzeichen", " ", 4.4f, Act.SPACE),
                Key("›", null, 1f, Act.RIGHT), Key(".", ".", 1f), Key("OK", null, 1.8f, Act.OK)),
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
        setMeasuredDimension(w, minOf(context.dp(250), (w * 0.36f).toInt()))
    }

    private fun shape(r: RectF): Path = if (pixel) pixelPath(r, unit, 2) else Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }

    override fun onDraw(c: Canvas) {
        rows.forEachIndexed { y, r ->
            r.forEachIndexed { x, k ->
                val sel = showCursor && y == row && x == col
                val special = k.act != Act.CHAR && k.act != Act.SPACE
                val accent = k.act == Act.OK || (k.act == Act.SHIFT && shift)
                keyFill.color = when {
                    accent -> pal.primaryContainer
                    dots -> pal.surface
                    special -> pal.surfaceHighest
                    else -> pal.surfaceHigh
                }
                val p = shape(k.rect)
                c.drawPath(p, keyFill)
                if (dots) {
                    keyEdge.color = if (sel) pal.onSurface else pal.outline
                    c.drawPath(p, keyEdge)
                } else if (sel) {
                    keyEdge.color = pal.onSurface
                    c.drawPath(p, keyEdge)
                }
                if (sel && dots) {
                    keyFill.color = (pal.onSurface and 0x00FFFFFF) or 0x22000000
                    c.drawPath(p, keyFill)
                }
                text.color = if (accent) pal.onPrimaryContainer else if (special) pal.onSurfaceVariant else pal.onSurface
                text.textSize = minOf(k.rect.height() * 0.42f, context.dp(20).toFloat()) * if (k.label.length > 3) 0.75f else 1f
                val fm = text.fontMetrics
                c.drawText(k.label, k.rect.centerX(), k.rect.centerY() - (fm.ascent + fm.descent) / 2f, text)
            }
        }
    }

    private fun press(k: Key) {
        val e = target.text
        val start = target.selectionStart.coerceAtLeast(0)
        val end = target.selectionEnd.coerceAtLeast(0)
        when (k.act) {
            Act.CHAR, Act.SPACE -> {
                e.replace(minOf(start, end), maxOf(start, end), k.out ?: "")
                if (shift && k.act == Act.CHAR && symbols.not()) { shift = false; build() }
            }
            Act.BACK -> if (start != end) e.delete(minOf(start, end), maxOf(start, end)) else if (start > 0) e.delete(start - 1, start)
            Act.SHIFT -> { shift = !shift; build() }
            Act.SYM -> { symbols = !symbols; shift = false; build() }
            Act.LEFT -> target.setSelection((start - 1).coerceAtLeast(0))
            Act.RIGHT -> target.setSelection((start + 1).coerceAtMost(e.length))
            Act.OK -> onDone()
        }
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

    /** Controller keys (from the activity); true when handled. */
    fun onKey(code: Int): Boolean {
        if (!showCursor) {
            showCursor = true
            invalidate()
            if (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> move(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(0, 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> move(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(1, 0)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> press(rows[row][col])
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_DEL -> press(Key("⌫", null, 1f, Act.BACK))
            KeyEvent.KEYCODE_BUTTON_Y -> { shift = !shift; build() }
            KeyEvent.KEYCODE_BUTTON_L1 -> press(Key("‹", null, 1f, Act.LEFT))
            KeyEvent.KEYCODE_BUTTON_R1 -> press(Key("›", null, 1f, Act.RIGHT))
            KeyEvent.KEYCODE_BUTTON_START -> onDone()
            else -> return false
        }
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            rows.forEachIndexed { y, r ->
                r.forEachIndexed { x, k ->
                    if (k.rect.contains(e.x, e.y)) {
                        row = y; col = x
                        press(k)
                        invalidate()
                        return true
                    }
                }
            }
        }
        return true
    }
}
