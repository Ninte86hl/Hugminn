package io.github.ninte86hl.hugminn

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import java.util.ArrayDeque

/**
 * Screen and sound into H.264/AAC with the hardware encoders. Two outputs, both optional:
 * a video file (start/stop), and a ring buffer of the last seconds that can be saved at any time.
 */
class Recorder(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    fps: Int,
    bitrate: Int,
    private val audio: Capture.Audio,
    /** Seconds kept for "save the last seconds"; 0 = no ring buffer. */
    @Volatile var replaySec: Int,
) {
    private class Sample(val audio: Boolean, val data: ByteArray, val pts: Long, val flags: Int)

    private val thread = HandlerThread("hugminn-rec").apply { start() }
    private val handler = Handler(thread.looper)
    private val video: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var display: VirtualDisplay? = null
    private var aCodec: MediaCodec? = null
    private var audioThread: Thread? = null
    @Volatile private var running = true

    private var vFormat: MediaFormat? = null
    private var aFormat: MediaFormat? = null
    private val ring = ArrayDeque<Sample>()

    // the video file being written
    private var muxer: MediaMuxer? = null
    private var fileUri: Uri? = null
    private var fileFd: android.os.ParcelFileDescriptor? = null
    private var fileStarted = false
    private var fileBase = -1L
    private var vTrack = -1
    private var aTrack = -1

    init {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // a cut point every second for the ring buffer
            // a still screen still produces frames now and then, so the clip keeps its length
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
        }
        video.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                runCatching {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset); buf.get(data)
                        add(Sample(false, data, info.presentationTimeUs, info.flags))
                    }
                }
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {}
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                synchronized(this@Recorder) { vFormat = format; tryStartFile() }
            }
        }, handler)
        video.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = video.createInputSurface()
        video.start()
        display = projection.createVirtualDisplay("Hugminn", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        if (audio != Capture.Audio.NONE) runCatching { startAudio() }
    }

    // ---- sound ----

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val rate = 44100
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = (minBuf * 2).coerceAtLeast(8192)
        val game = if (audio == Capture.Audio.MIC) null else runCatching {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(bufSize).setAudioPlaybackCaptureConfig(cfg).build()
        }.getOrNull()
        val mic = if (audio == Capture.Audio.GAME_MIC || audio == Capture.Audio.MIC) runCatching {
            AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(fmt).setBufferSizeInBytes(bufSize).build()
        }.getOrNull() else null
        if (game == null && mic == null) return

        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        aCodec = codec
        game?.startRecording(); mic?.startRecording()

        audioThread = Thread {
            val chunk = 4096 // bytes: 1024 stereo frames
            val a = ByteArray(chunk)
            val b = ByteArray(chunk)
            val info = MediaCodec.BufferInfo()
            var frames = 0L
            val base = System.nanoTime() / 1000
            while (running) {
                val n = when {
                    game != null && mic != null -> {
                        val n1 = game.read(a, 0, chunk)
                        val n2 = mic.read(b, 0, chunk)
                        if (n1 > 0 && n2 > 0) mix(a, b, minOf(n1, n2)) else maxOf(n1, 0)
                    }
                    game != null -> game.read(a, 0, chunk)
                    else -> mic!!.read(a, 0, chunk)
                }
                if (n <= 0) continue
                val inIdx = runCatching { codec.dequeueInputBuffer(10_000) }.getOrDefault(-1)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    inBuf.clear()
                    val len = minOf(n, inBuf.capacity())
                    inBuf.put(a, 0, len)
                    codec.queueInputBuffer(inIdx, 0, len, base + frames * 1_000_000L / rate, 0)
                    frames += len / 4
                }
                while (true) {
                    val outIdx = runCatching { codec.dequeueOutputBuffer(info, 0) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized(this) { aFormat = codec.outputFormat; tryStartFile() }
                        continue
                    }
                    if (outIdx < 0) break
                    val out = codec.getOutputBuffer(outIdx)
                    if (out != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val data = ByteArray(info.size)
                        out.position(info.offset); out.get(data)
                        add(Sample(true, data, info.presentationTimeUs, info.flags))
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            runCatching { game?.stop(); game?.release() }
            runCatching { mic?.stop(); mic?.release() }
        }.apply { name = "hugminn-audio"; start() }
    }

    /** Game sound and microphone added together (16-bit, clipped). */
    private fun mix(a: ByteArray, b: ByteArray, n: Int): Int {
        var i = 0
        while (i + 1 < n) {
            val x = (a[i].toInt() and 0xFF) or (a[i + 1].toInt() shl 8)
            val y = (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)
            val s = (x + y).coerceIn(-32768, 32767)
            a[i] = s.toByte(); a[i + 1] = (s shr 8).toByte()
            i += 2
        }
        return n
    }

    private val hasAudio get() = aCodec != null

    // ---- samples ----

    @Synchronized
    private fun add(s: Sample) {
        if (replaySec > 0) {
            ring.addLast(s)
            val keep = (replaySec + 3) * 1_000_000L
            while (ring.isNotEmpty() && s.pts - ring.first().pts > keep) ring.removeFirst()
        } else ring.clear()
        writeFile(s)
    }

    // ---- video file ----

    @Synchronized
    fun startFile(): Boolean {
        if (muxer != null) return true
        val (uri, pfd) = newVideo(ctx) ?: return false
        fileUri = uri; fileFd = pfd
        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        fileStarted = false; fileBase = -1; vTrack = -1; aTrack = -1
        tryStartFile()
        // start the file on a fresh key frame
        runCatching { video.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        return true
    }

    private fun tryStartFile() {
        val m = muxer ?: return
        if (vTrack >= 0) return
        val vf = vFormat ?: return
        if (hasAudio && aFormat == null) return
        vTrack = m.addTrack(vf)
        aFormat?.takeIf { hasAudio }?.let { aTrack = m.addTrack(it) }
        m.start()
    }

    private fun writeFile(s: Sample) {
        val m = muxer ?: return
        if (vTrack < 0) return
        if (!fileStarted) {
            // the file begins with a key frame; earlier sound is left out
            if (s.audio || s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return
            fileStarted = true; fileBase = s.pts
        }
        if (s.pts < fileBase) return
        val track = if (s.audio) aTrack else vTrack
        if (track < 0) return
        write(m, track, s, fileBase)
    }

    private fun write(m: MediaMuxer, track: Int, s: Sample, base: Long) {
        val info = MediaCodec.BufferInfo().apply { set(0, s.data.size, s.pts - base, s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) }
        runCatching { m.writeSampleData(track, java.nio.ByteBuffer.wrap(s.data), info) }
    }

    /** Ends the video file; returns where it was saved (null if nothing was written). */
    @Synchronized
    fun stopFile(): Uri? {
        val m = muxer ?: return null
        muxer = null
        val ok = fileStarted
        runCatching { if (vTrack >= 0) m.stop() }
        runCatching { m.release() }
        runCatching { fileFd?.close() }
        val uri = fileUri
        fileFd = null; fileUri = null
        return finish(ctx, uri, ok)
    }

    val recordingFile: Boolean @Synchronized get() = muxer != null

    // ---- the last seconds ----

    /** Writes the ring buffer to a new video; null if there is nothing yet. */
    fun saveReplay(): Uri? {
        val samples: List<Sample>
        val vf: MediaFormat
        val af: MediaFormat?
        synchronized(this) {
            samples = ring.toList()
            vf = vFormat ?: return null
            af = aFormat?.takeIf { hasAudio }
        }
        if (samples.isEmpty()) return null
        val last = samples.last().pts
        val from = samples.indexOfFirst { !it.audio && it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 && it.pts >= last - replaySec * 1_000_000L }
            .takeIf { it >= 0 } ?: samples.indexOfFirst { !it.audio && it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }
        if (from < 0) return null
        val base = samples[from].pts
        val (uri, pfd) = newVideo(ctx) ?: return null
        var ok = false
        runCatching {
            val m = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vt = m.addTrack(vf)
            val at = af?.let { m.addTrack(it) } ?: -1
            m.start()
            for (i in from until samples.size) {
                val s = samples[i]
                if (s.pts < base) continue
                val t = if (s.audio) at else vt
                if (t >= 0) write(m, t, s, base)
            }
            m.stop(); m.release()
            ok = true
        }
        runCatching { pfd.close() }
        return finish(ctx, uri, ok)
    }

    fun release() {
        running = false
        runCatching { audioThread?.join(500) }
        runCatching { stopFile() }
        runCatching { display?.release() }
        runCatching { video.stop() }
        runCatching { video.release() }
        runCatching { aCodec?.stop() }
        runCatching { aCodec?.release() }
        runCatching { projection.stop() }
        thread.quitSafely()
    }

    companion object {
        /** A new, still hidden video in Movies/Hugminn. */
        fun newVideo(ctx: Context): Pair<Uri, android.os.ParcelFileDescriptor>? = runCatching {
            val name = "Hugminn_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".mp4"
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Hugminn")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
            uri to ctx.contentResolver.openFileDescriptor(uri, "rw")!!
        }.getOrNull()

        /** Makes the video visible in the gallery, or removes it if nothing was written. */
        fun finish(ctx: Context, uri: Uri?, ok: Boolean): Uri? {
            uri ?: return null
            if (!ok) { runCatching { ctx.contentResolver.delete(uri, null, null) }; return null }
            runCatching { ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) }
            return uri
        }
    }
}
