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

/**
 * Processes due lotteries in `FIREBASE_PROJECT_ID` and prints the number of completed draws.
 * When `FIRESTORE_EMULATOR_HOST` is set, the project ID must start with `demo-`.
 * Live runs require `LOTTERY_DRAW_ENABLED=true` and Google Application Default Credentials.
 *
 * @throws IllegalStateException If required configuration is missing or a draw fails.
 */
fun main() {
    val projectId = checkNotNull(System.getenv("FIREBASE_PROJECT_ID")?.takeIf { it.isNotBlank() }) {
        "Set FIREBASE_PROJECT_ID before running the worker."
    }
    val emulatorHost = System.getenv("FIRESTORE_EMULATOR_HOST")?.takeIf { it.isNotBlank() }
    if (emulatorHost != null) {
        check(projectId.startsWith("demo-")) { "Use a demo project ID with the Firestore emulator." }
        FirestoreOptions.newBuilder().setProjectId(projectId).setEmulatorHost(emulatorHost).build()
            .service.use { println("Completed ${LotteryWorker(it).drawDueLotteries()} lottery draw(s).") }
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
        val drawn = LotteryWorker(FirestoreClient.getFirestore(app)).drawDueLotteries()
        println("Completed $drawn lottery draw(s).")
    } finally {
        app.delete()
    }
}
