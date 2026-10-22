package uz.dublaj.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import timber.log.Timber
import uz.dublaj.stt.WhisperRecognizer
import uz.dublaj.translate.NllbTranslator
import java.io.File

/**
 * Telefon xotirasidan (Download, nllb, Documents va h.k.) model fayllarini avtomatik topadi
 * va ilova papkasiga nusxalaydi. Ilova o'chirilib qayta o'rnatilganda modellarni
 * qayta yuklab olish o'rniga shu yo'l bilan tiklash mumkin.
 *
 * Android 11+ da "Barcha fayllarga kirish" (MANAGE_EXTERNAL_STORAGE) ruxsati kerak.
 */
object StorageScanner {

    private const val MB = 1024L * 1024L
    private val SKIP_TOP = setOf(
        "Android", "DCIM", "Pictures", "Movies", "Music", "Alarms",
        "Notifications", "Ringtones", "Podcasts", "Recordings"
    )
    private val WHISPER_BY_FILE: Map<String, WhisperRecognizer.Companion.ModelInfo> =
        WhisperRecognizer.MODEL_OPTIONS.associateBy { it.fileName }

    class ScanResult(
        val whisper: Map<String, File>,          // fayl nomi -> manba
        val vitsModel: File?,
        val vitsConfig: File?,
        val nllb: Map<String, File>?             // nisbiy yo'l (onnx/...) -> manba; to'liq bo'lsagina
    ) {
        val foundAny: Boolean
            get() = whisper.isNotEmpty() || vitsModel != null || vitsConfig != null || nllb != null
    }

    class ImportReport(val copied: Int, val found: Int, val notFound: List<String>)

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun scan(): ScanResult {
        val root = Environment.getExternalStorageDirectory()
        val whisper = LinkedHashMap<String, File>()
        var vitsModel: File? = null
        var vitsConfig: File? = null
        val decoders = ArrayList<File>()
        var visited = 0

        fun walk(dir: File, depth: Int) {
            if (depth > 4 || visited > 60_000) return
            val list = dir.listFiles() ?: return
            for (f in list) {
                visited++
                if (f.isDirectory) {
                    if (f.name.startsWith(".")) continue
                    if (depth == 0 && f.name in SKIP_TOP) continue
                    walk(f, depth + 1)
                } else {
                    val n = f.name
                    when {
                        n in WHISPER_BY_FILE -> whisper.putIfAbsent(n, f)
                        n == "O'zbekcha ovoz fayli (vits_uzbek.onnx)" -> if (vitsModel == null) vitsModel = f
                        n == "Ovoz sozlama fayli (uz_tts_base.json)" -> if (vitsConfig == null) vitsConfig = f
                        n == "decoder_model_merged_quantized.onnx" -> decoders.add(f)
                    }
                }
            }
        }
        walk(root, 0)

        var nllb: Map<String, File>? = null
        for (dec in decoders) {
            val dir = dec.parentFile ?: continue
            val encoder = File(dir, "encoder_model_quantized.onnx")
            if (!encoder.isFile) continue
            val base = if (dir.name == "onnx") (dir.parentFile ?: dir) else dir
            val map = LinkedHashMap<String, File>()
            map["onnx/encoder_model_quantized.onnx"] = encoder
            map["onnx/decoder_model_merged_quantized.onnx"] = dec
            var ok = true
            for (name in listOf("config.json", "tokenizer.json", "tokenizer_config.json")) {
                val f = listOf(File(base, name), File(dir, name)).firstOrNull { it.isFile }
                if (f == null) { ok = false; break }
                map[name] = f
            }
            if (!ok) continue
            for (name in listOf("generation_config.json", "special_tokens_map.json")) {
                listOf(File(base, name), File(dir, name)).firstOrNull { it.isFile }?.let { map[name] = it }
            }
            nllb = map
            break
        }

        Timber.i(
            "Scan: whisper=${whisper.keys} vits=${vitsModel?.path} cfg=${vitsConfig?.path} " +
                "nllb=${nllb?.get("tokenizer.json")?.parent} (visited=$visited)"
        )
        return ScanResult(whisper, vitsModel, vitsConfig, nllb)
    }

    /** Topilgan modellarni ilova papkasiga nusxalaydi (allaqachon bor va to'liq bo'lsa o'tkazib yuboradi). */
    fun importAll(context: Context, work: WorkDir, onProgress: (String) -> Unit): ImportReport {
        onProgress("Download va boshqa papkalar qidirilmoqda…")
        val r = scan()
        var copied = 0
        var found = 0
        val notFound = ArrayList<String>()

        // Whisper
        if (r.whisper.isEmpty()) notFound.add("Matn aniqlash fayli (ggml-base.bin, ggml-small.bin yoki ggml-tiny.bin)")
        for ((name, src) in r.whisper) {
            found++
            val info = WHISPER_BY_FILE.getValue(name)
            if (src.length() < info.minBytes) {
                Timber.w("Whisper $name to'liq emas (${src.length()} bayt), o'tkazildi")
                continue
            }
            val dest = File(work.models, name)
            if (isSame(src, dest)) continue
            copy(src, dest, "Matn aniqlash (${info.id})", onProgress); copied++
        }

        // VITS
        if (r.vitsModel == null) notFound.add("O'zbekcha ovoz fayli (vits_uzbek.onnx)")
        r.vitsModel?.let {
            found++
            val dest = File(work.models, "O'zbekcha ovoz fayli (vits_uzbek.onnx)")
            if (!isSame(it, dest)) { copy(it, dest, "O'zbekcha ovoz", onProgress); copied++ }
        }
        if (r.vitsConfig == null) notFound.add("Ovoz sozlama fayli (uz_tts_base.json)")
        r.vitsConfig?.let {
            found++
            val dest = File(work.models, "Ovoz sozlama fayli (uz_tts_base.json)")
            if (!isSame(it, dest)) { copy(it, dest, "Ovoz sozlamasi", onProgress); copied++ }
        }

        // NLLB
        if (r.nllb == null) {
            notFound.add("Tarjima papkasi (config.json, tokenizer.json, tokenizer_config.json, onnx/…quantized.onnx)")
        } else {
            found++
            val destRoot = NllbTranslator.modelDir(work)
            for ((rel, src) in r.nllb) {
                val dest = File(destRoot, rel)
                if (isSame(src, dest)) continue
                copy(src, dest, "Tarjima ${rel.substringAfterLast('/')}", onProgress); copied++
            }
        }
        return ImportReport(copied, found, notFound)
    }

    private fun isSame(src: File, dest: File) = dest.isFile && dest.length() == src.length()

    private fun copy(src: File, dest: File, label: String, onProgress: (String) -> Unit) {
        dest.parentFile?.mkdirs()
        FileUtils.ensureSpace(dest.parentFile!!, src.length() + 50L * MB)
        val part = File(dest.parentFile, dest.name + ".part")
        val total = src.length()
        src.inputStream().use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport >= 4 * MB) {
                        lastReport = done
                        onProgress("$label: ${done / MB} / ${total / MB} MB")
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        Timber.i("Auto-import: ${src.path} → ${dest.path}")
    }
}
