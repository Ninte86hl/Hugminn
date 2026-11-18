package io.github.ninte86hl.hugminn

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.widget.TextView
import android.graphics.Rect
import android.view.View

/** Material You colours taken from the system palette (falls back to Hugminn red on older Android). */
class Palette(private val ctx: Context) {
    private val theme = Prefs(ctx).theme
    /** Dot-matrix style: monochrome with one red accent (Nothing-Phone-like). */
    val dots = Prefs(ctx).uiStyle == UiStyle.DOTS
    /** OLED: dark with pure black backgrounds. */
    val oled = theme == Theme.OLED
    val dark: Boolean = when (theme) {
        Theme.LIGHT -> false
        Theme.DARK, Theme.OLED -> true
        Theme.SYSTEM -> {
            // Services keep an old configuration, so ask the system for the current night mode first.
            val um = ctx.getSystemService(android.app.UiModeManager::class.java)
            when (um?.nightMode) {
                android.app.UiModeManager.MODE_NIGHT_YES -> true
                android.app.UiModeManager.MODE_NIGHT_NO -> false
                else -> (ctx.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
    private fun sys(name: String, fallback: Int): Int {
        if (Build.VERSION.SDK_INT < 31) return fallback
        val id = ctx.resources.getIdentifier(name, "color", "android")
        return if (id != 0) ctx.getColor(id) else fallback
    }

    /** Chosen tile colour (0 = style default); it becomes the accent of every style. */
    private val chosen = Prefs(ctx).tileColor.takeIf { it != 0 }
    /** Dot-matrix accent: the chosen tile colour, otherwise Nothing-like red. */
    private val dotAccent = chosen ?: if (dark) 0xFFE5262E.toInt() else 0xFFD71920.toInt()
    /** Styles other than dot matrix use the chosen colour like the dot matrix does, else Material You. */
    private val useDot = dots || chosen != null
    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    val primary = if (useDot) dotAccent else if (dark) sys("system_accent1_200", 0xFFFFB3AC.toInt()) else sys("system_accent1_600", 0xFFB3261E.toInt())
    val onPrimary = if (useDot) onColor(dotAccent) else if (dark) sys("system_accent1_800", 0xFF680003.toInt()) else sys("system_accent1_0", Color.WHITE)
    val primaryContainer = if (useDot) (if (dark) mix(Color.BLACK, dotAccent, 0.18f) else mix(Color.WHITE, dotAccent, 0.14f)) else if (dark) sys("system_accent1_700", 0xFF8C1D18.toInt()) else sys("system_accent1_100", 0xFFFFDAD6.toInt())
    val onPrimaryContainer = if (useDot) (if (dark) mix(Color.WHITE, dotAccent, 0.35f) else mix(Color.BLACK, dotAccent, 0.45f)) else if (dark) sys("system_accent1_100", 0xFFFFDAD6.toInt()) else sys("system_accent1_900", 0xFF410002.toInt())
    val secondaryContainer = if (dots) (if (dark) 0xFF262626.toInt() else 0xFFE4E4E4.toInt()) else if (dark) sys("system_accent2_700", 0xFF5D3F3C.toInt()) else sys("system_accent2_100", 0xFFFFDAD6.toInt())
    val onSecondaryContainer = if (dots) (if (dark) Color.WHITE else Color.BLACK) else if (dark) sys("system_accent2_100", 0xFFFFDAD6.toInt()) else sys("system_accent2_900", 0xFF2C1513.toInt())
    val surface = if (dots) (if (dark) Color.BLACK else 0xFFF3F3F3.toInt()) else if (oled) Color.BLACK else if (dark) sys("system_neutral1_900", 0xFF201A19.toInt()) else sys("system_neutral1_10", 0xFFFFFBFF.toInt())
    val surfaceHigh = if (dots) (if (dark) 0xFF101010.toInt() else Color.WHITE) else if (oled) 0xFF111111.toInt() else if (dark) sys("system_neutral1_800", 0xFF362F2E.toInt()) else sys("system_neutral1_50", 0xFFFBEEEC.toInt())
    val surfaceHighest = if (dots) (if (dark) 0xFF242424.toInt() else 0xFFE2E2E2.toInt()) else if (oled) 0xFF1F1F1F.toInt() else if (dark) sys("system_neutral2_700", 0xFF534341.toInt()) else sys("system_neutral2_100", 0xFFF5DDDA.toInt())
    val onSurface = if (dots) (if (dark) Color.WHITE else Color.BLACK) else if (dark) sys("system_neutral1_100", 0xFFEDE0DE.toInt()) else sys("system_neutral1_900", 0xFF201A19.toInt())
    val onSurfaceVariant = if (dots) (if (dark) 0xFF8C8C8C.toInt() else 0xFF6B6B6B.toInt()) else if (dark) sys("system_neutral2_200", 0xFFD8C2BF.toInt()) else sys("system_neutral2_700", 0xFF534341.toInt())
    val outline = if (dots) (if (dark) 0xFF3C3C3C.toInt() else 0xFFBDBDBD.toInt()) else if (dark) sys("system_neutral2_400", 0xFFA08C8A.toInt()) else sys("system_neutral2_500", 0xFF857371.toInt())
    val scrim = 0x99000000.toInt()

    // Dot-matrix style overrides: black/white/grey, red as the only colour
    val dotsPrimary get() = dotAccent
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

/**
 * Face-button glyphs. Android key codes follow the labels the firmware uses (A confirms), the glyph
 * shown follows the button's physical position and the style the user picked.
 */
object Glyphs {
    enum class Pos { BOTTOM, RIGHT, LEFT, TOP }
    enum class Shape { CROSS, CIRCLE, SQUARE, TRIANGLE }

    /** What to draw: a letter or a PlayStation shape, or a label for other buttons. */
    data class Glyph(val text: String?, val shape: Shape?, val color: Int)

    /** True when the handheld runs in Retroid/Nintendo layout (A on the right). */
    fun nintendoLayout(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.name?.contains("Retroid Pocket Controller", ignoreCase = true) == true
    }

    fun posOf(keyCode: Int, nintendo: Boolean = nintendoLayout()): Pos? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> if (nintendo) Pos.RIGHT else Pos.BOTTOM
        KeyEvent.KEYCODE_BUTTON_B -> if (nintendo) Pos.BOTTOM else Pos.RIGHT
        KeyEvent.KEYCODE_BUTTON_X -> if (nintendo) Pos.TOP else Pos.LEFT
        KeyEvent.KEYCODE_BUTTON_Y -> if (nintendo) Pos.LEFT else Pos.TOP
        else -> null
    }

    private fun styleFor(prefs: Prefs, nintendo: Boolean) = when (prefs.glyphStyle) {
        GlyphStyle.AUTO -> if (nintendo) GlyphStyle.NINTENDO else GlyphStyle.XBOX
        else -> prefs.glyphStyle
    }

    fun glyph(prefs: Prefs, keyCode: Int): Glyph {
        val nintendo = nintendoLayout()
        val pos = posOf(keyCode, nintendo) ?: return Glyph(keyLabel(keyCode), null, 0)
        val g = when (styleFor(prefs, nintendo)) {
            GlyphStyle.PLAYSTATION -> when (pos) {
                Pos.BOTTOM -> Glyph(null, Shape.CROSS, 0xFF8FB4F0.toInt())
                Pos.RIGHT -> Glyph(null, Shape.CIRCLE, 0xFFF07A7A.toInt())
                Pos.LEFT -> Glyph(null, Shape.SQUARE, 0xFFE59AD9.toInt())
                Pos.TOP -> Glyph(null, Shape.TRIANGLE, 0xFF4CD1B0.toInt())
            }
            GlyphStyle.NINTENDO -> when (pos) {
                Pos.BOTTOM -> Glyph("B", null, 0xFFF2CF4A.toInt())
                Pos.RIGHT -> Glyph("A", null, 0xFFF0625C.toInt())
                Pos.LEFT -> Glyph("Y", null, 0xFF5CCB68.toInt())
                Pos.TOP -> Glyph("X", null, 0xFF5B9BF2.toInt())
            }
            else -> when (pos) {
                Pos.BOTTOM -> Glyph("A", null, 0xFF5CCB68.toInt())
                Pos.RIGHT -> Glyph("B", null, 0xFFF0625C.toInt())
                Pos.LEFT -> Glyph("X", null, 0xFF5B9BF2.toInt())
                Pos.TOP -> Glyph("Y", null, 0xFFF2CF4A.toInt())
            }
        }
        return if (prefs.glyphColored) g else g.copy(color = 0)
    }

    private fun keyLabel(k: Int) = when (k) {
        KeyEvent.KEYCODE_BUTTON_L1 -> "LB"
        KeyEvent.KEYCODE_BUTTON_R1 -> "RB"
        KeyEvent.KEYCODE_BUTTON_START -> "☰"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "⧉"
        else -> "?"
    }

    /** A round button like on the controller: dark cap, coloured symbol (or plain in mono mode). */
    fun chip(ctx: Context, prefs: Prefs, pal: Palette, keyCode: Int, sizeDp: Int = 24): View =
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) DpadView(ctx, pal, sizeDp)
        else GlyphView(ctx, glyph(prefs, keyCode), pal, sizeDp)
}

class GlyphView(ctx: Context, private val g: Glyphs.Glyph, pal: Palette, private val sizeDp: Int) : View(ctx) {
    private val cap = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = if (g.color != 0) (if (pal.dark) 0xFF34343A.toInt() else 0xFF2B2B30.toInt()) else pal.onSurface
    }
    private val ink = if (g.color != 0) g.color else pal.surface
    private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        color = ink
    }
    private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        typeface = Look.font(ctx, Prefs(ctx).uiStyle == UiStyle.PIXEL, true)
        textAlign = android.graphics.Paint.Align.CENTER
    }

    override fun onMeasure(w: Int, h: Int) {
        val wide = (g.text?.length ?: 1) > 1
        val s = context.dp(sizeDp)
        setMeasuredDimension(if (wide) (s * 1.5f).toInt() else s, s)
    }

    private val style = Prefs(ctx).uiStyle

    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        val cx = w / 2f
        val cy = h / 2f
        val s = h * 0.24f // half size of the symbol
        val pixel = style == UiStyle.PIXEL
        val dots = style == UiStyle.DOTS
        // the button cap in the chosen style
        when {
            pixel -> {
                cap.isAntiAlias = false
                c.drawPath(pixelPath(android.graphics.RectF(0f, 0f, w, h), h / 8f, 2), cap)
            }
            dots -> {
                // ring of dots instead of a filled cap; the symbol takes the cap's colour
                val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = h * 0.09f
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    color = if (g.color != 0) g.color else cap.color
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.1f, h * 0.17f), 0f)
                }
                c.drawRoundRect(h * 0.06f, h * 0.06f, w - h * 0.06f, h - h * 0.06f, r, r, ring)
            }
            else -> c.drawRoundRect(0f, 0f, w, h, r, r, cap)
        }
        stroke.strokeWidth = h * if (pixel) 0.11f else 0.09f
        if (pixel) {
            stroke.isAntiAlias = false
            stroke.strokeCap = android.graphics.Paint.Cap.SQUARE
            stroke.strokeJoin = android.graphics.Paint.Join.MITER
        }
        if (dots) {
            stroke.color = if (g.color != 0) g.color else cap.color
            textPaint.color = stroke.color
            stroke.strokeWidth = h * 0.1f
            stroke.strokeCap = android.graphics.Paint.Cap.ROUND
            stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.1f, h * 0.13f), 0f)
        }
        when (g.shape) {
            Glyphs.Shape.CROSS -> {
                c.drawLine(cx - s, cy - s, cx + s, cy + s, stroke)
                c.drawLine(cx - s, cy + s, cx + s, cy - s, stroke)
            }
            Glyphs.Shape.CIRCLE -> if (pixel) {
                // octagon: a circle drawn with pixels
                val k = s * 1.05f
                val e = k * 0.42f
                c.drawPath(android.graphics.Path().apply {
                    moveTo(cx - e, cy - k); lineTo(cx + e, cy - k); lineTo(cx + k, cy - e); lineTo(cx + k, cy + e)
                    lineTo(cx + e, cy + k); lineTo(cx - e, cy + k); lineTo(cx - k, cy + e); lineTo(cx - k, cy - e); close()
                }, stroke)
            } else c.drawCircle(cx, cy, s * 1.05f, stroke)
            Glyphs.Shape.SQUARE -> c.drawRect(cx - s * 0.92f, cy - s * 0.92f, cx + s * 0.92f, cy + s * 0.92f, stroke)
            Glyphs.Shape.TRIANGLE -> {
                val t = android.graphics.Path().apply {
                    moveTo(cx, cy - s * 1.1f)
                    lineTo(cx + s * 1.15f, cy + s * 0.85f)
                    lineTo(cx - s * 1.15f, cy + s * 0.85f)
                    close()
                }
                c.drawPath(t, stroke)
            }
            null -> {
                val text = g.text ?: return
                textPaint.textSize = h * (if (text.length > 1) 0.42f else if (pixel) 0.66f else 0.56f)
                if (pixel) textPaint.isAntiAlias = false
                val b = Rect().also { textPaint.getTextBounds(text, 0, text.length, it) }
                c.drawText(text, cx, cy + b.height() / 2f - b.bottom.coerceAtLeast(0) / 2f, textPaint)
            }
        }
    }
}

/** Small D-pad symbol for "change" hints. */
class DpadView(ctx: Context, pal: Palette, private val sizeDp: Int) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = pal.onSurfaceVariant }
    private val hole = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = pal.surface }

    override fun onMeasure(w: Int, h: Int) {
        val s = context.dp(sizeDp)
        setMeasuredDimension(s, s)
    }

    private val style = Prefs(ctx).uiStyle

    override fun onDraw(c: android.graphics.Canvas) {
        val s = height.toFloat()
        val a = s * 0.33f
        val r = s * 0.08f
        if (style == UiStyle.DOTS) {
            // cross made of dots, arrows left/right brighter
            val n = 5
            val step = s / n
            for (y in 0 until n) for (x in 0 until n) {
                val inCross = x == 2 || y == 2
                if (!inCross) continue
                p.alpha = if (y == 2 && (x == 0 || x == 4)) 255 else 150
                c.drawCircle(step * (x + 0.5f), step * (y + 0.5f), step * 0.32f, p)
            }
            p.alpha = 255
            return
        }
        if (style == UiStyle.PIXEL) {
            p.isAntiAlias = false
            c.drawRect(a, 0f, s - a, s, p)
            c.drawRect(0f, a, s, s - a, p)
        } else {
            c.drawRoundRect(a, 0f, s - a, s, r, r, p)
            c.drawRoundRect(0f, a, s, s - a, r, r, p)
        }
        // highlight left/right arms
        val t = android.graphics.Path().apply {
            moveTo(s * 0.08f, s / 2); lineTo(s * 0.24f, s * 0.38f); lineTo(s * 0.24f, s * 0.62f); close()
            moveTo(s * 0.92f, s / 2); lineTo(s * 0.76f, s * 0.38f); lineTo(s * 0.76f, s * 0.62f); close()
        }
        c.drawPath(t, hole)
    }
}

/** Visual style of Hugminn's own screens. */
enum class UiStyle(val label: String) { MODERN("Modern"), PIXEL("Pixel"), DOTS("Punktmatrix") }

/**
 * Shapes and fonts for the chosen style. "Pixel" (matching the Retroid Pocket Classic/Dual design
 * line) uses the NF Pixels font (SIL Open Font License) and notched, square-pixel corners.
 */
object Look {
    @Volatile private var regular: Typeface? = null
    @Volatile private var bold: Typeface? = null
    @Volatile private var dotRegular: Typeface? = null
    @Volatile private var dotBold: Typeface? = null

    private fun dotFont(ctx: Context, b: Boolean): Typeface {
        val cached = if (b) dotBold else dotRegular
        if (cached != null) return cached
        val tf = try {
            Typeface.Builder(ctx.assets, "fonts/Doto.ttf").setFontVariationSettings("'ROND' 100, 'wght' ${if (b) 900 else 700}").build()
        } catch (t: Throwable) {
            Typeface.MONOSPACE
        }
        if (b) dotBold = tf else dotRegular = tf
        return tf
    }

    fun pixel(ctx: Context) = Prefs(ctx).uiStyle == UiStyle.PIXEL

    private fun pixelFont(ctx: Context, b: Boolean): Typeface {
        val cached = if (b) bold else regular
        if (cached != null) return cached
        val tf = try {
            // NF Pixels: a light pixel font with all German letters (one weight)
            Typeface.createFromAsset(ctx.assets, "fonts/NFPixels.ttf")
        } catch (t: Throwable) {
            Typeface.MONOSPACE
        }
        if (b) bold = tf else regular = tf
        return tf
    }

    fun font(ctx: Context, pixel: Boolean, b: Boolean): Typeface = when {
        pixel -> pixelFont(ctx, b)
        Prefs(ctx).uiStyle == UiStyle.DOTS -> dotFont(ctx, b)
        else -> Typeface.create(Typeface.DEFAULT, if (b) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Background shape: rounded (modern) or with stepped pixel corners. */
    fun shape(ctx: Context, pixel: Boolean, color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): android.graphics.drawable.Drawable =
        if (pixel) PixelShape(color, ctx.dp(3).toFloat(), if (radius > ctx.dp(20)) 3 else 2, stroke.toFloat(), strokeColor)
        else if (stroke > 0 && Prefs(ctx).uiStyle == UiStyle.DOTS) DotOutline(color, radius, stroke.toFloat(), strokeColor)
        else rounded(color, radius, stroke, strokeColor)
}

/** Rectangle with stepped corners like pixel art; [steps] pixel steps of [unit] size per corner. */
class PixelShape(private val color: Int, private val unit: Float, private val steps: Int, private val stroke: Float, private val strokeColor: Int) :
    android.graphics.drawable.Drawable() {
    private val fill = android.graphics.Paint().apply { this.color = this@PixelShape.color }
    private val line = android.graphics.Paint().apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = stroke
        this.color = strokeColor
    }

    private fun path(r: android.graphics.RectF) = pixelPath(r, unit, steps)

    override fun draw(c: android.graphics.Canvas) {
        val b = android.graphics.RectF(bounds)
        if (android.graphics.Color.alpha(color) > 0) c.drawPath(path(b), fill)
        if (stroke > 0) {
            val h = stroke / 2
            c.drawPath(path(android.graphics.RectF(b.left + h, b.top + h, b.right - h, b.bottom - h)), line)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Black or white, whichever reads better on [bg]. */
fun onColor(bg: Int): Int {
    val l = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255
    return if (l > 0.6) 0xFF1B1B1F.toInt() else Color.WHITE
}

/** Rectangle outline with [n] stepped pixel corners of [u] size each. */
fun pixelPath(r: android.graphics.RectF, u: Float, n: Int, into: android.graphics.Path = android.graphics.Path()): android.graphics.Path {
    val p = into
    p.moveTo(r.left + u * n, r.top)
    p.lineTo(r.right - u * n, r.top)
    for (i in n - 1 downTo 0) { p.lineTo(r.right - u * i, r.top + u * (n - 1 - i)); p.lineTo(r.right - u * i, r.top + u * (n - i)) }
    p.lineTo(r.right, r.bottom - u * n)
    for (i in 1..n) { p.lineTo(r.right - u * (i - 1), r.bottom - u * (n - i)); p.lineTo(r.right - u * i, r.bottom - u * (n - i)) }
    p.lineTo(r.left + u * n, r.bottom)
    for (i in n - 1 downTo 0) { p.lineTo(r.left + u * i, r.bottom - u * (n - 1 - i)); p.lineTo(r.left + u * i, r.bottom - u * (n - i)) }
    p.lineTo(r.left, r.top + u * n)
    for (i in 1..n) { p.lineTo(r.left + u * (i - 1), r.top + u * (n - i)); p.lineTo(r.left + u * i, r.top + u * (n - i)) }
    p.close()
    return p
}

/**
 * Hugminn's own status bar (clock, title, battery) in the chosen style; used by the Game Switcher and
 * the Control Center so both look the same. Keeps the battery value current by itself.
 */
class StatusBand(ctx: Context, pal: Palette, title: String) : android.widget.LinearLayout(ctx) {
    private val battery = TextView(ctx)
    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) = update(i)
    }

    init {
        val pixel = Look.pixel(ctx)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(22), 0, ctx.dp(22), 0)
        addView(android.widget.TextClock(ctx).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(pal.onSurface)
            typeface = Look.font(ctx, pixel, true)
        })
        addView(TextView(ctx).apply {
            text = title
            typeface = Look.font(ctx, pixel, false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(pal.onSurfaceVariant)
            gravity = Gravity.CENTER
            maxLines = 1
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        battery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        battery.setTextColor(pal.onSurface)
        battery.typeface = Look.font(ctx, pixel, true)
        addView(battery)
        update(null)
    }

    private fun update(i: android.content.Intent?) {
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
        val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = i?.let {
            val st = it.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL
        } ?: (bm?.isCharging == true)
        battery.text = if (pct >= 0) "$pct %" + if (charging) " ⚡" else "" else ""
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching { context.registerReceiver(receiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) }
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(receiver) }
        super.onDetachedFromWindow()
    }
}

/** Dot-matrix outline: round dots evenly spaced along the shape's edge (same corners as the shape). */
class DotOutline(private val color: Int, private val radius: Float, private val dot: Float, private val dotColor: Int) :
    android.graphics.drawable.Drawable() {
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = this@DotOutline.color }
    private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = dot
        strokeCap = android.graphics.Paint.Cap.ROUND
        this.color = dotColor
    }

    override fun draw(c: android.graphics.Canvas) {
        val b = android.graphics.RectF(bounds)
        val r = radius.coerceAtMost(minOf(b.width(), b.height()) / 2)
        if (android.graphics.Color.alpha(color) > 0) c.drawRoundRect(b, r, r, fill)
        val h = dot / 2
        val e = android.graphics.RectF(b.left + h, b.top + h, b.right - h, b.bottom - h)
        val er = (r - h).coerceAtLeast(0f)
        val path = android.graphics.Path().apply { addRoundRect(e, er, er, android.graphics.Path.Direction.CW) }
        // Spread the dots evenly so the ring closes without a gap or a double dot.
        val len = android.graphics.PathMeasure(path, true).length
        val want = dot * 2.6f
        val n = (len / want).toInt().coerceAtLeast(4)
        line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.01f, len / n - 0.01f), 0f)
        c.drawPath(path, line)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
