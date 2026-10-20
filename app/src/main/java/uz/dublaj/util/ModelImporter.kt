package uz.dublaj.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import timber.log.Timber
import uz.dublaj.translate.NllbTranslator
import java.io.File

/** Telefondagi fayllardan (SAF orqali tanlangan) modellarni ilova papkasiga nusxalaydi. */
object ModelImporter {

    fun importFile(context: Context, uri: Uri, dest: File, minBytes: Long = 1000): File {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            part.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
        } ?: throw IllegalStateException("Fayl ochilmadi")
        if (part.length() < minBytes) {
            part.delete()
            throw IllegalStateException("Fayl bo'sh yoki juda kichik")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        return dest
    }

    /**
     * NLLB papkasini (ichida onnx/ va tokenizer fayllari) tanlangan daraxtdan topib nusxalaydi.
     * @return nusxalangan fayllar soni
     */
    fun importNllbTree(
        context: Context,
        treeUri: Uri,
        work: WorkDir,
        onProgress: (String) -> Unit
    ): Int {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Papka ochilmadi")
        val index = HashMap<String, DocumentFile>()   // fayl nomi -> hujjat (birinchisi)
        collect(root, index, depth = 0)

        val destRoot = NllbTranslator.modelDir(work)
        var copied = 0
        for (rel in NllbTranslator.REQUIRED_FILES + listOf("generation_config.json", "special_tokens_map.json")) {
            val name = rel.substringAfterLast('/')
            val doc = index[name]
            if (doc == null) {
                if (rel in NllbTranslator.REQUIRED_FILES) Timber.w("Papkada topilmadi: $name")
                continue
            }
            onProgress("Nusxalanmoqda: $name")
            importFile(context, doc.uri, File(destRoot, rel))
            copied++
        }
        return copied
    }

    private fun collect(dir: DocumentFile, out: MutableMap<String, DocumentFile>, depth: Int) {
        if (depth > 4) return
        for (f in dir.listFiles()) {
            if (f.isDirectory) collect(f, out, depth + 1)
            else f.name?.let { out.putIfAbsent(it, f) }
        }
    }
}
