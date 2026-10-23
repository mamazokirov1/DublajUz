package uz.dublaj.pipeline

import timber.log.Timber
import uz.dublaj.data.TranscriptUnit
import uz.dublaj.util.WavFile
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Whisper segment vaqtlari ±0.3–0.5 s noaniq bo'lishi mumkin. Bu yerda asl audioning energiyasi
 * bo'yicha nutq boshlanishi/tugashi topilib, segment chegaralari eng yaqin haqiqiy nuqtaga suriladi.
 * Fon musiqasi/shovqin kuchli bo'lib, nutq ajralmasa — hech narsa o'zgartirilmaydi.
 */
object SpeechSnap {
    private const val FRAME_MS = 20
    private const val ONSET_FRAMES = 5      // 100 ms barqaror nutq
    private const val OFFSET_FRAMES = 8     // 160 ms jimlik

    private fun speechMask(wav: File): BooleanArray? {
        WavFile(wav).use { w ->
            val frame = w.sampleRate * FRAME_MS / 1000
            if (frame <= 0) return null
            val nFrames = (w.numSamples / frame).toInt()
            if (nFrames < 50) return null
            val db = FloatArray(nFrames)
            var f = 0
            while (f < nFrames) {
                val cnt = min(500, nFrames - f)
                val buf = w.read(f.toLong() * frame, cnt * frame)
                for (k in 0 until cnt) {
                    var s = 0.0
                    val o = k * frame
                    for (j in 0 until frame) {
                        val v = buf[o + j] / 32768.0
                        s += v * v
                    }
                    db[f + k] = (20.0 * log10(sqrt(s / frame) + 1e-6)).toFloat()
                }
                f += cnt
            }
            val sorted = db.copyOf()
            sorted.sort()
            val floor = sorted[(nFrames * 0.10).toInt()]
            val ceil = sorted[min(nFrames - 1, (nFrames * 0.95).toInt())]
            if (ceil - floor < 12f) return null   // dinamika yo'q — ishonib bo'lmaydi
            val thr = floor + (ceil - floor) * 0.25f
            return BooleanArray(nFrames) { db[it] > thr }
        }
    }

    fun refine(wav: File, units: List<TranscriptUnit>, maxShiftSec: Double = 0.4): List<TranscriptUnit> {
        if (units.isEmpty()) return units
        val sp = speechMask(wav) ?: return units
        val n = sp.size
        val shift = (maxShiftSec * 1000 / FRAME_MS).toInt()
        fun frameOf(t: Double) = (t * 1000.0 / FRAME_MS).toInt()
        fun secOf(f: Int) = f * FRAME_MS / 1000.0

        val out = ArrayList<TranscriptUnit>(units.size)
        var prevEnd = 0.0
        var moved = 0
        for (u in units.sortedBy { it.start }) {
            // --- boshlanish: eng yaqin nutq boshlanishi
            val s0 = frameOf(u.start)
            var bestS = -1
            var bestD = Int.MAX_VALUE
            for (f in max(1, s0 - shift)..min(n - ONSET_FRAMES - 1, s0 + shift)) {
                if (sp[f] && !sp[f - 1]) {
                    var ok = true
                    for (k in 0 until ONSET_FRAMES) if (!sp[f + k]) { ok = false; break }
                    if (ok && abs(f - s0) < bestD) { bestD = abs(f - s0); bestS = f }
                }
            }
            var newStart = if (bestS >= 0) secOf(bestS) else u.start

            // --- tugash: eng yaqin nutq tugashi
            val e0 = frameOf(u.end)
            var bestE = -1
            bestD = Int.MAX_VALUE
            for (f in max(1, e0 - shift)..min(n - OFFSET_FRAMES - 1, e0 + shift)) {
                if (sp[f - 1] && !sp[f]) {
                    var ok = true
                    for (k in 0 until OFFSET_FRAMES) if (sp[f + k]) { ok = false; break }
                    if (ok && abs(f - e0) < bestD) { bestD = abs(f - e0); bestE = f }
                }
            }
            var newEnd = if (bestE >= 0) secOf(bestE) else u.end

            if (newStart < prevEnd) newStart = min(max(prevEnd, u.start), u.end - 0.3)
            if (newEnd < newStart + 0.3) newEnd = u.end
            if (newEnd < newStart + 0.3) newStart = u.start
            if (abs(newStart - u.start) > 0.01 || abs(newEnd - u.end) > 0.01) moved++
            prevEnd = newEnd
            out.add(u.copy(start = newStart, end = newEnd))
        }
        Timber.i("SpeechSnap: ${out.size} segmentdan $moved tasi surildi")
        return out
    }
}
