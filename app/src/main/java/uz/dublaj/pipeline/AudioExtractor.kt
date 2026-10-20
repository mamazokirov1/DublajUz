package uz.dublaj.pipeline

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import timber.log.Timber
import uz.dublaj.util.FileUtils
import uz.dublaj.util.StreamResampler
import uz.dublaj.util.WavWriter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Videodan mono 16-bit PCM WAV ajratadi (MediaExtractor + MediaCodec), oqim bilan:
 * butun audio xotirada saqlanmaydi, shuning uchun uzun videolar ham ishlaydi.
 */
object AudioExtractor {

    data class AudioInfo(
        val path: File,
        val sampleRate: Int,
        val channels: Int,
        val durationSec: Double
    )

    fun extractToWav(
        videoFile: File,
        outWav: File,
        targetSampleRate: Int = 16000,
        onProgress: (Float) -> Unit = {}
    ): AudioInfo {
        if (!videoFile.isFile) throw IllegalArgumentException("Video topilmadi: $videoFile")
        outWav.parentFile?.mkdirs()

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var writer: WavWriter? = null
        try {
            extractor.setDataSource(videoFile.absolutePath)
            val track = selectAudioTrack(extractor)
                ?: throw IllegalStateException("Videoda audio oqim yo'q")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("MIME noma'lum")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L

            if (durationUs > 0) {
                val estBytes = (durationUs / 1_000_000.0 * targetSampleRate * 2).toLong() + 20L * 1024 * 1024
                FileUtils.ensureSpace(outWav.parentFile!!, estBytes)
            }

            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            Timber.i("Audio: mime=$mime sr=$srcRate ch=$channels dur=${"%.1f".format(durationUs / 1e6)}s")

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            writer = WavWriter(outWav, targetSampleRate)
            var resampler = StreamResampler(srcRate, targetSampleRate)
            var totalOut = 0L

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = c.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = c.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) {
                                onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 0.99f))
                            }
                            extractor.advance()
                        }
                    }
                }

                val outIndex = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        idle = 0
                        if (info.size > 0) {
                            val outBuf = c.getOutputBuffer(outIndex)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val mono = decodeToMono(outBuf, channels, isFloat)
                            val res = resampler.process(mono)
                            writer.write(res)
                            totalOut += res.size
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        c.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Haqiqiy chiqish formati (HE-AAC kabilarda kirishdan farq qiladi)
                        val of = c.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler = StreamResampler(srcRate, targetSampleRate)
                        Timber.d("Decoder format: $of")
                    }
                    else -> {
                        if (inputDone && ++idle > 1000) {
                            Timber.w("Decoder EOS bermadi — to'xtatildi")
                            outputDone = true
                        }
                    }
                }
            }

            onProgress(1f)
            writer.close()
            writer = null
            Timber.i("WAV yozildi: ${outWav.absolutePath} (${outWav.length()} bayt, $targetSampleRate Hz)")
            return AudioInfo(outWav, targetSampleRate, 1, totalOut.toDouble() / targetSampleRate)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { writer?.close() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun decodeToMono(buf: ByteBuffer, channels: Int, isFloat: Boolean): ShortArray {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val ch = channels.coerceAtLeast(1)
        return if (isFloat) {
            val fb = buf.asFloatBuffer()
            val frames = fb.remaining() / ch
            ShortArray(frames) { i ->
                var s = 0f
                for (c in 0 until ch) s += fb.get(i * ch + c)
                (s / ch * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            }
        } else {
            val sb = buf.asShortBuffer()
            val frames = sb.remaining() / ch
            ShortArray(frames) { i ->
                var s = 0
                for (c in 0 until ch) s += sb.get(i * ch + c).toInt()
                (s / ch).toShort()
            }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    /** Video davomiyligini (soniya) olish. */
    fun probeDuration(videoFile: File): Double {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(videoFile.absolutePath)
            var maxDur = 0L
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.containsKey(MediaFormat.KEY_DURATION)) {
                    maxDur = maxOf(maxDur, f.getLong(MediaFormat.KEY_DURATION))
                }
            }
            maxDur / 1_000_000.0
        } finally {
            extractor.release()
        }
    }
}
