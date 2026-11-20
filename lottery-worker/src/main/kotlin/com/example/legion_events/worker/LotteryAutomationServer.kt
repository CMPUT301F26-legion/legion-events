/*
 * LotteryAutomationServer.kt
 * Purpose: Draws lotteries after Firestore writes and scheduled deadline checks.
 * Role: Trusted backend entry point hosted on an authenticated Cloud Run service.
 * Trigger mode: CLOUD-ONLY. Currently unused by foreground Android triggering.
 * Deploy and configure the event trigger and timer only when choosing cloud operation.
 */
package com.example.legion_events.worker

import com.google.cloud.firestore.Firestore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Receives authenticated Eventarc and Cloud Scheduler requests through Cloud Run's IAM gateway.
 * Eventarc delivers document changes to `/events`. Scheduler calls `/sweep` every minute to
 * catch deadlines that pass without a document write and recover interrupted processing.
 * @param firestore Trusted database connection. Credentials stay on the backend.
 * @param projectId Project used to validate the Firestore CloudEvent source.
 * @param address Listening interface and port. Tests use a loopback address with a temporary port.
 */
internal class LotteryAutomationServer(
    firestore: Firestore,
    private val projectId: String,
    address: InetSocketAddress,
) : AutoCloseable {
    private val worker = LotteryWorker(firestore)
    private val logger = Logger.getLogger(LotteryAutomationServer::class.java.name)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server = HttpServer.create(address, 0).apply {
        createContext("/") { request -> handle(request) }
        this.executor = this@LotteryAutomationServer.executor
    }

    /** Actual listening port, including the allocated port when tests requested port zero. */
    val port: Int get() = server.address.port

    /** Begins accepting requests until [close] is called. */
    fun start() = server.start()

    /** Releases the HTTP listener and its request executor. */
    override fun close() {
        server.stop(1)
        executor.close()
    }

    /** Rechecks current database values and acknowledges an event only after its draw commits. */
    private fun handle(request: HttpExchange) {
        request.use {
            if (request.requestMethod != "POST") {
                respond(request, 405, "POST required")
                return
            }
            if (request.requestURI.path !in setOf("/events", "/sweep")) {
                respond(request, 404, "Unknown endpoint")
                return
            }
            val lotteryId = if (request.requestURI.path == "/events") eventLotteryId(request) else null
            if (request.requestURI.path == "/events" && lotteryId == null) {
                respond(request, 400, "Invalid Firestore event")
                return
            }
            try {
                val drawn = if (lotteryId == null) worker.drawDueLotteries() else if (worker.drawLottery(lotteryId)) 1 else 0
                logger.info("Completed $drawn draw(s) from ${request.requestURI.path}")
                respond(request, 200, "Completed $drawn draw(s)")
            } catch (failure: Exception) {
                // A failed acknowledgement lets Eventarc or Scheduler retry. The draw transaction is idempotent.
                logger.log(Level.SEVERE, "Lottery processing failed for ${lotteryId ?: "deadline sweep"}", failure)
                respond(request, 500, "Lottery processing failed. Check backend logs.")
            }
        }
    }

    /** Validates CloudEvent headers and takes the document ID from its subject, then rereads Firestore. */
    private fun eventLotteryId(request: HttpExchange): String? {
        val headers = request.requestHeaders
        if (headers.getFirst("ce-type") != "google.cloud.firestore.document.v1.written" ||
            headers.getFirst("ce-source") != "//firestore.googleapis.com/projects/$projectId/databases/(default)"
        ) return null
        return Regex("documents/lotteries/([^/]+)").matchEntire(headers.getFirst("ce-subject").orEmpty())
            ?.groupValues?.get(1)
    }

    /** Writes a short status response without exposing entrant data or credentials. */
    private fun respond(request: HttpExchange, status: Int, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        request.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        request.sendResponseHeaders(status, bytes.size.toLong())
        request.responseBody.write(bytes)
    }
}
