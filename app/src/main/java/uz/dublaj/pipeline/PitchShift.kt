package uz.dublaj.pipeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Ovoz tembrini (pitch) davomiylikni saqlagan holda o'zgartiradi:
 * avval qayta tanlab (resample) balandlik o'zgaradi, keyin WSOLA bilan uzunlik asl holiga qaytariladi.
 * semitones > 0 → ingichkaroq, < 0 → chuqurroq.
 */
object PitchShift {
    fun shift(pcm: ShortArray, sr: Int, semitones: Float): ShortArray {
        if (pcm.isEmpty() || abs(semitones) < 0.05f) return pcm
        val f = 2.0.pow(semitones / 12.0)
        val newLen = max(1, (pcm.size / f).roundToInt())
        val res = ShortArray(newLen) { i ->
            val p = i * f
            val idx = p.toInt().coerceIn(0, pcm.lastIndex)
            val fr = p - idx
            val s0 = pcm[idx].toInt()
            val s1 = pcm[minOf(idx + 1, pcm.lastIndex)].toInt()
            (s0 + (s1 - s0) * fr).toInt().toShort()
        }
        return TimeStretch.stretch(res, sr, (1.0 / f).toFloat())
    }
}
