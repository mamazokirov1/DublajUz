package uz.dublaj.util

import android.content.Context
import uz.dublaj.data.TranslationUnit
import java.io.File

/** Subtitr (.srt) yaratish. */
object SrtExporter {

    private fun ts(sec: Double): String {
        val ms = (sec.coerceAtLeast(0.0) * 1000).toLong()
        return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }

    private fun wrap(text: String, width: Int = 42): String {
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (w in text.trim().split(Regex("\\s+"))) {
            if (cur.isNotEmpty() && cur.length + 1 + w.length > width) {
                lines.add(cur.toString())
                cur = StringBuilder()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines.joinToString("\n")
    }

    /** uzbek = true: tarjima matni, false: asl til matni. */
    fun build(units: List<TranslationUnit>, uzbek: Boolean): String {
        val items = units
            .map { it to (if (uzbek) it.uzText else it.text) }
            .filter { it.second.isNotBlank() }
            .sortedBy { it.first.start }
        val sb = StringBuilder()
        items.forEachIndexed { i, (u, t) ->
            val nextStart = items.getOrNull(i + 1)?.first?.start
            var end = u.end
            if (nextStart != null && end > nextStart) end = nextStart
            if (end <= u.start) end = u.start + 1.0
            sb.append(i + 1).append('\n')
                .append(ts(u.start)).append(" --> ").append(ts(end)).append('\n')
                .append(wrap(t)).append("\n\n")
        }
        return sb.toString()
    }

    fun write(file: File, units: List<TranslationUnit>, uzbek: Boolean) {
        file.parentFile?.mkdirs()
        file.writeText(build(units, uzbek), Charsets.UTF_8)
    }

    /** Ulashish uchun vaqtinchalik fayl (cache/reports). */
    fun tempFile(context: Context, units: List<TranslationUnit>, uzbek: Boolean): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val f = File(dir, if (uzbek) "subtitr_uz.srt" else "subtitr_asl.srt")
        write(f, units, uzbek)
        return f
    }
}
