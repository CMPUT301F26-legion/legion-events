/*
 * QrScanControllerTest.kt
 *
 * Purpose: Unit tests for QrScanController validation, retry, and restoration (US 01.12.01).
 * Role: No camera or Firebase needed.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.SavedStateHandle
import com.example.legion_events.model.CampsiteQr
import org.junit.Assert.*
import org.junit.Test

/** Checks scan validation, retry, and restoration without requiring a camera or Firebase. */
class QrScanControllerTest {
    @Test fun aCampsiteCodeIsAcceptedOnce() {
        val controller = QrScanController(SavedStateHandle())
        controller.scanStarted()
        controller.scanResult(CampsiteQr.encode("pine"))
        assertEquals(QrScanState(QrScanStatus.FOUND, "pine"), controller.state.value)
        controller.scanResult(CampsiteQr.encode("other"))
        controller.retry()
        assertEquals("pine", controller.state.value.campsiteId)
        assertEquals(QrScanStatus.FOUND, controller.state.value.status)
    }

    @Test fun unrelatedCodesStayOnTheScannerAndCanBeRetried() {
        val controller = QrScanController(SavedStateHandle())
        controller.scanStarted()
        controller.scanResult("https://example.com")
        assertEquals(QrScanStatus.INVALID, controller.state.value.status)
        assertNull(controller.state.value.campsiteId)
        controller.retry()
        assertEquals(QrScanStatus.READY, controller.state.value.status)
        controller.scanStarted()
        controller.scanResult(CampsiteQr.encode("pine"))
        assertEquals("pine", controller.state.value.campsiteId)
    }

    @Test fun cancellationDoesNotOpenAnyCampsite() {
        val controller = QrScanController(SavedStateHandle())
        controller.scanStarted()
        controller.scanResult(null)
        assertEquals(QrScanStatus.CANCELLED, controller.state.value.status)
        assertNull(controller.state.value.campsiteId)
    }

    @Test fun permissionAndCameraFailuresCanBeRetried() {
        val controller = QrScanController(SavedStateHandle())
        controller.scanStarted()
        controller.cameraFailed(true)
        assertEquals(QrScanStatus.PERMISSION_DENIED, controller.state.value.status)
        controller.retry()
        controller.scanStarted()
        controller.cameraFailed(false)
        assertEquals(QrScanStatus.FAILED, controller.state.value.status)
        controller.retry()
        assertEquals(QrScanStatus.READY, controller.state.value.status)
    }

    @Test fun restoringAPendingScanDoesNotRequestAnotherCameraLaunch() {
        val savedState = SavedStateHandle()
        val original = QrScanController(savedState)
        original.scanStarted()
        val restored = QrScanController(savedState)
        assertEquals(QrScanStatus.SCANNING, restored.state.value.status)
        restored.scanResult(CampsiteQr.encode("pine"))
        val readyToNavigate = QrScanController(savedState)
        assertEquals(QrScanState(QrScanStatus.FOUND, "pine"), readyToNavigate.state.value)
    }

    @Test fun restoringAnErrorKeepsRecoveryAvailable() {
        val savedState = SavedStateHandle()
        val original = QrScanController(savedState)
        original.scanStarted()
        original.cameraFailed(true)
        val restored = QrScanController(savedState)
        assertEquals(QrScanStatus.PERMISSION_DENIED, restored.state.value.status)
        restored.retry()
        assertEquals(QrScanStatus.READY, restored.state.value.status)
    }
}
