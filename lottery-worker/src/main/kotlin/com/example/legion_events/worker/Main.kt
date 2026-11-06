/*
 * Main.kt
 *
 * Purpose: Starts the standalone lottery worker from GitHub Actions or a local emulator.
 * Role: Command-line entry point that selects the Firestore connection.
 */
package com.example.legion_events.worker

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.FirestoreOptions
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Processes due lotteries in `FIREBASE_PROJECT_ID` and prints the number of completed draws.
 * When `FIRESTORE_EMULATOR_HOST` is set, the project ID must start with `demo-`.
 * Live runs require `LOTTERY_DRAW_ENABLED=true` and Google Application Default Credentials.
 *
 * @param args Empty to draw lotteries, or `deliver-notifications` to publish queued results.
 * @throws IllegalStateException If required configuration is missing or processing fails.
 */
fun main(args: Array<String>) {
    require(args.isEmpty() || args.contentEquals(arrayOf("deliver-notifications"))) { "Unknown worker command." }
    val deliverNotifications = args.isNotEmpty()
    val projectId = checkNotNull(System.getenv("FIREBASE_PROJECT_ID")?.takeIf { it.isNotBlank() }) {
        "Set FIREBASE_PROJECT_ID before running the worker."
    }
    val emulatorHost = System.getenv("FIRESTORE_EMULATOR_HOST")?.takeIf { it.isNotBlank() }
    if (emulatorHost != null) {
        check(projectId.startsWith("demo-")) { "Use a demo project ID with the Firestore emulator." }
        FirestoreOptions.newBuilder().setProjectId(projectId).setEmulatorHost(emulatorHost).build()
            .service.use { firestore ->
                if (deliverNotifications) {
                    val delivered = NotificationDelivery(firestore) { false }.deliverPending()
                    println("Completed $delivered notification(s) in the emulator.")
                } else {
                    println("Completed ${LotteryWorker(firestore).drawDueLotteries()} lottery draw(s).")
                }
            }
        return
    }

    check(System.getenv("LOTTERY_DRAW_ENABLED") == "true") { "Set LOTTERY_DRAW_ENABLED to enable live draws." }
    val app = FirebaseApp.initializeApp(
        FirebaseOptions.builder()
            .setProjectId(projectId)
            .setCredentials(GoogleCredentials.getApplicationDefault())
            .build(),
    )
    try {
        val firestore = FirestoreClient.getFirestore(app)
        if (deliverNotifications) {
            val messaging = FirebaseMessaging.getInstance(app)
            val delivered = NotificationDelivery(firestore) { sendLotteryPush(messaging, it) }.deliverPending()
            println("Completed $delivered notification(s).")
        } else {
            println("Completed ${LotteryWorker(firestore).drawDueLotteries()} lottery draw(s).")
        }
    } finally {
        app.delete()
    }
}

/** Sends a visible Android push request and returns false for an unregistered device token. */
private fun sendLotteryPush(messaging: FirebaseMessaging, push: LotteryPush): Boolean {
    val message = Message.builder()
        .setToken(push.token)
        .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).setTtl(86_400_000).build())
        .putData("eventId", push.eventId)
        .putData("toUserId", push.userId)
        .putData("type", push.type)
        .build()
    try {
        messaging.sendAsync(message).get(30, TimeUnit.SECONDS)
        return true
    } catch (error: ExecutionException) {
        val code = (error.cause as? FirebaseMessagingException)?.messagingErrorCode
        if (code == MessagingErrorCode.UNREGISTERED) return false
        error("FCM send failed (${code ?: "unknown error"}).")
    } catch (_: TimeoutException) {
        error("FCM send timed out.")
    }
}
