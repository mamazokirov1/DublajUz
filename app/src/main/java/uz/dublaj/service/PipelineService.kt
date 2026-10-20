package uz.dublaj.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import timber.log.Timber
import uz.dublaj.MainActivity

/**
 * Uzoq jarayon (STT/tarjima/TTS/mux) paytida ilovani fonda tirik tutadi.
 * Ishning o'zi ViewModel'da bajariladi; bu servis faqat jarayonni himoya qiladi.
 */
class PipelineService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Dublyaj ishlamoqda…"
        val notification = buildNotification(text)
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        } catch (e: Exception) {
            Timber.w(e, "startForeground")
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DublajUz:pipeline").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Dublyaj jarayoni", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DublajUz")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "dublaj_pipeline"
        private const val NOTIF_ID = 1001
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PipelineService::class.java).putExtra(EXTRA_TEXT, text)
                )
            } catch (e: Exception) {
                Timber.w(e, "PipelineService start")   // servis bo'lmasa ham ish davom etadi
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, PipelineService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
