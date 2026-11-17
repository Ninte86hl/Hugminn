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

/** Material You colours taken from the system palette (falls back to Hugminn red on older Android). */
class Palette(private val ctx: Context) {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun sys(name: String, fallback: Int): Int {
        if (Build.VERSION.SDK_INT < 31) return fallback
        val id = ctx.resources.getIdentifier(name, "color", "android")
        return if (id != 0) ctx.getColor(id) else fallback
    }

    val primary = if (dark) sys("system_accent1_200", 0xFFFFB3AC.toInt()) else sys("system_accent1_600", 0xFFB3261E.toInt())
    val onPrimary = if (dark) sys("system_accent1_800", 0xFF680003.toInt()) else sys("system_accent1_0", Color.WHITE)
    val primaryContainer = if (dark) sys("system_accent1_700", 0xFF8C1D18.toInt()) else sys("system_accent1_100", 0xFFFFDAD6.toInt())
    val onPrimaryContainer = if (dark) sys("system_accent1_100", 0xFFFFDAD6.toInt()) else sys("system_accent1_900", 0xFF410002.toInt())
    val secondaryContainer = if (dark) sys("system_accent2_700", 0xFF5D3F3C.toInt()) else sys("system_accent2_100", 0xFFFFDAD6.toInt())
    val onSecondaryContainer = if (dark) sys("system_accent2_100", 0xFFFFDAD6.toInt()) else sys("system_accent2_900", 0xFF2C1513.toInt())
    val surface = if (dark) sys("system_neutral1_900", 0xFF201A19.toInt()) else sys("system_neutral1_10", 0xFFFFFBFF.toInt())
    val surfaceHigh = if (dark) sys("system_neutral1_800", 0xFF362F2E.toInt()) else sys("system_neutral1_50", 0xFFFBEEEC.toInt())
    val surfaceHighest = if (dark) sys("system_neutral2_700", 0xFF534341.toInt()) else sys("system_neutral2_100", 0xFFF5DDDA.toInt())
    val onSurface = if (dark) sys("system_neutral1_100", 0xFFEDE0DE.toInt()) else sys("system_neutral1_900", 0xFF201A19.toInt())
    val onSurfaceVariant = if (dark) sys("system_neutral2_200", 0xFFD8C2BF.toInt()) else sys("system_neutral2_700", 0xFF534341.toInt())
    val outline = if (dark) sys("system_neutral2_400", 0xFFA08C8A.toInt()) else sys("system_neutral2_500", 0xFF857371.toInt())
    val scrim = 0x99000000.toInt()
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

    /** Label and colour (0 = neutral) for a face button. */
    fun glyph(prefs: Prefs, keyCode: Int): Pair<String, Int> {
        val nintendo = nintendoLayout()
        val pos = posOf(keyCode, nintendo) ?: return keyLabel(keyCode) to 0
        val (label, color) = when (styleFor(prefs, nintendo)) {
            GlyphStyle.PLAYSTATION -> when (pos) {
                Pos.BOTTOM -> "✕" to 0xFF7DA7E8.toInt()
                Pos.RIGHT -> "○" to 0xFFE86A6A.toInt()
                Pos.LEFT -> "□" to 0xFFD88FD0.toInt()
                Pos.TOP -> "△" to 0xFF3EC1A4.toInt()
            }
            GlyphStyle.NINTENDO -> when (pos) {
                Pos.BOTTOM -> "B" to 0xFFE5C33A.toInt()
                Pos.RIGHT -> "A" to 0xFFE5403A.toInt()
                Pos.LEFT -> "Y" to 0xFF3DBB4B.toInt()
                Pos.TOP -> "X" to 0xFF2F7BEA.toInt()
            }
            else -> when (pos) {
                Pos.BOTTOM -> "A" to 0xFF3DBB4B.toInt()
                Pos.RIGHT -> "B" to 0xFFE5403A.toInt()
                Pos.LEFT -> "X" to 0xFF2F7BEA.toInt()
                Pos.TOP -> "Y" to 0xFFF4C20D.toInt()
            }
        }
        return label to if (prefs.glyphColored) color else 0
    }

    private fun keyLabel(k: Int) = when (k) {
        KeyEvent.KEYCODE_BUTTON_L1 -> "LB"
        KeyEvent.KEYCODE_BUTTON_R1 -> "RB"
        KeyEvent.KEYCODE_BUTTON_START -> "☰"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "⧉"
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> "◀▶"
        else -> "?"
    }

    /** A round button chip like on the controller. */
    fun chip(ctx: Context, prefs: Prefs, pal: Palette, keyCode: Int): TextView {
        val (label, color) = glyph(prefs, keyCode)
        val size = ctx.dp(22)
        return TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 9f else 12f)
            val bg = if (color != 0) color else pal.onSurface
            setTextColor(if (color != 0) Color.WHITE else pal.surface)
            background = rounded(bg, size / 2f)
            minWidth = size
            minHeight = size
            setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
        }
    }
}
