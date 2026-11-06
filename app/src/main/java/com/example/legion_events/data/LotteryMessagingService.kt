/* Receives lottery push messages and updates the profile's FCM registration. */
package com.example.legion_events.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.legion_events.MainActivity
import com.example.legion_events.R
import com.example.legion_events.model.LotteryResult
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Extra carrying the recipient UID when a lottery push opens the inbox. */
internal const val LOTTERY_INBOX_RECIPIENT = "com.example.legion_events.LOTTERY_INBOX_RECIPIENT"

private const val LOTTERY_CHANNEL = "lottery_results"

/** Stores a device registration for the matching Firebase account after its profile exists. */
internal fun registerLotteryPushToken(userId: String, token: String): Task<Void> {
    if (FirebaseAuth.getInstance().currentUser?.uid != userId) return Tasks.forResult(null)
    return FirebaseFirestore.getInstance().collection("users").document(userId)
        .update("fcmToken", token, "updatedAt", FieldValue.serverTimestamp())
}

/** Handles foreground and background data messages from the trusted lottery worker. */
class LotteryMessagingService : FirebaseMessagingService() {
    /** Updates the saved registration when Firebase rotates the device token. */
    override fun onNewToken(token: String) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        registerLotteryPushToken(userId, token).addOnFailureListener {
            Log.w("LotteryPush", "Could not save the refreshed push registration.")
        }
    }

    /** Shows the result only for the matching account and when Android allows notifications. */
    override fun onMessageReceived(message: RemoteMessage) {
        val eventId = message.data["eventId"]?.takeIf(String::isNotBlank) ?: return
        val userId = message.data["toUserId"] ?: return
        val result = LotteryResult.fromStoredType(message.data["type"]) ?: return
        if (FirebaseAuth.getInstance().currentUser?.uid != userId) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notifications = NotificationManagerCompat.from(this)
        if (!notifications.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(LOTTERY_CHANNEL, getString(R.string.lottery_notification_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        val intent = Intent(this, MainActivity::class.java)
            .setData(Uri.Builder().scheme("legion-notification").authority("inbox").appendPath(eventId).build())
            .putExtra(LOTTERY_INBOX_RECIPIENT, userId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openInbox = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, LOTTERY_CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_inbox)
            .setContentTitle(getString(result.title))
            .setContentText(getString(result.message))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openInbox)
            .build()
        // Reusing the event ID lets a retried push update the existing notification.
        notifications.notify(eventId, 0, notification)
    }
}
