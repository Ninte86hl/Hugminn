package io.github.ninte86hl.hugminn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Simple line icons drawn in code, so they look the same everywhere and follow the theme colour. */
class IconView(ctx: Context, private val kind: Kind, private val sizeDp: Int = 22) : View(ctx) {
    enum class Kind { WIFI, BLUETOOTH, BOLT, FAN, REFRESH, LED, CAMERA, GEAR, AIRPLANE, MOON, ROTATE, SCREEN, BROOM, GAMEPAD, SUN, SPEAKER, THERMO, PLUG, VIBRATE, LOCK, PALETTE, MOUSE, MUSIC, POWER, BATTERY, PLAY, PAUSE, PREV, NEXT, PIN, LIST, EXPAND, VOL_DOWN, VOL_UP, DIM }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setColor(c: Int) {
        p.color = c
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = context.dp(sizeDp)
        setMeasuredDimension(s, s)
    }

    /** 0 lines, 1 pixel blocks (pixel style), 2 dots (dot matrix) – for the media buttons. */
    var grid = 0
        set(v) { field = v; invalidate() }

    /** The style's own form for this icon (only the media buttons have a block/dot version). */
    fun styled(pal: Palette, pixel: Boolean) = apply { grid = if (pal.dots) 2 else if (pixel) 1 else 0 }

    /** A crescent moon on the 9×9 grid (dim button). */
    private val crescent = listOf(
        "...###...",
        ".###.....",
        ".##......",
        "##.......",
        "##.......",
        "##......#",
        ".##....##",
        ".#######.",
        "...###...",
    )

    /** Media buttons on a 9×9 grid: true = cell lit. */
    private fun cells(k: Kind): ((Int, Int) -> Boolean)? = when (k) {
        Kind.PLAY -> { r, col -> col >= 2 && col < 2 + (5 - Math.abs(r - 4)) }
        // a push pin: round head, short bar, needle
        Kind.PIN -> { r, col -> (r in 0..3 && col in 2..6 && !(r == 0 && (col == 2 || col == 6))) || (r == 4 && col in 1..7) || (r in 5..8 && col == 4) }
        Kind.PAUSE -> { r, col -> r in 1..7 && (col in 2..3 || col in 5..6) }
        Kind.PREV -> { r, col -> r in 1..7 && (col == 1 || col in (7 - (4 - Math.abs(r - 4)))..6) }
        Kind.NEXT -> { r, col -> r in 1..7 && (col == 7 || col in 2..(1 + (4 - Math.abs(r - 4)))) }
        // the player's other buttons in the same blocks/dots, so the whole row matches
        Kind.VOL_DOWN, Kind.VOL_UP -> { r, col ->
            val speaker = (col in 0..1 && r in 3..5) || (col == 2 && r in 2..6) || (col == 3 && r in 1..7)
            val minus = r == 4 && col in 5..7
            val plus = k == Kind.VOL_UP && col == 6 && r in 3..5
            speaker || minus || plus
        }
        Kind.EXPAND -> { r, col ->
            val edgeR = r == 0 || r == 8
            val edgeC = col == 0 || col == 8
            (edgeR && (col <= 2 || col >= 6)) || (edgeC && (r <= 2 || r >= 6))
        }
        Kind.CAMERA -> { r, col ->
            (r == 1 && col in 3..5) || ((r == 2 || r == 7) && col in 0..8) || ((col == 0 || col == 8) && r in 2..7) ||
                (r in 3..6 && col in 3..5 && !(r in 4..5 && col == 4) && !((r == 3 || r == 6) && (col == 3 || col == 5)))
        }
        Kind.DIM -> { r, col -> crescent[r][col] == '#' }
        else -> null
    }

    override fun onDraw(c: Canvas) {
        val lit = if (grid > 0) cells(kind) else null
        if (lit != null) {
            val n = 9
            val cell = width.toFloat() / n
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.color; style = Paint.Style.FILL }
            for (r in 0 until n) for (col in 0 until n) if (lit(r, col)) {
                val x = col * cell
                val y = r * cell
                if (grid == 2) c.drawCircle(x + cell / 2, y + cell / 2, cell * 0.38f, fill)
                else c.drawRect(x + cell * 0.06f, y + cell * 0.06f, x + cell * 0.94f, y + cell * 0.94f, fill)
            }
            return
        }
        val s = width.toFloat()
        p.strokeWidth = s * 0.09f
        val m = s / 2f
        when (kind) {
            Kind.WIFI -> {
                for (i in 1..3) {
                    val r = s * 0.15f * i + s * 0.02f
                    c.drawArc(RectF(m - r, s * 0.82f - r, m + r, s * 0.82f + r), 225f, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(m, s * 0.8f, s * 0.06f, p)
                p.style = Paint.Style.STROKE
            }
            Kind.BLUETOOTH -> c.drawPath(Path().apply {
                moveTo(s * 0.3f, s * 0.32f); lineTo(s * 0.7f, s * 0.68f); lineTo(m, s * 0.86f); lineTo(m, s * 0.14f)
                lineTo(s * 0.7f, s * 0.32f); lineTo(s * 0.3f, s * 0.68f)
            }, p)
            Kind.BOLT -> c.drawPath(Path().apply {
                moveTo(s * 0.58f, s * 0.1f); lineTo(s * 0.28f, s * 0.56f); lineTo(s * 0.5f, s * 0.56f)
                lineTo(s * 0.42f, s * 0.9f); lineTo(s * 0.72f, s * 0.44f); lineTo(s * 0.5f, s * 0.44f); close()
            }, p)
            Kind.FAN -> {
                for (i in 0 until 3) {
                    c.save()
                    c.rotate(120f * i, m, m)
                    c.drawPath(Path().apply {
                        moveTo(m, m)
                        cubicTo(s * 0.35f, s * 0.35f, s * 0.38f, s * 0.08f, m, s * 0.1f)
                        cubicTo(s * 0.66f, s * 0.12f, s * 0.62f, s * 0.35f, m, m)
                    }, p)
                    c.restore()
                }
            }
            Kind.REFRESH -> {
                c.drawRoundRect(RectF(s * 0.12f, s * 0.22f, s * 0.88f, s * 0.72f), s * 0.08f, s * 0.08f, p)
                c.drawLine(s * 0.35f, s * 0.86f, s * 0.65f, s * 0.86f, p)
                c.drawPath(Path().apply {
                    moveTo(s * 0.24f, s * 0.6f); lineTo(s * 0.38f, s * 0.4f); lineTo(s * 0.5f, s * 0.54f)
                    lineTo(s * 0.62f, s * 0.36f); lineTo(s * 0.76f, s * 0.5f)
                }, p)
            }
            Kind.LED -> {
                // a stick seen from above with its light ring: two circles and short glow marks
                c.drawCircle(m, m, s * 0.15f, p)
                c.drawCircle(m, m, s * 0.31f, p)
                for (i in 0 until 4) {
                    c.save()
                    c.rotate(45f + 90f * i, m, m)
                    c.drawLine(m, s * 0.02f, m, s * 0.1f, p)
                    c.restore()
                }
            }
            Kind.CAMERA -> {
                c.drawRoundRect(RectF(s * 0.1f, s * 0.28f, s * 0.9f, s * 0.82f), s * 0.1f, s * 0.1f, p)
                c.drawLine(s * 0.36f, s * 0.18f, s * 0.64f, s * 0.18f, p)
                c.drawCircle(m, s * 0.55f, s * 0.15f, p)
            }
            Kind.AIRPLANE -> c.drawPath(Path().apply {
                moveTo(m, s * 0.1f); lineTo(m, s * 0.75f)
                moveTo(s * 0.12f, s * 0.55f); lineTo(m, s * 0.38f); lineTo(s * 0.88f, s * 0.55f)
                moveTo(s * 0.34f, s * 0.9f); lineTo(m, s * 0.78f); lineTo(s * 0.66f, s * 0.9f)
            }, p)
            Kind.DIM -> {
                // dim the screen: a crescent moon
                val moon = Path().apply { addCircle(m, m, s * 0.36f, Path.Direction.CW) }
                val bite = Path().apply { addCircle(s * 0.68f, s * 0.32f, s * 0.3f, Path.Direction.CW) }
                moon.op(bite, Path.Op.DIFFERENCE)
                c.drawPath(moon, p)
            }
            Kind.MOON -> {
                // "do not disturb" like on Android: a circle with a bar
                c.drawCircle(m, m, s * 0.36f, p)
                c.drawLine(s * 0.3f, m, s * 0.7f, m, p)
            }
            Kind.ROTATE -> {
                c.drawArc(RectF(s * 0.16f, s * 0.16f, s * 0.84f, s * 0.84f), 300f, 280f, false, p)
                c.drawPath(Path().apply { moveTo(s * 0.62f, s * 0.12f); lineTo(s * 0.82f, s * 0.2f); lineTo(s * 0.7f, s * 0.38f) }, p)
            }
            Kind.SCREEN -> {
                c.drawRoundRect(RectF(s * 0.1f, s * 0.2f, s * 0.9f, s * 0.7f), s * 0.08f, s * 0.08f, p)
                c.drawLine(s * 0.35f, s * 0.86f, s * 0.65f, s * 0.86f, p)
                c.drawLine(m, s * 0.7f, m, s * 0.86f, p)
            }
            Kind.BROOM -> {
                c.drawLine(s * 0.78f, s * 0.12f, s * 0.48f, s * 0.48f, p)
                c.drawPath(Path().apply {
                    moveTo(s * 0.38f, s * 0.42f); lineTo(s * 0.56f, s * 0.58f); lineTo(s * 0.4f, s * 0.9f)
                    lineTo(s * 0.12f, s * 0.66f); close()
                }, p)
            }
            Kind.GAMEPAD -> {
                c.drawRoundRect(RectF(s * 0.06f, s * 0.28f, s * 0.94f, s * 0.76f), s * 0.22f, s * 0.22f, p)
                c.drawLine(s * 0.24f, s * 0.52f, s * 0.4f, s * 0.52f, p)
                c.drawLine(s * 0.32f, s * 0.44f, s * 0.32f, s * 0.6f, p)
                c.drawCircle(s * 0.66f, s * 0.47f, s * 0.03f, p)
                c.drawCircle(s * 0.74f, s * 0.57f, s * 0.03f, p)
            }
            Kind.SUN -> {
                c.drawCircle(m, m, s * 0.17f, p)
                for (i in 0 until 8) {
                    c.save()
                    c.rotate(45f * i, m, m)
                    c.drawLine(m, s * 0.06f, m, s * 0.18f, p)
                    c.restore()
                }
            }
            Kind.SPEAKER -> {
                c.drawPath(Path().apply {
                    moveTo(s * 0.12f, s * 0.38f); lineTo(s * 0.3f, s * 0.38f); lineTo(s * 0.52f, s * 0.18f)
                    lineTo(s * 0.52f, s * 0.82f); lineTo(s * 0.3f, s * 0.62f); lineTo(s * 0.12f, s * 0.62f); close()
                }, p)
                c.drawArc(RectF(s * 0.5f, s * 0.32f, s * 0.74f, s * 0.68f), -60f, 120f, false, p)
                c.drawArc(RectF(s * 0.5f, s * 0.18f, s * 0.9f, s * 0.82f), -60f, 120f, false, p)
            }
            Kind.VOL_DOWN, Kind.VOL_UP -> {
                // a speaker with − or +, both on the same middle line
                c.drawPath(Path().apply {
                    moveTo(s * 0.08f, s * 0.38f); lineTo(s * 0.24f, s * 0.38f); lineTo(s * 0.46f, s * 0.18f)
                    lineTo(s * 0.46f, s * 0.82f); lineTo(s * 0.24f, s * 0.62f); lineTo(s * 0.08f, s * 0.62f); close()
                }, p)
                c.drawLine(s * 0.62f, m, s * 0.92f, m, p)
                if (kind == Kind.VOL_UP) c.drawLine(s * 0.77f, s * 0.35f, s * 0.77f, s * 0.65f, p)
            }
            Kind.THERMO -> {
                c.drawRoundRect(RectF(s * 0.4f, s * 0.08f, s * 0.6f, s * 0.66f), s * 0.1f, s * 0.1f, p)
                c.drawCircle(m, s * 0.76f, s * 0.15f, p)
            }
            Kind.PLUG -> {
                c.drawRoundRect(RectF(s * 0.28f, s * 0.3f, s * 0.72f, s * 0.62f), s * 0.08f, s * 0.08f, p)
                c.drawLine(s * 0.4f, s * 0.12f, s * 0.4f, s * 0.3f, p)
                c.drawLine(s * 0.6f, s * 0.12f, s * 0.6f, s * 0.3f, p)
                c.drawLine(m, s * 0.62f, m, s * 0.9f, p)
            }
            Kind.VIBRATE -> {
                c.drawRoundRect(RectF(s * 0.32f, s * 0.14f, s * 0.68f, s * 0.86f), s * 0.08f, s * 0.08f, p)
                c.drawLine(s * 0.18f, s * 0.36f, s * 0.18f, s * 0.64f, p)
                c.drawLine(s * 0.82f, s * 0.36f, s * 0.82f, s * 0.64f, p)
            }
            Kind.LOCK -> {
                c.drawRoundRect(RectF(s * 0.2f, s * 0.44f, s * 0.8f, s * 0.88f), s * 0.08f, s * 0.08f, p)
                c.drawArc(RectF(s * 0.32f, s * 0.12f, s * 0.68f, s * 0.6f), 180f, 180f, false, p)
            }
            Kind.PALETTE -> {
                c.drawCircle(m, m, s * 0.36f, p)
                p.style = Paint.Style.FILL
                c.drawArc(RectF(s * 0.14f, s * 0.14f, s * 0.86f, s * 0.86f), 90f, 180f, true, p)
                p.style = Paint.Style.STROKE
            }
            Kind.MUSIC -> {
                c.drawCircle(s * 0.32f, s * 0.72f, s * 0.12f, p)
                c.drawCircle(s * 0.7f, s * 0.64f, s * 0.12f, p)
                c.drawLine(s * 0.44f, s * 0.72f, s * 0.44f, s * 0.2f, p)
                c.drawLine(s * 0.82f, s * 0.64f, s * 0.82f, s * 0.14f, p)
                c.drawLine(s * 0.44f, s * 0.2f, s * 0.82f, s * 0.14f, p)
            }
            Kind.POWER -> {
                c.drawArc(RectF(s * 0.16f, s * 0.2f, s * 0.84f, s * 0.88f), 300f, 300f, false, p)
                c.drawLine(m, s * 0.1f, m, s * 0.48f, p)
            }
            Kind.BATTERY -> {
                c.drawRoundRect(RectF(s * 0.1f, s * 0.3f, s * 0.82f, s * 0.7f), s * 0.08f, s * 0.08f, p)
                c.drawLine(s * 0.9f, s * 0.42f, s * 0.9f, s * 0.58f, p)
                c.drawLine(s * 0.24f, s * 0.42f, s * 0.24f, s * 0.58f, p)
                c.drawLine(s * 0.38f, s * 0.42f, s * 0.38f, s * 0.58f, p)
            }
            Kind.PIN -> {
                c.drawCircle(m, s * 0.3f, s * 0.2f, p)
                c.drawLine(s * 0.18f, s * 0.52f, s * 0.82f, s * 0.52f, p)
                c.drawLine(m, s * 0.52f, m, s * 0.94f, p)
            }
            Kind.PLAY -> c.drawPath(Path().apply { moveTo(s * 0.3f, s * 0.18f); lineTo(s * 0.82f, m); lineTo(s * 0.3f, s * 0.82f); close() }, p)
            Kind.PAUSE -> {
                c.drawLine(s * 0.36f, s * 0.2f, s * 0.36f, s * 0.8f, p)
                c.drawLine(s * 0.64f, s * 0.2f, s * 0.64f, s * 0.8f, p)
            }
            Kind.PREV -> {
                c.drawPath(Path().apply { moveTo(s * 0.8f, s * 0.22f); lineTo(s * 0.34f, m); lineTo(s * 0.8f, s * 0.78f); close() }, p)
                c.drawLine(s * 0.22f, s * 0.22f, s * 0.22f, s * 0.78f, p)
            }
            Kind.NEXT -> {
                c.drawPath(Path().apply { moveTo(s * 0.2f, s * 0.22f); lineTo(s * 0.66f, m); lineTo(s * 0.2f, s * 0.78f); close() }, p)
                c.drawLine(s * 0.78f, s * 0.22f, s * 0.78f, s * 0.78f, p)
            }
            Kind.LIST -> {
                // a playlist: three rows, the last with a small play mark
                c.drawLine(s * 0.16f, s * 0.26f, s * 0.84f, s * 0.26f, p)
                c.drawLine(s * 0.16f, s * 0.5f, s * 0.84f, s * 0.5f, p)
                c.drawLine(s * 0.16f, s * 0.74f, s * 0.5f, s * 0.74f, p)
                c.drawPath(Path().apply { moveTo(s * 0.64f, s * 0.64f); lineTo(s * 0.86f, s * 0.76f); lineTo(s * 0.64f, s * 0.88f); close() }, p)
            }
            Kind.EXPAND -> {
                // full screen: four corners
                val a = s * 0.16f; val b = s * 0.84f; val k = s * 0.22f
                c.drawPath(Path().apply {
                    moveTo(a, a + k); lineTo(a, a); lineTo(a + k, a)
                    moveTo(b - k, a); lineTo(b, a); lineTo(b, a + k)
                    moveTo(b, b - k); lineTo(b, b); lineTo(b - k, b)
                    moveTo(a + k, b); lineTo(a, b); lineTo(a, b - k)
                }, p)
            }
            Kind.MOUSE -> {
                c.drawRoundRect(RectF(s * 0.26f, s * 0.12f, s * 0.74f, s * 0.88f), s * 0.24f, s * 0.24f, p)
                c.drawLine(m, s * 0.12f, m, s * 0.4f, p)
            }
            Kind.GEAR -> {
                c.drawCircle(m, m, s * 0.14f, p)
                c.drawCircle(m, m, s * 0.3f, p)
                for (i in 0 until 8) {
                    c.save()
                    c.rotate(45f * i, m, m)
                    c.drawLine(m, s * 0.08f, m, s * 0.2f, p)
                    c.restore()
                }
            }
        }
    }
}
