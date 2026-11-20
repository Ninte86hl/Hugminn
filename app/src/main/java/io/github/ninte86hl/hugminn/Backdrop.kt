package io.github.ninte86hl.hugminn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File

/**
 * Picture behind the Control Center and the switcher: the Android wallpaper (copied with root) or a
 * picture of the user's own (copied in, so the original may move or vanish). It is blurred once and
 * kept in memory; a veil darkens it (dark themes) or lightens it (light themes) so text stays readable.
 */
object Backdrop {
    /** Longest side the picture is kept at: plenty for a handheld screen, small in memory. */
    private const val MAX_SIDE = 1920

    fun imageFile(ctx: Context) = File(ctx.filesDir, "backdrop.jpg")
    fun wallFile(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")

    private var cache: Pair<String, Bitmap>? = null

    /** The background as a drawable, or null when the style's own ground is used. */
    /**
     * The source in use: the chosen one; with the colour scheme Liquid Glass and no picture chosen,
     * the Android wallpaper (or else the own picture), since glass needs something behind it.
     */
    fun source(ctx: Context): BackdropSource {
        val p = Prefs(ctx)
        if (p.backdrop != BackdropSource.STYLE || p.theme != Theme.GLASS) return p.backdrop
        return when {
            wallFile(ctx).exists() -> BackdropSource.WALLPAPER
            imageFile(ctx).exists() -> BackdropSource.IMAGE
            else -> BackdropSource.STYLE
        }
    }

    /** For Liquid Glass: copies the wallpaper once if there is no picture yet (root). */
    fun prepareGlass(ctx: Context, done: () -> Unit) {
        if (Prefs(ctx).theme == Theme.GLASS && source(ctx) == BackdropSource.STYLE && Root.available()) fetchWallpaper(ctx) { done() }
    }

    fun drawable(ctx: Context, pal: Palette): Drawable? {
        val p = Prefs(ctx)
        val f = when (source(ctx)) {
            BackdropSource.STYLE -> return null
            BackdropSource.IMAGE -> imageFile(ctx)
            BackdropSource.WALLPAPER -> wallFile(ctx)
        }
        if (!f.exists()) return null
        val blur = p.backdropBlur.coerceIn(0, 3)
        val key = "${f.path}|${f.lastModified()}|$blur"
        val bmp = cache?.takeIf { it.first == key }?.second
            ?: runCatching { load(f, blur) }.getOrNull()?.also { cache = key to it }
            ?: return null
        // glass tiles need some calm behind them, whatever the slider says
        val pct = p.backdropDim.coerceIn(0, 80).let { if (pal.tileStyle == TileStyle.GLASS) it.coerceAtLeast(30) else it }
        val veil = ((pct * 255) / 100 shl 24) or (if (pal.dark) 0x000000 else 0xFFFFFF)
        return Picture(bmp, veil)
    }

    /** True when a picture lies behind the overlays (texts on the ground then need more contrast). */
    fun active(ctx: Context): Boolean = when (source(ctx)) {
        BackdropSource.STYLE -> false
        BackdropSource.IMAGE -> imageFile(ctx).exists()
        BackdropSource.WALLPAPER -> wallFile(ctx).exists()
    }

    /** Drops the decoded picture (after a new one was chosen or the blur changed). */
    fun forget() { cache = null }

    /** Copies a picture the user picked (content Uri) into Hugminn's own storage, scaled down. */
    fun importImage(ctx: Context, uri: Uri): Boolean = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val sample = sampleFor(opts.outWidth, opts.outHeight)
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        save(fit(bmp), imageFile(ctx))
        forget()
        true
    }.getOrDefault(false)

    /** Copies the Android wallpaper with root (apps may not read it themselves). Runs off the main thread. */
    fun fetchWallpaper(ctx: Context, done: (Boolean) -> Unit) {
        Thread {
            val tmp = File(ctx.cacheDir, "wallpaper.raw")
            val ok = runCatching {
                Root.exec("cat /data/system/users/0/wallpaper > ${Root.quote(tmp.path)} && chmod 644 ${Root.quote(tmp.path)}", 8_000)
                if (!tmp.exists() || tmp.length() == 0L) return@runCatching false
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tmp.path, opts)
                val bmp = BitmapFactory.decodeFile(tmp.path, BitmapFactory.Options().apply { inSampleSize = sampleFor(opts.outWidth, opts.outHeight) })
                    ?: return@runCatching false
                save(fit(bmp), wallFile(ctx))
                forget()
                true
            }.getOrDefault(false)
            tmp.delete()
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(ok) }
        }.start()
    }

    private fun sampleFor(w: Int, h: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= MAX_SIDE) s *= 2
        return s
    }

    private fun fit(b: Bitmap): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= MAX_SIDE) return b
        val f = MAX_SIDE.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    private fun save(b: Bitmap, f: File) {
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.renameTo(f)
    }

    /**
     * Decodes and blurs: the picture is shrunk (the stronger the blur, the smaller), box-blurred
     * three times (close to a Gaussian) and later drawn stretched with filtering.
     */
    private fun load(f: File, blur: Int): Bitmap? {
        val src = BitmapFactory.decodeFile(f.path) ?: return null
        if (blur == 0) return src
        val (div, radius) = when (blur) { 1 -> 4 to 2; 2 -> 6 to 3; else -> 8 to 5 }
        val small = Bitmap.createScaledBitmap(src, (src.width / div).coerceAtLeast(8), (src.height / div).coerceAtLeast(8), true)
        if (small !== src) src.recycle()
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            boxBlur(px, tmp, w, h, radius, true)
            boxBlur(tmp, px, w, h, radius, false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        return out
    }

    /** One pass of a box blur, along rows (horizontal) or columns. */
    private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int) = if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val c = src[idx(i)]; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF }
            for (i in 0 until len) {
                dst[idx(i)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[idx(i - r)]
                val inn = src[idx(i + r + 1)]
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    /** Draws the picture like "center crop" and the veil over it. */
    private class Picture(private val bmp: Bitmap, private val veil: Int) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val veilPaint = Paint().apply { color = veil }
        private val srcRect = Rect()

        override fun draw(c: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val scale = maxOf(b.width().toFloat() / bmp.width, b.height().toFloat() / bmp.height)
            val sw = (b.width() / scale).toInt()
            val sh = (b.height() / scale).toInt()
            val x = (bmp.width - sw) / 2
            val y = (bmp.height - sh) / 2
            srcRect.set(x, y, x + sw, y + sh)
            c.drawBitmap(bmp, srcRect, b, paint)
            if (veil ushr 24 != 0) c.drawRect(b, veilPaint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}
