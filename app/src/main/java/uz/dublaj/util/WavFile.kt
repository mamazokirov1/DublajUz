package uz.dublaj.util

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Mono/stereo 16-bit PCM WAV yozuvchi (oqim bilan, xotirani to'ldirmaydi). */
class WavWriter(private val file: File, val sampleRate: Int, private val channels: Int = 1) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private val buf = ByteArray(64 * 1024)

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    fun write(pcm: ShortArray, off: Int = 0, len: Int = pcm.size - off) {
        var done = 0
        while (done < len) {
            val n = min(len - done, buf.size / 2)
            for (k in 0 until n) {
                val s = pcm[off + done + k].toInt()
                buf[2 * k] = (s and 0xff).toByte()
                buf[2 * k + 1] = ((s shr 8) and 0xff).toByte()
            }
            raf.write(buf, 0, n * 2)
            done += n
        }
        dataBytes += len * 2L
    }

    override fun close() {
        try {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray())
            h.putInt((36 + dataBytes).toInt())
            h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray())
            h.putInt(16)
            h.putShort(1)
            h.putShort(channels.toShort())
            h.putInt(sampleRate)
            h.putInt(sampleRate * channels * 2)
            h.putShort((channels * 2).toShort())
            h.putShort(16)
            h.put("data".toByteArray())
            h.putInt(dataBytes.toInt())
            raf.seek(0)
            raf.write(h.array())
        } finally {
            raf.close()
        }
    }
}

/** O'zimiz yozgan 44-baytli sarlavhali mono WAV ni tasodifiy o'qish. */
class WavFile(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val sampleRate: Int
    val numSamples: Long

    init {
        val h = ByteArray(44)
        raf.readFully(h)
        sampleRate = ByteBuffer.wrap(h, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        numSamples = (raf.length() - 44) / 2
    }

    /** [start, start+count) oralig'ini o'qiydi; tashqarisi 0 bilan to'ldiriladi. */
    fun read(start: Long, count: Int): ShortArray {
        val out = ShortArray(count)
        if (count <= 0 || start >= numSamples) return out
        val from = start.coerceAtLeast(0)
        val skip = (from - start).toInt()
        val n = min((count - skip).toLong(), numSamples - from).toInt()
        if (n <= 0) return out
        val bytes = ByteArray(n * 2)
        raf.seek(44 + from * 2)
        raf.readFully(bytes)
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        sb.get(out, skip, n)
        return out
    }

    override fun close() = raf.close()
}

/** Sarlavhasiz s16le fayllar (vaqtinchalik klip). */
object RawPcm {
    fun write(file: File, pcm: ShortArray) {
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(pcm)
        file.outputStream().use { it.write(bb.array()) }
    }

    fun read(file: File): ShortArray {
        val bytes = file.readBytes()
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }
}

/** Oqimli chiziqli resampler (bloklar orasida holatni saqlaydi). */
class StreamResampler(private val srcRate: Int, private val dstRate: Int) {
    private val step = srcRate.toDouble() / dstRate
    private var pos = 0.0
    private var carry = ShortArray(0)

    fun process(input: ShortArray): ShortArray {
        if (srcRate == dstRate) return input
        if (input.isEmpty()) return input
        val data = if (carry.isEmpty()) input else carry + input
        val maxOut = (((data.size - pos) / step).toInt() + 2).coerceAtLeast(0)
        val out = ShortArray(maxOut)
        var n = 0
        while (pos < data.size - 1 && n < out.size) {
            val i = pos.toInt()
            val f = pos - i
            val s0 = data[i].toInt()
            val s1 = data[i + 1].toInt()
            out[n++] = (s0 + (s1 - s0) * f).toInt().toShort()
            pos += step
        }
        val keepFrom = pos.toInt().coerceAtMost(data.size)
        carry = data.copyOfRange(keepFrom, data.size)
        pos -= keepFrom
        return if (n == out.size) out else out.copyOf(n)
    }

    companion object {
        /** Butun massivni bir martada resample qiladi. */
        fun resample(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray =
            StreamResampler(srcRate, dstRate).process(input)
    }
}
