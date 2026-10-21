package uz.dublaj.util

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Tayyor videodan qo'shimcha fayllar: audio (M4A/AAC) va subtitr. */
object ExportUtils {

    fun srtFile(video: File, uzbek: Boolean) =
        File(video.parentFile, video.nameWithoutExtension + if (uzbek) ".uz.srt" else ".orig.srt")

    fun m4aFile(video: File) = File(video.parentFile, video.nameWithoutExtension + ".m4a")

    /**
     * Videodagi AAC audio trekni qayta kodlamasdan M4A fayliga ko'chiradi (tez, sifat yo'qolmaydi).
     * MP3 Androidda tizim darajasida yo'q, M4A esa deyarli hamma pleer va Telegram'da ishlaydi.
     */
    fun ensureM4a(video: File): File {
        val out = m4aFile(video)
        if (out.isFile && out.length() > 0) return out
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var ok = false
        try {
            ex.setDataSource(video.absolutePath)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    idx = i
                    fmt = f
                    break
                }
            }
            if (idx < 0 || fmt == null) throw IllegalStateException("Videoda audio trek topilmadi")
            ex.selectTrack(idx)
            val mx = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            val track = mx.addTrack(fmt)
            mx.start()
            val maxSize = try { fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) } catch (_: Exception) { 0 }
            val buf = ByteBuffer.allocate(maxOf(maxSize, 512 * 1024))
            val info = MediaCodec.BufferInfo()
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, n, ex.sampleTime, flags)
                mx.writeSampleData(track, buf, info)
                ex.advance()
            }
            mx.stop()
            ok = true
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            ex.release()
            if (!ok) out.delete()
        }
        return out
    }

    fun shareIntent(context: Context, file: File, mime: String, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, OutputUtils.fileUri(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }
}
