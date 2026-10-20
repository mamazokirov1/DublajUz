package uz.dublaj.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * Loyiha ish papkalari: externalFilesDir/dublaj/{input,temp,output,models}
 */
class WorkDir(context: Context) {
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "dublaj").also { it.mkdirs() }
    val input: File = File(root, "input").also { it.mkdirs() }
    val temp: File = File(root, "temp").also { it.mkdirs() }
    val output: File = File(root, "output").also { it.mkdirs() }
    val models: File = File(root, "models").also { it.mkdirs() }

    val transcriptJson get() = File(temp, "transcript.json")
    val translationJson get() = File(temp, "translation.json")
    val approvedFlag get() = File(temp, "approved.flag")
    val extractedWav get() = File(temp, "extracted.wav")      // 16 kHz — Whisper uchun
    val mixWav get() = File(temp, "original_mix.wav")         // 22.05 kHz — aralashtirish uchun

    /** Vaqtinchalik fayllarni (klip, wav) o'chiradi; transkript/tarjima/tasdiq qoladi. */
    fun clearHeavyTemp() {
        extractedWav.delete()
        mixWav.delete()
        temp.listFiles()?.forEach { f ->
            if (f.name.startsWith("clip_") || f.name.endsWith(".part")) f.delete()
        }
    }

    /** Kiruvchi video nusxalari va vaqtinchalik fayllarni tozalaydi (modellar va tayyor videolar qoladi). */
    fun clearWork() {
        input.listFiles()?.forEach { it.deleteRecursively() }
        temp.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun usedBytes(): Long = listOf(input, temp).sumOf { dirSize(it) }

    private fun dirSize(f: File): Long =
        if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0L)
}

object FileUtils {
    /** content:// URI ni lokal faylga nusxalaydi (joy yetarliligini tekshiradi). */
    fun copyUriToFile(context: Context, uri: Uri, dest: File): File {
        dest.parentFile?.mkdirs()
        val size = querySize(context, uri)
        if (size > 0) ensureSpace(dest.parentFile!!, size + 50L * 1024 * 1024)
        val part = File(dest.parentFile, dest.name + ".part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(part).use { output -> input.copyTo(output, 256 * 1024) }
        } ?: throw IllegalStateException("URI ochilmadi: $uri")
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        Timber.d("Copied $uri → ${dest.absolutePath} (${dest.length()} bytes)")
        return dest
    }

    fun displayName(context: Context, uri: Uri): String {
        var name = "video.mp4"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
        } catch (_: Exception) {
        }
        return name
    }

    private fun querySize(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
            }
        } catch (_: Exception) {
        }
        return -1L
    }

    /**
     * Lokal fayl nomi: kirill/maxsus harflar "____" ga aylanib to'qnashmasligi uchun
     * URI hash'iga asoslangan, kengaytma saqlanadi.
     */
    fun localVideoName(context: Context, uri: Uri): String {
        val display = displayName(context, uri)
        val ext = display.substringAfterLast('.', "mp4")
            .lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "mp4" }
        return "video_${(uri.toString().hashCode().toLong() and 0xffffffffL).toString(16)}.$ext"
    }

    fun ensureSpace(dir: File, needBytes: Long) {
        val free = dir.usableSpace
        if (free < needBytes) {
            throw IllegalStateException(
                "Joy yetarli emas: ~${needBytes / (1024 * 1024)} MB kerak, bo'sh ${free / (1024 * 1024)} MB"
            )
        }
    }
}
