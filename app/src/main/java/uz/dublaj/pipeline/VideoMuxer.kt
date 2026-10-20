package uz.dublaj.pipeline

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Video oqimi (nusxa, qayta kodlanmaydi) + yangi audio (aralashma → AAC) → MP4.
 * Audio blok-blok (DubMixer) olinib kodlanadi va video bilan vaqt bo'yicha navbatlashtirib yoziladi.
 */
object VideoMuxer {

    private val MP4_VIDEO = setOf(
        "video/avc", "video/hevc", "video/mp4v-es", "video/3gpp", "video/av01"
    )

    fun mux(
        videoFile: File,
        outFile: File,
        sampleRate: Int,
        totalSamples: Long,
        mixer: DubMixer,
        onProgress: (Float) -> Unit = {}
    ): File {
        outFile.parentFile?.mkdirs()
        val part = File(outFile.parentFile, outFile.nameWithoutExtension + ".part.mp4")
        if (part.exists()) part.delete()

        val extractor = MediaExtractor()
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var ok = false
        try {
            extractor.setDataSource(videoFile.absolutePath)

            var vTrack = -1
            var vFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                    vTrack = i
                    vFormat = f
                    break
                }
            }
            if (vTrack < 0 || vFormat == null) throw IllegalStateException("Videoda video oqim topilmadi")
            val vMime = vFormat.getString(MediaFormat.KEY_MIME) ?: ""
            if (vMime !in MP4_VIDEO) {
                throw IllegalStateException(
                    "Video kodeki ($vMime) MP4 ga yozib bo'lmaydi. H.264/H.265 (MP4) video tanlang."
                )
            }
            extractor.selectTrack(vTrack)

            val mx = MediaMuxer(part.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            val rotation = readRotation(videoFile)
            if (rotation != 0) mx.setOrientationHint(rotation)
            val outV = mx.addTrack(vFormat)

            // ---- AAC kodlovchi
            val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder = enc
            enc.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            var outA = -1
            var inputDone = false
            var audioDone = false
            var samplesFed = 0L
            var lastAudioPts = 0L
            val aInfo = MediaCodec.BufferInfo()

            // Bitta qadam: kodlovchiga blok beradi va tayyor AAC paketlarni muxer'ga yozadi
            fun stepAudio() {
                if (!inputDone) {
                    val idx = enc.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        val buf = enc.getInputBuffer(idx)!!
                        buf.clear()
                        val pts = samplesFed * 1_000_000L / sampleRate
                        if (samplesFed >= totalSamples) {
                            enc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val n = min(min(buf.capacity() / 2, 4096).toLong(), totalSamples - samplesFed).toInt()
                            val block = mixer.mix(samplesFed, n)
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            buf.asShortBuffer().put(block)
                            enc.queueInputBuffer(idx, 0, n * 2, pts, 0)
                            samplesFed += n
                        }
                    }
                }
                val oi = enc.dequeueOutputBuffer(aInfo, 5_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outA = mx.addTrack(enc.outputFormat)
                        mx.start()
                        muxerStarted = true
                    }
                    oi >= 0 -> {
                        val ob = enc.getOutputBuffer(oi)!!
                        if (aInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) aInfo.size = 0
                        if (aInfo.size > 0 && muxerStarted) {
                            ob.position(aInfo.offset)
                            ob.limit(aInfo.offset + aInfo.size)
                            mx.writeSampleData(outA, ob, aInfo)
                            lastAudioPts = aInfo.presentationTimeUs
                        }
                        if (aInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) audioDone = true
                        enc.releaseOutputBuffer(oi, false)
                    }
                }
            }

            // Muxer audio trek formati ma'lum bo'lgach start bo'ladi
            var guard = 0
            while (!muxerStarted) {
                stepAudio()
                if (++guard > 5000) throw IllegalStateException("AAC kodlovchi javob bermadi")
            }

            // ---- video paketlarini nusxalash (audio bilan navbatlashtirib)
            val maxIn = if (vFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                vFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            var vBuf = ByteBuffer.allocate(max(maxIn, 2 * 1024 * 1024))
            val vInfo = MediaCodec.BufferInfo()
            val durUs = if (vFormat.containsKey(MediaFormat.KEY_DURATION))
                vFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                var size = 0
                while (true) {
                    try {
                        size = extractor.readSampleData(vBuf, 0)
                        break
                    } catch (e: IllegalArgumentException) {
                        vBuf = ByteBuffer.allocate(vBuf.capacity() * 2)   // katta kadr
                    }
                }
                if (size < 0) break
                val t = extractor.sampleTime
                while (!audioDone && lastAudioPts < t + 300_000L) stepAudio()
                vInfo.set(0, size, t, extractor.sampleFlags)
                mx.writeSampleData(outV, vBuf, vInfo)
                extractor.advance()
                if (durUs > 0) onProgress((t.toFloat() / durUs).coerceIn(0f, 0.99f))
            }
            while (!audioDone) stepAudio()

            mx.stop()
            muxerStarted = false
            ok = true
        } finally {
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            extractor.release()
            if (!ok) part.delete()
        }

        if (outFile.exists()) outFile.delete()
        if (!part.renameTo(outFile)) {
            part.copyTo(outFile, overwrite = true)
            part.delete()
        }
        Timber.i("Mux tayyor: ${outFile.absolutePath} (${outFile.length() / 1024} KB)")
        onProgress(1f)
        return outFile
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
