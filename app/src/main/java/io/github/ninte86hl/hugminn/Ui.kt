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
    /** Cocoon style: soft grey ground, white cards, colourful icon badges, mint green accent. */
    val cocoon = Prefs(ctx).uiStyle == UiStyle.COCOON
    /** Liquid Glass style: see-through tiles over the (blurred) wallpaper, clean type, corner marks. */
    val glass = Prefs(ctx).uiStyle == UiStyle.GLASS
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
    private val dotAccent = chosen ?: if (cocoon) (if (dark) 0xFF6CCB72.toInt() else 0xFF3FA348.toInt())
        else if (glass) (if (dark) 0xFF8CC4FF.toInt() else 0xFF1F6FE0.toInt())
        else if (dark) 0xFFE5262E.toInt() else 0xFFD71920.toInt()
    /** Styles other than dot matrix use the chosen colour like the dot matrix does, else Material You. */
    private val useDot = dots || cocoon || glass || chosen != null
    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    val primary = if (useDot) dotAccent else if (dark) sys("system_accent1_200", 0xFFFFB3AC.toInt()) else sys("system_accent1_600", 0xFFB3261E.toInt())
    val onPrimary = if (useDot) onColor(dotAccent) else if (dark) sys("system_accent1_800", 0xFF680003.toInt()) else sys("system_accent1_0", Color.WHITE)
    val primaryContainer = if (useDot) (if (dark) mix(Color.BLACK, dotAccent, 0.18f) else mix(Color.WHITE, dotAccent, 0.14f)) else if (dark) sys("system_accent1_700", 0xFF8C1D18.toInt()) else sys("system_accent1_100", 0xFFFFDAD6.toInt())
    val onPrimaryContainer = if (useDot) (if (dark) mix(Color.WHITE, dotAccent, 0.35f) else mix(Color.BLACK, dotAccent, 0.45f)) else if (dark) sys("system_accent1_100", 0xFFFFDAD6.toInt()) else sys("system_accent1_900", 0xFF410002.toInt())
    private val c = cocoon && !oled
    /** Liquid Glass: cool neutral greys and the accent for selections (not the system's tinted ones). */
    private val g = glass && !oled
    val secondaryContainer = if (glass) (if (dark) mix(0xFF262A31.toInt(), dotAccent, 0.32f) else mix(Color.WHITE, dotAccent, 0.22f)) else if (c) (if (dark) 0xFF363B45.toInt() else 0xFFE6E9EF.toInt()) else if (dots) (if (dark) 0xFF262626.toInt() else 0xFFE4E4E4.toInt()) else if (dark) sys("system_accent2_700", 0xFF5D3F3C.toInt()) else sys("system_accent2_100", 0xFFFFDAD6.toInt())
    val onSecondaryContainer = if (glass) (if (dark) Color.WHITE else 0xFF14171C.toInt()) else if (c) (if (dark) 0xFFF2F4F7.toInt() else 0xFF1F2328.toInt()) else if (dots) (if (dark) Color.WHITE else Color.BLACK) else if (dark) sys("system_accent2_100", 0xFFFFDAD6.toInt()) else sys("system_accent2_900", 0xFF2C1513.toInt())
    val surface = if (g) (if (dark) 0xFF14161A.toInt() else 0xFFEEF1F5.toInt()) else if (c) (if (dark) 0xFF181B21.toInt() else 0xFFECEEF3.toInt()) else if (dots) (if (dark) Color.BLACK else 0xFFF3F3F3.toInt()) else if (oled) Color.BLACK else if (dark) sys("system_neutral1_900", 0xFF201A19.toInt()) else sys("system_neutral1_10", 0xFFFFFBFF.toInt())
    val surfaceHigh = if (g) (if (dark) 0xFF22252B.toInt() else Color.WHITE) else if (c) (if (dark) 0xFF272B33.toInt() else Color.WHITE) else if (dots) (if (dark) 0xFF101010.toInt() else Color.WHITE) else if (oled) 0xFF111111.toInt() else if (dark) sys("system_neutral1_800", 0xFF362F2E.toInt()) else sys("system_neutral1_50", 0xFFFBEEEC.toInt())
    val surfaceHighest = if (g) (if (dark) 0xFF31353D.toInt() else 0xFFE1E5EB.toInt()) else if (c) (if (dark) 0xFF363B45.toInt() else 0xFFE3E6EC.toInt()) else if (dots) (if (dark) 0xFF242424.toInt() else 0xFFE2E2E2.toInt()) else if (oled) 0xFF1F1F1F.toInt() else if (dark) sys("system_neutral2_700", 0xFF534341.toInt()) else sys("system_neutral2_100", 0xFFF5DDDA.toInt())
    val onSurface = if (glass) (if (dark) 0xFFF3F5F8.toInt() else 0xFF16191E.toInt()) else if (c) (if (dark) 0xFFF2F4F7.toInt() else 0xFF1F2328.toInt()) else if (dots) (if (dark) Color.WHITE else Color.BLACK) else if (dark) sys("system_neutral1_100", 0xFFEDE0DE.toInt()) else sys("system_neutral1_900", 0xFF201A19.toInt())
    val onSurfaceVariant = if (glass) (if (dark) 0xFFAAB1BC.toInt() else 0xFF5A6170.toInt()) else if (c) (if (dark) 0xFFA7AEBA.toInt() else 0xFF596070.toInt()) else if (dots) (if (dark) 0xFF8C8C8C.toInt() else 0xFF6B6B6B.toInt()) else if (dark) sys("system_neutral2_200", 0xFFD8C2BF.toInt()) else sys("system_neutral2_700", 0xFF534341.toInt())
    val outline = if (g) (if (dark) 0xFF4A4F58.toInt() else 0xFFC6CCD5.toInt()) else if (c) (if (dark) 0xFF4A505C.toInt() else 0xFFC9CED8.toInt()) else if (dots) (if (dark) 0xFF3C3C3C.toInt() else 0xFFBDBDBD.toInt()) else if (dark) sys("system_neutral2_400", 0xFFA08C8A.toInt()) else sys("system_neutral2_500", 0xFF857371.toInt())
    val scrim = 0x99000000.toInt()

    /** Tile style: OLED black only means something in dark colour schemes, light ones stay standard. */
    val tileStyle = if (glass) TileStyle.GLASS
        else Prefs(ctx).tileStyle.let { if (it == TileStyle.OLED && !dark) TileStyle.STANDARD else it }
    /** Fill of tiles, cards and slider tracks (not of menus and dialogs: those always stay opaque). */
    val tile = when (tileStyle) {
        TileStyle.OLED -> Color.BLACK
        // smoked glass on dark themes, frosted on light ones: enough body for the text on top
        TileStyle.GLASS -> Prefs(ctx).glassLevel.let { g ->
            if (dark) Color.argb(intArrayOf(60, 90, 120, 155, 190)[g - 1], 24, 26, 30)
            else Color.argb(intArrayOf(110, 140, 175, 205, 230)[g - 1], 255, 255, 255)
        }
        TileStyle.STANDARD -> surfaceHigh
    }
    /** Edge of switched-off tiles (0 = none): keeps black or see-through tiles apart from the ground. */
    val tileEdge = when (tileStyle) {
        TileStyle.OLED -> 0xFF2C2C2C.toInt()
        TileStyle.GLASS -> if (dark) Color.argb(70, 255, 255, 255) else Color.argb(110, 255, 255, 255)
        TileStyle.STANDARD -> 0
    }
    /** Focus marks: corners (default of the Cocoon style) or the full frame. */
    val focusCorners = when (Prefs(ctx).focusStyle) {
        FocusStyle.CORNERS -> true
        FocusStyle.FRAME -> false
        FocusStyle.AUTO -> cocoon || glass
    }

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
    fun nintendoLayout(): Boolean = anbernicNs() ?: Device.aynNintendo ?: InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.name?.contains("Retroid Pocket Controller", ignoreCase = true) == true
    }

    /**
     * Anbernic: its own "Trigger Setting" – abxy_mode 0 "Ns mode", 1 "Xbox mode". Inside Hugminn's windows the
     * buttons arrive as printed (tested on the RG 406H, see [logicalRaw]); the mode decides which one confirms
     * and which glyphs "Automatisch" shows. Read live, it can change any time.
     */
    private fun anbernicNs(): Boolean? {
        if (!Device.isAnbernic) return null
        val ctx = Device.app ?: return null
        return Device.sys(ctx, "abxy_mode")?.trim()?.let { it == "0" }
    }

    /**
     * Inside Hugminn the two upper face buttons go by position, the same on every device and layout:
     * Y stands for the north button, X for the west one (the retro layout's X/Y are swapped on the
     * way in, see [logical]). A and B follow the layout: confirm and back as on that console.
     */
    fun posOf(keyCode: Int, nintendo: Boolean = nintendoLayout()): Pos? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> if (nintendo) Pos.RIGHT else Pos.BOTTOM
        KeyEvent.KEYCODE_BUTTON_B -> if (nintendo) Pos.BOTTOM else Pos.RIGHT
        KeyEvent.KEYCODE_BUTTON_X -> Pos.LEFT
        KeyEvent.KEYCODE_BUTTON_Y -> Pos.TOP
        else -> null
    }

    /** Last conversions (face buttons only), for the feedback: shows how a press was read. */
    val trace = java.util.concurrent.ConcurrentLinkedDeque<String>()

    fun note(s: String) {
        trace.addLast("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())} $s")
        while (trace.size > 30) trace.pollFirst()
    }

    /** The key code Hugminn works with: in the retro layout the north button (X) becomes Y, the west one (Y) X. */
    fun logical(code: Int): Int = logicalRaw(code).also { out ->
        if (code in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_Z) {
            val where = Throwable().stackTrace.drop(1).take(2).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
            trace.addLast("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())} " +
                "${KeyEvent.keyCodeToString(code)} → ${KeyEvent.keyCodeToString(out)} · ns=${anbernicNs()} nin=${nintendoLayout()} · $where")
            while (trace.size > 30) trace.pollFirst()
        }
    }

    private fun logicalRaw(code: Int): Int = anbernicNs()?.let { ns ->
        // Anbernic: while Hugminn is open its key mapper is paused, so the buttons arrive as printed
        // (A right, B bottom, X top, Y left) whatever the mode. North/west always by position;
        // Ns mode keeps A/B as printed, Xbox mode makes the bottom button confirm.
        when (code) {
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            KeyEvent.KEYCODE_BUTTON_A -> if (ns) code else KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B -> if (ns) code else KeyEvent.KEYCODE_BUTTON_A
            else -> code
        }
    } ?: if (!nintendoLayout()) code else when (code) {
        KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
        KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
        else -> code
    }

    fun logical(e: KeyEvent): KeyEvent {
        val c = logical(e.keyCode)
        return if (c == e.keyCode) e else KeyEvent(e.downTime, e.eventTime, e.action, c, e.repeatCount, e.metaState, e.deviceId, e.scanCode, e.flags, e.source)
    }

    private fun styleFor(prefs: Prefs, nintendo: Boolean) = when (prefs.glyphStyle) {
        GlyphStyle.AUTO -> if (nintendo) GlyphStyle.NINTENDO else GlyphStyle.XBOX
        else -> prefs.glyphStyle
    }

    fun glyph(prefs: Prefs, keyCode: Int): Glyph {
        val nintendo = nintendoLayout()
        val pos = posOf(keyCode, nintendo) ?: return Glyph(keyLabel(keyCode, styleFor(prefs, nintendo)), null, 0)
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

    /** Shoulder and menu buttons the way each style names them. */
    private fun keyLabel(k: Int, style: GlyphStyle): String {
        val retro = style == GlyphStyle.NINTENDO
        val alt = style == GlyphStyle.PLAYSTATION
        return when (k) {
            KeyEvent.KEYCODE_BUTTON_L1 -> if (retro) "L" else if (alt) "L1" else "LB"
            KeyEvent.KEYCODE_BUTTON_R1 -> if (retro) "R" else if (alt) "R1" else "RB"
            KeyEvent.KEYCODE_BUTTON_L2 -> if (retro) "ZL" else if (alt) "L2" else "LT"
            KeyEvent.KEYCODE_BUTTON_R2 -> if (retro) "ZR" else if (alt) "R2" else "RT"
            KeyEvent.KEYCODE_BUTTON_START -> if (retro) "+" else if (alt) "≡" else "☰"
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (retro) "−" else if (alt) "⇧" else "⧉"
            else -> keyLabelCommon(k)
        }
    }

    private fun keyLabelCommon(k: Int) = when (k) {
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_BUTTON_MODE -> "⌂"
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
enum class UiStyle(private val labelDe: String) { MODERN("Modern"), PIXEL("Pixel"), DOTS("Punktmatrix"), COCOON("Cocoon"), GLASS("Liquid Glass");

    val label get() = tr(labelDe)
}

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

    @Volatile private var interRegular: Typeface? = null
    @Volatile private var interBold: Typeface? = null

    private fun interFont(ctx: Context, b: Boolean): Typeface {
        val cached = if (b) interBold else interRegular
        if (cached != null) return cached
        val tf = try {
            // Inter (SIL Open Font License): clean and friendly, close to Cocoon's look
            Typeface.createFromAsset(ctx.assets, if (b) "fonts/Inter-ExtraBold.otf" else "fonts/Inter-Medium.otf")
        } catch (t: Throwable) {
            Typeface.create(Typeface.DEFAULT, if (b) Typeface.BOLD else Typeface.NORMAL)
        }
        if (b) interBold = tf else interRegular = tf
        return tf
    }

    fun cocoon(ctx: Context) = Prefs(ctx).uiStyle == UiStyle.COCOON

    /** Background of a whole screen: plain, or Cocoon's soft diagonal stripes. */
    /**
     * Capsule under the button hints on a picture: made like the tiles (standard, OLED black with its
     * edge, or glass), so the bar belongs to the rest instead of being a dark block of its own.
     */
    fun capsule(ctx: Context, pal: Palette): android.graphics.drawable.Drawable {
        val edge = if (pal.tileEdge != 0) pal.tileEdge else 0
        return rounded(pal.tile, 999f, if (edge != 0) (ctx.resources.displayMetrics.density * 1.5f).toInt().coerceAtLeast(1) else 0, edge)
    }

    fun ground(ctx: Context, pal: Palette): android.graphics.drawable.Drawable =
        Backdrop.drawable(ctx, pal) ?: if (pal.glass) {
            // no picture yet: a soft colour wash, so the glass has something to show
            GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (pal.dark) intArrayOf(0xFF1B2440.toInt(), 0xFF2A1F45.toInt(), 0xFF14303A.toInt())
                else intArrayOf(0xFFDCE8FF.toInt(), 0xFFEADFFF.toInt(), 0xFFD8F1EE.toInt()))
        } else if (pal.cocoon && !pal.oled) Stripes(pal.surface, if (pal.dark) 0xFF1C1F26.toInt() else 0xFFE5E8EE.toInt(), ctx.dp(34).toFloat())
        else android.graphics.drawable.ColorDrawable(pal.surface)

    /** Icon badge colours of the Cocoon style, per tile. */
    fun badge(id: String, accent: Int): IntArray = when (id) {
        "devbat" -> intArrayOf(0xFF9BE58C.toInt(), 0xFF34B24A.toInt())
        "wifi" -> intArrayOf(0xFF7CC4FF.toInt(), 0xFF3D8BFD.toInt())
        "bt" -> intArrayOf(0xFF9FA8FF.toInt(), 0xFF5865F2.toInt())
        "leds" -> intArrayOf(0xFFFF9EC0.toInt(), 0xFFF2558A.toInt())
        "dnd", "theme" -> intArrayOf(0xFFB49CFF.toInt(), 0xFF7C5CF2.toInt())
        "perf", "chargesep" -> intArrayOf(0xFFFFB37A.toInt(), 0xFFFF7A3D.toInt())
        "fan", "clean" -> intArrayOf(0xFF6EE3D3.toInt(), 0xFF1FB5A1.toInt())
        "airplane", "mouse" -> intArrayOf(0xFF8FD8FF.toInt(), 0xFF2EA8E6.toInt())
        "rotation", "landscape", "screen" -> intArrayOf(0xFFFFD36E.toInt(), 0xFFF5A623.toInt())
        "refresh", "controller", "trigger", "keymapper" -> intArrayOf(0xFF8DE08F.toInt(), 0xFF43A047.toInt())
        "media" -> intArrayOf(0xFFFF9E80.toInt(), 0xFFFF5A5F.toInt())
        "screenshot", "homelock", "vibration", "sleep" -> intArrayOf(0xFFFFA3A3.toInt(), 0xFFE5484D.toInt())
        else -> intArrayOf(accent, accent)
    }

    fun font(ctx: Context, pixel: Boolean, b: Boolean): Typeface = when {
        pixel -> pixelFont(ctx, b)
        Prefs(ctx).uiStyle == UiStyle.DOTS -> dotFont(ctx, b)
        Prefs(ctx).uiStyle == UiStyle.COCOON || Prefs(ctx).uiStyle == UiStyle.GLASS -> interFont(ctx, b)
        else -> Typeface.create(Typeface.DEFAULT, if (b) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Background shape: rounded (modern) or with stepped pixel corners. */
    fun shape(ctx: Context, pixel: Boolean, color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): android.graphics.drawable.Drawable =
        if (pixel) PixelShape(color, ctx.dp(3).toFloat(), if (radius > ctx.dp(20)) 3 else 2, stroke.toFloat(), strokeColor)
        else if (stroke > 0 && Prefs(ctx).uiStyle == UiStyle.DOTS) DotOutline(color, radius, stroke.toFloat(), strokeColor)
        else if (Prefs(ctx).uiStyle == UiStyle.COCOON) rounded(color, if (radius >= 999f) radius else radius * 1.35f, stroke, strokeColor)
        else rounded(color, radius, stroke, strokeColor)
}

/** Cocoon-like background: soft diagonal stripes. */
class Stripes(private val bg: Int, private val stripe: Int, private val width: Float) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = stripe; strokeWidth = width }

    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        c.drawColor(bg)
        c.save()
        c.clipRect(b)
        // lines at 60°, every two widths
        val h = b.height().toFloat()
        val dx = h / Math.tan(Math.toRadians(60.0)).toFloat()
        var x = b.left - dx - width
        while (x < b.right + width) {
            c.drawLine(x, b.bottom.toFloat(), x + dx, b.top.toFloat(), p)
            x += width * 2.6f
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
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
            setTextColor(if (Backdrop.active(ctx)) pal.onSurface else pal.onSurfaceVariant)
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


/**
 * Focus mark made of four corner brackets that follow the element's rounded corners. It settles in
 * once (the brackets grow to their length and fade in, about 180 ms) and then stays still.
 */
class CornerFrame(private val color: Int, private val radius: Float, private val width: Float, private val settle: Boolean = true) :
    android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = width
        strokeCap = android.graphics.Paint.Cap.ROUND
        this.color = this@CornerFrame.color
    }
    private var t = if (settle) 0f else 1f
    private val anim = if (settle) android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 180
        interpolator = android.view.animation.DecelerateInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidateSelf() }
    } else null
    private var started = false

    override fun draw(c: android.graphics.Canvas) {
        if (!started) { started = true; anim?.start() }
        val b = android.graphics.RectF(bounds)
        val h = width / 2
        b.inset(h, h)
        if (b.width() <= 0 || b.height() <= 0) return
        val r = (radius - h).coerceIn(0f, minOf(b.width(), b.height()) / 2)
        // straight part of each bracket: a share of the shorter side, grown in while settling
        val full = minOf(b.width(), b.height()) * 0.22f
        val len = full * (0.45f + 0.55f * t)
        paint.alpha = (android.graphics.Color.alpha(color) * (0.25f + 0.75f * t)).toInt()
        if (r >= minOf(b.width(), b.height()) / 2 - 0.5f && b.width() - b.height() < 1f && b.height() - b.width() < 1f) {
            // a round element: four short arcs on the diagonals instead of brackets
            val sweep = 44f * (0.5f + 0.5f * t)
            for (mid in floatArrayOf(225f, 315f, 45f, 135f)) c.drawArc(b, mid - sweep / 2, sweep, false, paint)
            return
        }
        val p = android.graphics.Path()
        val l = b.left; val tp = b.top; val rt = b.right; val bt = b.bottom
        // straight parts never run past the middle of a side (pill shapes have none there)
        val lh = len.coerceAtMost(b.width() / 2 - r).coerceAtLeast(0f)
        val lv = len.coerceAtMost(b.height() / 2 - r).coerceAtLeast(0f)
        // top left
        p.moveTo(l, tp + r + lv); p.lineTo(l, tp + r)
        p.arcTo(android.graphics.RectF(l, tp, l + 2 * r, tp + 2 * r), 180f, 90f); p.lineTo(l + r + lh, tp)
        // top right
        p.moveTo(rt - r - lh, tp); p.lineTo(rt - r, tp)
        p.arcTo(android.graphics.RectF(rt - 2 * r, tp, rt, tp + 2 * r), 270f, 90f); p.lineTo(rt, tp + r + lv)
        // bottom right
        p.moveTo(rt, bt - r - lv); p.lineTo(rt, bt - r)
        p.arcTo(android.graphics.RectF(rt - 2 * r, bt - 2 * r, rt, bt), 0f, 90f); p.lineTo(rt - r - lh, bt)
        // bottom left
        p.moveTo(l + r + lh, bt); p.lineTo(l + r, bt)
        p.arcTo(android.graphics.RectF(l, bt - 2 * r, l + 2 * r, bt), 90f, 90f); p.lineTo(l, bt - r - lv)
        c.drawPath(p, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
