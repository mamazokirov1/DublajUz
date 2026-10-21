package uz.dublaj.util

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Har bir ovoz klipini taxminan bir xil balandlikka keltiradi (RMS), kliplashni oldini oladi. */
object AudioLevel {
    private const val TARGET_RMS = 3600.0       // ≈ -19 dBFS
    private const val MAX_GAIN = 3.0
    private const val MIN_GAIN = 0.5

    fun level(pcm: ShortArray): ShortArray {
        if (pcm.isEmpty()) return pcm
        var sum = 0.0
        var peak = 0
        for (s in pcm) {
            val v = s.toInt()
            sum += v.toDouble() * v
            peak = max(peak, abs(v))
        }
        val rms = sqrt(sum / pcm.size)
        if (rms < 50.0 || peak == 0) return pcm
        var gain = (TARGET_RMS / rms).coerceIn(MIN_GAIN, MAX_GAIN)
        gain = min(gain, 31000.0 / peak)
        if (abs(gain - 1.0) < 0.03) return pcm
        return ShortArray(pcm.size) { i -> (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }
}
