package uz.dublaj.pipeline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * WSOLA vaqt-cho'zish: nutq balandligini (pitch) o'zgartirmasdan tezlashtiradi.
 * speed > 1 → qisqaroq (tezroq).
 */
object TimeStretch {

    fun stretch(input: ShortArray, sr: Int, speed: Float): ShortArray {
        if (abs(speed - 1f) < 0.01f) return input
        val n = (sr * 0.030).toInt().coerceAtLeast(256)          // oyna ~30 ms
        if (input.size < n * 3) return fallback(input, speed)

        val hs = n / 2                                           // sintez qadami
        val ha = max(1, (hs * speed).roundToInt())              // tahlil qadami
        val tol = (sr * 0.010).toInt()                           // qidiruv ±10 ms

        val x = FloatArray(input.size) { input[it] / 32768f }
        val outCap = (input.size / speed).toInt() + 3 * n
        val y = FloatArray(outCap)
        val norm = FloatArray(outCap)
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * (it + 0.5) / n)).toFloat() }

        var outPos = 0
        var nominal = 0
        var prevStart = 0
        var first = true

        while (nominal <= x.size - n && outPos + n <= outCap) {
            val start: Int
            if (first) {
                start = 0
                first = false
            } else {
                val target = prevStart + hs
                val lo = max(0, nominal - tol)
                val hi = min(x.size - n, nominal + tol)
                if (target + hs > x.size) break
                var best = min(max(nominal, lo), hi)
                var bestC = -Float.MAX_VALUE
                var d = lo
                while (d <= hi) {
                    var c = 0f
                    var k = 0
                    while (k < hs) {
                        c += x[target + k] * x[d + k]
                        k += 2
                    }
                    if (c > bestC) {
                        bestC = c
                        best = d
                    }
                    d++
                }
                start = best
            }
            for (k in 0 until n) {
                y[outPos + k] += x[start + k] * win[k]
                norm[outPos + k] += win[k]
            }
            outPos += hs
            prevStart = start
            nominal += ha
        }

        val len = min(outPos + hs, outCap)
        return ShortArray(len) { i ->
            val v = y[i] / max(norm[i], 1e-3f)
            (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }

    /** Juda qisqa klip uchun oddiy chiziqli qisqartirish. */
    private fun fallback(input: ShortArray, speed: Float): ShortArray {
        val newLen = max(1, (input.size / speed).roundToInt())
        val ratio = input.size.toDouble() / newLen
        return ShortArray(newLen) { i ->
            val p = i * ratio
            val idx = p.toInt().coerceIn(0, input.lastIndex)
            val f = p - idx
            val s0 = input[idx].toInt()
            val s1 = input[min(idx + 1, input.lastIndex)].toInt()
            (s0 + (s1 - s0) * f).toInt().toShort()
        }
    }
}
