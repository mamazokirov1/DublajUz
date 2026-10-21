package uz.dublaj.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import uz.dublaj.data.AppConfig
import uz.dublaj.data.ModelsInfo
import java.io.File

/** Muammo/taklif hisoboti: qurilma ma'lumoti + sozlamalar + oxirgi loglar. Video va matn kirmaydi. */
object BugReport {
    const val ADMIN_EMAIL = "shoirjon0123@gmail.com"

    fun hasCrash(context: Context) = CrashLogger.crashFile(context).isFile

    fun build(context: Context, config: AppConfig, models: ModelsInfo, note: String): File {
        val pm = context.packageManager
        val version = try { pm.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { "?" }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val sb = StringBuilder()
        sb.appendLine("DublajUz hisoboti")
        sb.appendLine("Versiya: $version")
        sb.appendLine("Qurilma: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("RAM: ${mi.totalMem / 1024 / 1024} MB jami, ${mi.availMem / 1024 / 1024} MB bo'sh")
        sb.appendLine("Modellar: whisper=${models.whisper} nllb=${models.nllb} vits=${models.vits}")
        sb.appendLine("Sozlamalar: $config")
        if (note.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("--- Foydalanuvchi izohi ---")
            sb.appendLine(note.trim())
        }
        val crash = CrashLogger.crashFile(context)
        if (crash.isFile) {
            sb.appendLine()
            sb.appendLine("--- Oxirgi to'xtash (crash) ---")
            sb.appendLine(crash.readText().take(6000))
        }
        val log = CrashLogger.logFile(context)
        if (log.isFile) {
            sb.appendLine()
            sb.appendLine("--- Oxirgi loglar ---")
            sb.appendLine(log.readLines().takeLast(200).joinToString("\n"))
        }

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val out = File(dir, "dublaj_hisobot.txt")
        out.writeText(sb.toString())
        return out
    }

    /** Hisobotni faqat ilova adminiga (Gmail) yuborish oynasini ochadi. */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        fun mail(pkg: String?) = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(ADMIN_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "DublajUz: muammo / taklif")
            putExtra(Intent.EXTRA_TEXT, "Salom! Hisobot faylda ilova qilingan.\n\n")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) setPackage(pkg)
        }
        try {
            context.startActivity(mail("com.google.android.gm").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent.createChooser(mail(null), "Email ilovasini tanlang").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                Toast.makeText(context, "Email ilovasi topilmadi. Hisobotni $ADMIN_EMAIL manziliga yuboring.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
