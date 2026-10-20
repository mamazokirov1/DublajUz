package uz.dublaj.stt

import android.content.Context
import org.json.JSONObject
import timber.log.Timber
import uz.dublaj.data.TranscriptUnit
import java.io.File

/**
 * STT xatolarini tuzatish (Python stt_fixes.json porti).
 * Masalan: "атично" → "хаотично"
 */
object SttFixes {

    fun load(context: Context, externalFile: File? = null): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        try {
            // assets
            context.assets.open("stt_fixes.json").bufferedReader().use { reader ->
                parseInto(JSONObject(reader.readText()), result)
            }
        } catch (e: Exception) {
            Timber.w("assets/stt_fixes.json yo'q")
        }
        if (externalFile != null && externalFile.isFile) {
            try {
                parseInto(JSONObject(externalFile.readText()), result)
            } catch (e: Exception) {
                Timber.w(e, "stt_fixes.json o'qish xato")
            }
        }
        return result
    }

    private fun parseInto(obj: JSONObject, out: MutableMap<String, MutableMap<String, String>>) {
        obj.keys().forEach { lang ->
            if (lang.startsWith("_")) return@forEach
            val sub = obj.optJSONObject(lang) ?: return@forEach
            val map = out.getOrPut(lang) { mutableMapOf() }
            sub.keys().forEach { bad ->
                map[bad] = sub.getString(bad)
            }
        }
    }

    fun apply(units: List<TranscriptUnit>, fixes: Map<String, Map<String, String>>): List<TranscriptUnit> {
        if (fixes.isEmpty()) return units
        return units.map { u ->
            var text = u.text
            val langMap = fixes[u.language] ?: emptyMap()
            val all = fixes["*"] ?: emptyMap()
            for ((bad, good) in langMap + all) {
                if (bad.isNotEmpty() && text.contains(bad, ignoreCase = true)) {
                    text = text.replace(bad, good, ignoreCase = true)
                }
            }
            if (text != u.text) u.copy(text = text) else u
        }
    }
}
