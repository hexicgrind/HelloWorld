package ai.sotto.assistant.service

import ai.sotto.assistant.R
import ai.sotto.assistant.SottoApplication
import ai.sotto.assistant.core.SLog
import ai.sotto.assistant.ui.MainActivity
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService

/**
 * Keeps the microphone alive and, more importantly, keeps a persistent notification on
 * screen while Sotto is listening.
 *
 * The notification is not a technicality. An app that quietly records the people around
 * you must be visibly, unmistakably on — this is the honest signal that it is.
 */
class SessionService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                SottoApplication.from(this).sessionOrchestrator?.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
        } catch (t: Throwable) {
            // On API 34+ this throws if the microphone permission was revoked while we
            // were backgrounded. Stopping is the correct response.
            SLog.e(TAG, "Could not enter the foreground; stopping", t)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, SottoApplication.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.notification_session_title))
            .setContentText(getString(R.string.notification_session_text))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_session_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    companion object {
        private const val TAG = "SessionService"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "ai.sotto.assistant.STOP_SESSION"

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, SessionService::class.java))
            }.onFailure { SLog.w(TAG, "Could not start the session service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SessionService::class.java)) }
        }
    }
}
