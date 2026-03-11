package tech.shroyer.q25trackpadcustomizer

import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Status-bar notifications for root errors and optional current-mode status.
 */
object NotificationHelper {

    private const val ERROR_CHANNEL_ID = "q25_errors"
    private const val ERROR_CHANNEL_NAME = "Q25 errors"
    private const val ERROR_CHANNEL_DESC = "Error notifications for Q25 Trackpad Customizer"

    private const val STATUS_CHANNEL_ID = "q25_mode_status_v2"
    private const val STATUS_CHANNEL_NAME = "Q25 mode status"
    private const val STATUS_CHANNEL_DESC = "Current mode notification for Q25 Trackpad Customizer"

    private const val ROOT_ERROR_NOTIFICATION_ID = 1
    private const val MODE_STATUS_NOTIFICATION_ID = 2

    fun showRootError(context: Context, text: String) {
        if (AppState.rootErrorShown) return
        AppState.rootErrorShown = true

        val appContext = context.applicationContext
        createChannels(appContext)

        val notification = NotificationCompat.Builder(appContext, ERROR_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Q25 Trackpad Customizer: Root required")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .build()

        NotificationManagerCompat.from(appContext).notify(ROOT_ERROR_NOTIFICATION_ID, notification)
    }

    fun clearRootError(context: Context) {
        if (!AppState.rootErrorShown) return

        AppState.rootErrorShown = false
        val appContext = context.applicationContext
        NotificationManagerCompat.from(appContext).cancel(ROOT_ERROR_NOTIFICATION_ID)
    }

    fun updateModeStatus(context: Context, mode: Mode, appLabel: String? = null) {
        val appContext = context.applicationContext
        createChannels(appContext)

        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                appContext,
                0,
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
            )
        }

        val modeLabel = modeLabel(mode)

        val contentText = appLabel?.takeIf { it.isNotBlank() } ?: "Trackpad mode active"

        val builder = NotificationCompat.Builder(appContext, STATUS_CHANNEL_ID)
            .setSmallIcon(modeStatusIcon(mode))
            .setContentTitle(modeLabel)
            .setContentText("App: $contentText")
            .setSubText(modeCode(mode))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Mode: ${modeCode(mode)}\nApp: $contentText"
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(false)
        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        NotificationManagerCompat.from(appContext).notify(MODE_STATUS_NOTIFICATION_ID, builder.build())
    }

    fun clearModeStatus(context: Context) {
        val appContext = context.applicationContext
        NotificationManagerCompat.from(appContext).cancel(MODE_STATUS_NOTIFICATION_ID)
    }

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(ERROR_CHANNEL_ID) == null) {
                val errorChannel = NotificationChannel(
                    ERROR_CHANNEL_ID,
                    ERROR_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = ERROR_CHANNEL_DESC
                }
                nm.createNotificationChannel(errorChannel)
            }

            if (nm.getNotificationChannel(STATUS_CHANNEL_ID) == null) {
                val statusChannel = NotificationChannel(
                    STATUS_CHANNEL_ID,
                    STATUS_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = STATUS_CHANNEL_DESC
                    setShowBadge(false)
                }
                nm.createNotificationChannel(statusChannel)
            }
        }
    }

    private fun modeStatusIcon(mode: Mode): Int {
        return when (mode) {
            Mode.MOUSE -> R.drawable.ic_mode_mouse
            Mode.KEYBOARD -> R.drawable.ic_mode_keyboard
            Mode.SCROLL_WHEEL -> R.drawable.ic_mode_scroll_wheel
            Mode.SCROLL_MODE_2 -> R.drawable.ic_mode_scroll_touch
            Mode.FOLLOW_SYSTEM -> R.mipmap.ic_launcher
        }
    }

    private fun modeCode(mode: Mode): String {
        return when (mode) {
            Mode.MOUSE -> "MOUSE"
            Mode.KEYBOARD -> "KEYBOARD"
            Mode.SCROLL_WHEEL -> "SCROLL_WHEEL"
            Mode.SCROLL_MODE_2 -> "SCROLL_MODE_2"
            Mode.FOLLOW_SYSTEM -> "FOLLOW_SYSTEM"
        }
    }

    private fun modeLabel(mode: Mode): String {
        return when (mode) {
            Mode.MOUSE -> "Mouse mode"
            Mode.KEYBOARD -> "Keyboard mode"
            Mode.SCROLL_WHEEL -> "Scroll wheel mode"
            Mode.SCROLL_MODE_2 -> "Scroll mode 2"
            Mode.FOLLOW_SYSTEM -> "Follow system"
        }
    }

    private fun pendingIntentImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }
}
