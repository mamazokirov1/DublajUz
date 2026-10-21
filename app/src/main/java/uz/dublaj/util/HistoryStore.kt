package uz.dublaj.util

import android.content.Context
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val video: File,
    val title: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val hasUzSrt: Boolean,
    val hasM4a: Boolean
)

/**
 * Dublyaj tarixi. Alohida baza yo'q: tayyor videolar ilova xotirasidagi output papkasida turadi,
 * yonida metama'lumot (.json), subtitr (.srt) va audio (.m4a). Ilova yopilsa ham saqlanadi.
 */
object HistoryStore {
    private val EXTRAS = listOf("mp4", "json", "uz.srt", "orig.srt", "m4a")

    fun list(context: Context): List<HistoryEntry> {
        val dir = WorkDir(context.applicationContext).output
        val files = dir.listFiles { f -> f.isFile && f.extension == "mp4" } ?: return emptyList()
        return files.map { f ->
            val base = f.nameWithoutExtension
            var title = ""
            var created = 0L
            val meta = File(dir, "$base.json")
            if (meta.isFile) {
                try {
                    val j = JSONObject(meta.readText())
                    title = j.optString("title")
                    created = j.optLong("created")
                } catch (_: Exception) {
                }
            }
            if (created == 0L) {
                created = Regex("dublaj_(\\d+)").find(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: f.lastModified()
            }
            HistoryEntry(
                video = f, title = title, createdAt = created, sizeBytes = f.length(),
                hasUzSrt = File(dir, "$base.uz.srt").isFile,
                hasM4a = File(dir, "$base.m4a").isFile
            )
        }.sortedByDescending { it.createdAt }
    }

    fun writeMeta(video: File, title: String, durationSec: Double, segments: Int) {
        val j = JSONObject()
            .put("title", title)
            .put("created", System.currentTimeMillis())
            .put("durationSec", durationSec)
            .put("segments", segments)
        File(video.parentFile, video.nameWithoutExtension + ".json").writeText(j.toString())
    }

    /** Videoni va unga tegishli hamma fayllarni (meta, srt, m4a) o'chiradi. */
    fun delete(video: File) {
        val base = video.nameWithoutExtension
        EXTRAS.forEach { ext -> File(video.parentFile, "$base.$ext").delete() }
    }

    fun deleteAll(context: Context) {
        list(context).forEach { delete(it.video) }
    }

    fun totalBytes(entries: List<HistoryEntry>): Long = entries.sumOf { it.sizeBytes }
}
