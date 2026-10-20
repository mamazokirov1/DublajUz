package uz.dublaj.pipeline

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.view.Surface
import timber.log.Timber
import java.io.File

/**
 * MP4 ga yozib bo'lmaydigan kodeklar (VP9, VP8 ...) uchun: video oqimini H.264 ga qayta kodlaydi.
 * Dekoder → (Surface) → H.264 enkoder. Faqat video trek yoziladi (audio keyin aralashtiriladi).
 * Eslatma: VP9 odatda dasturiy dekoder bilan ochiladi, shuning uchun uzun/katta video sekinroq bo'ladi.
 */
object VideoTranscoder {
    private const val TIMEOUT_US = 5_000L
    private const val IDLE_LIMIT_NS = 30_000_000_000L

    fun toH264(src: File, dest: File, onProgress: (Float) -> Unit = {}): File {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var ok = false
        try {
            extractor.setDataSource(src.absolutePath)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) throw IllegalStateException("Videoda video oqim topilmadi")
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("Video MIME yo'q")
            extractor.selectTrack(track)

            val w = fmt.getInteger(MediaFormat.KEY_WIDTH) and 1.inv()
            val h = fmt.getInteger(MediaFormat.KEY_HEIGHT) and 1.inv()
            val fps = readFps(fmt)
            val bitrate = (w.toLong() * h * fps * 0.12).toLong().coerceIn(1_500_000L, 16_000_000L).toInt()
            val durUs = readDuration(fmt)
            Timber.i("Transcode $mime ${w}x$h @${fps}fps → H.264 ${bitrate / 1000} kbps")

            // ---- H.264 enkoder (kirish — Surface)
            val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = enc
            enc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surf = enc.createInputSurface()
            inputSurface = surf
            enc.start()

            // ---- dekoder (chiqish — enkoder Surface'i)
            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(fmt, surf, null, 0)
            dec.start()

            val mx = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            val rotation = readRotation(src)
            if (rotation != 0) mx.setOrientationHint(rotation)

            val di = MediaCodec.BufferInfo()
            val ei = MediaCodec.BufferInfo()
            var inputDone = false
            var decDone = false
            var encDone = false
            var outTrack = -1
            var lastActivity = System.nanoTime()

            while (!encDone) {
                var active = false

                // 1) extractor → dekoder
                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = dec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        active = true
                    }
                }

                // 2) dekoder → Surface (enkoderga)
                if (!decDone) {
                    val oi = dec.dequeueOutputBuffer(di, TIMEOUT_US)
                    if (oi >= 0) {
                        val eos = di.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val render = di.size > 0
                        if (render) dec.releaseOutputBuffer(oi, di.presentationTimeUs * 1000L)
                        else dec.releaseOutputBuffer(oi, false)
                        if (render && durUs > 0) {
                            onProgress((di.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 0.99f))
                        }
                        if (eos) {
                            decDone = true
                            enc.signalEndOfInputStream()
                        }
                        active = true
                    }
                }

                // 3) enkoder → muxer
                val eo = enc.dequeueOutputBuffer(ei, TIMEOUT_US)
                when {
                    eo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outTrack = mx.addTrack(enc.outputFormat)
                        mx.start()
                        muxerStarted = true
                        active = true
                    }
                    eo >= 0 -> {
                        val ob = enc.getOutputBuffer(eo)!!
                        if (ei.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) ei.size = 0
                        if (ei.size > 0 && muxerStarted) {
                            ob.position(ei.offset)
                            ob.limit(ei.offset + ei.size)
                            mx.writeSampleData(outTrack, ob, ei)
                        }
                        if (ei.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encDone = true
                        enc.releaseOutputBuffer(eo, false)
                        active = true
                    }
                }

                val now = System.nanoTime()
                if (active) lastActivity = now
                else if (now - lastActivity > IDLE_LIMIT_NS) {
                    throw IllegalStateException("Videoni H.264 ga qayta kodlash javob bermadi")
                }
            }

            if (!muxerStarted) throw IllegalStateException("Qayta kodlashda video kadr chiqmadi")
            mx.stop()
            muxerStarted = false
            ok = true
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { inputSurface?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            extractor.release()
            if (!ok) dest.delete()
        }
        onProgress(1f)
        Timber.i("Transcode tayyor: ${dest.absolutePath} (${dest.length() / 1024} KB)")
        return dest
    }

    private fun readFps(f: MediaFormat): Int {
        if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) return 30
        return try {
            f.getInteger(MediaFormat.KEY_FRAME_RATE)
        } catch (_: Exception) {
            try { f.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() } catch (_: Exception) { 30 }
        }.coerceIn(10, 60)
    }

    private fun readDuration(f: MediaFormat): Long {
        if (!f.containsKey(MediaFormat.KEY_DURATION)) return 0L
        return try {
            f.getLong(MediaFormat.KEY_DURATION)
        } catch (_: Exception) {
            try { f.getInteger(MediaFormat.KEY_DURATION).toLong() } catch (_: Exception) { 0L }
        }
    }

    private fun readRotation(file: File): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}
