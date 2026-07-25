package ai.sotto.assistant

import ai.sotto.assistant.core.SLog
import ai.sotto.assistant.di.AppContainer
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class SottoApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()
        // Design Doc 1 § Runtime Flow: "The app loads the precomputed attendee database
        // into memory" before face detection begins.
        container.preload()
        SLog.i(TAG, "Sotto ${BuildConfig.VERSION_NAME} started")
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_SESSION,
            getString(R.string.notification_channel_session),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_session_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onTerminate() {
        container.release()
        super.onTerminate()
    }

    companion object {
        private const val TAG = "App"
        const val CHANNEL_SESSION = "sotto_session"

        fun from(context: Context): AppContainer =
            (context.applicationContext as SottoApplication).container
    }
}
