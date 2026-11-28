/*
 * BookingAutomationServer.kt
 * Purpose: Process booking writes promptly and recover missed writes on scheduled sweeps.
 * Role: Authenticated Cloud Run entry point independent of lottery-draw automation.
 * Outstanding issues: Requires deployment and event/timer setup after review; defaults off.
 */
package com.example.legion_events.worker

import com.google.cloud.firestore.Firestore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/** IAM authenticates callers; CloudEvent source and document paths are validated before database access. */
internal class BookingAutomationServer(firestore: Firestore, private val projectId: String, address: InetSocketAddress) : AutoCloseable {
    private val processor = BookingProcessor(firestore)
    private val logger = Logger.getLogger(BookingAutomationServer::class.java.name)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server = HttpServer.create(address, 0).apply {
        createContext("/") { request -> handle(request) }
        this.executor = this@BookingAutomationServer.executor
    }
    /** Actual port, including a temporary loopback port selected by the tests. */
    val port: Int get() = server.address.port
    /** Begins handling requests until shutdown. */
    fun start() = server.start()
    /** Closes the listener and its task executor. */
    override fun close() { server.stop(1); executor.close() }

    /** Acknowledges only completed work; non-2xx results permit infrastructure retries. */
    private fun handle(request: HttpExchange) = request.use {
        if (request.requestMethod != "POST") return@use respond(request, 405, "POST required")
        if (request.requestURI.path !in setOf("/events", "/sweep")) return@use respond(request, 404, "Unknown endpoint")
        val event = if (request.requestURI.path == "/events") eventDocument(request) else null
        if (request.requestURI.path == "/events" && event == null) return@use respond(request, 400, "Invalid Firestore event")
        try {
            if (event == null) processor.sweep()
            else when (event.first) {
                "reservations" -> processor.reservationChanged(event.second)
                "offers" -> processor.offerChanged(event.second)
            }
            respond(request, 200, "Booking processing completed")
        } catch (failure: Exception) {
            logger.log(Level.SEVERE, "Booking processing failed", failure)
            respond(request, 500, "Booking processing failed. Check backend logs.")
        }
    }

    /** Takes identity from the verified document path and rereads Firestore instead of trusting event data. */
    private fun eventDocument(request: HttpExchange): Pair<String, String>? {
        val headers = request.requestHeaders
        if (headers.getFirst("ce-type") != "google.cloud.firestore.document.v1.written" ||
            headers.getFirst("ce-source") != "//firestore.googleapis.com/projects/$projectId/databases/(default)") return null
        val match = Regex("documents/(reservations|offers)/([^/]+)").matchEntire(headers.getFirst("ce-subject").orEmpty()) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    /** Returns status without exposing booking records or credentials. */
    private fun respond(request: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        request.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        request.sendResponseHeaders(status, bytes.size.toLong())
        request.responseBody.write(bytes)
    }
}
