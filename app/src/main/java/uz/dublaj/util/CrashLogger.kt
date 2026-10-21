package uz.dublaj.util

import android.app.Application
import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Texnik loglar (faylga) va kutilmagan to'xtash (crash) hisobotini saqlaydi. Foydalanuvchi matni yozilmaydi. */
object CrashLogger {
    fun logFile(context: Context) = File(context.filesDir, "logs/dublaj.log")
    fun crashFile(context: Context) = File(context.filesDir, "crash.txt")

    fun install(app: Application) {
        Timber.plant(FileTree(logFile(app)))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                crashFile(app).writeText(
                    "${stamp()}\nThread: ${t.name}\n${Log.getStackTraceString(e)}"
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private class FileTree(private val file: File) : Timber.Tree() {
        private val maxBytes = 300_000L
        override fun isLoggable(tag: String?, priority: Int) = priority >= Log.INFO

        @Synchronized
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            try {
                file.parentFile?.mkdirs()
                if (file.length() > maxBytes) {
                    val old = File(file.parentFile, file.name + ".1")
                    old.delete()
                    file.renameTo(old)
                }
                val lvl = when (priority) { Log.ERROR -> "E"; Log.WARN -> "W"; else -> "I" }
                file.appendText("${stamp()} $lvl $message\n")
                if (t != null) file.appendText(Log.getStackTraceString(t) + "\n")
            } catch (_: Throwable) {
            }
        }
    }
}
