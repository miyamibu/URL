package jp.mimac.urlsaver.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import jp.mimac.urlsaver.MainActivity
import jp.mimac.urlsaver.R

data class SharedTagUpdateNotice(
    val newUrlCount: Int,
    val tagNames: List<String>,
)

fun interface SharedTagUpdateNotifier {
    fun notify(notice: SharedTagUpdateNotice)
}

object NoopSharedTagUpdateNotifier : SharedTagUpdateNotifier {
    override fun notify(notice: SharedTagUpdateNotice) = Unit
}

class AndroidSharedTagUpdateNotifier(
    private val context: Context,
) : SharedTagUpdateNotifier {
    @SuppressLint("MissingPermission")
    override fun notify(notice: SharedTagUpdateNotice) {
        if (notice.newUrlCount <= 0 || !canPostNotifications()) return
        createChannel()
        val tagLabel = notice.tagNames.distinct().take(3).joinToString("、")
        val content = if (tagLabel.isBlank()) {
            "共有タグに新しいURLが${notice.newUrlCount}件追加されました"
        } else {
            "${tagLabel}に新しいURLが${notice.newUrlCount}件追加されました"
        }
        val notificationId = NOTIFICATION_ID_BASE +
            ((notice.tagNames.joinToString("|").hashCode() and Int.MAX_VALUE) % 1_000)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_SHARED_TAG_CLOUD, true)
                putExtra(EXTRA_MAIN_INTENT_EVENT_TOKEN, "shared-tag-notification-$notificationId-${System.currentTimeMillis()}")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_rinbam)
            .setContentTitle("共有タグに新着があります")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "共有タグの新着",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "共有タグに別のメンバーがURLを追加した時だけ通知します"
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "shared-tag-updates"
        const val NOTIFICATION_ID_BASE = 4_100
    }
}
