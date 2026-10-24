package uz.dublaj.pipeline

import uz.dublaj.data.TranscriptUnit
import uz.dublaj.util.WavFile
import uz.dublaj.util.WavWriter
import java.io.File

/**
 * Videodagi nutqdan ovoz klonlash uchun namuna (≈10–12 soniya) ajratadi:
 * eng uzun segmentlar olinib, qisqa pauza bilan ulanadi. Bir nechta spiker bo'lsa,
 * aralash namuna chiqishi mumkin — bunday holda "Namuna fayl" rejimidan foydalaning.
 */
object ReferenceVoice {
    fun extract(wav: File, units: List<TranscriptUnit>, out: File, targetSec: Double = 12.0): Boolean {
        out.delete()
        if (units.isEmpty() || !wav.isFile) return false
        WavFile(wav).use { w ->
            val sr = w.sampleRate
            val total = w.numSamples
            val picked = ArrayList<Pair<Long, Int>>()
            var sec = 0.0
            for (u in units.sortedByDescending { it.end - it.start }) {
                val len = minOf(u.end - u.start, 8.0)
                if (len < 1.5) continue
                if (sec + len > targetSec + 2.0) continue
                val s = (u.start * sr).toLong().coerceIn(0L, total)
                val n = minOf((len * sr).toLong(), total - s).toInt()
                if (n <= 0) continue
                picked.add(Pair(s, n))
                sec += len
                if (sec >= targetSec) break
            }
            if (sec < 3.0) return false
            picked.sortBy { it.first }
            val gap = ShortArray((0.25 * sr).toInt())
            WavWriter(out, sr).use { ww ->
                for ((i, p) in picked.withIndex()) {
                    if (i > 0) ww.write(gap)
                    ww.write(w.read(p.first, p.second))
                }
            }
            return true
        }
    }
}
