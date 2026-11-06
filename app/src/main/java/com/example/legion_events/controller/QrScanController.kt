/*
 * QrScanController.kt
 *
 * Purpose: Tracks the S05 QR scan and accepts only valid campsite codes (US 01.12.01).
 * Role: Controller in MVC; QrScanScreen renders its state; CampsiteQr validates codes.
 * Outstanding issues: None.
 */
package com.example.legion_events.controller

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.example.legion_events.model.CampsiteQr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Camera launch, scan outcome, or recovery step displayed by S05. */
enum class QrScanStatus { READY, SCANNING, INVALID, PERMISSION_DENIED, FAILED, CANCELLED, FOUND }

/**
 * Current scanner state, independent of the camera library.
 * @property status Whether to launch the camera, show recovery actions, or leave S05.
 * @property campsiteId Validated document ID when a campsite code was found.
 */
data class QrScanState(val status: QrScanStatus = QrScanStatus.READY, val campsiteId: String? = null)

/**
 * Validates QR results using the same format as S14 and prevents duplicate launches or results.
 * @property savedState Retains the pending scan across screen and process recreation.
 */
class QrScanController(private val savedState: SavedStateHandle) : ViewModel() {
    /** Scanner state changed only by this controller. */
    private val mutableState = MutableStateFlow(QrScanState(
        status = QrScanStatus.valueOf(savedState["status"] ?: QrScanStatus.READY.name),
        campsiteId = savedState["campsiteId"],
    ))
    /** Read-only state rendered by S05 and observed by the shared router. */
    val state = mutableState.asStateFlow()

    /** Marks the launch before opening the camera, so recreation cannot launch it twice. */
    fun scanStarted() {
        if (state.value.status == QrScanStatus.READY) update(QrScanStatus.SCANNING)
    }

    /** Accepts one camera result; null means the user cancelled without choosing a campsite. */
    fun scanResult(text: String?) {
        if (state.value.status != QrScanStatus.SCANNING) return
        if (text == null) update(QrScanStatus.CANCELLED)
        else {
            val campsiteId = CampsiteQr.decode(text)
            update(if (campsiteId == null) QrScanStatus.INVALID else QrScanStatus.FOUND, campsiteId)
        }
    }

    /** Records camera or permission failure while keeping the user on S05 for recovery. */
    fun cameraFailed(permissionDenied: Boolean) {
        if (state.value.status == QrScanStatus.SCANNING) {
            update(if (permissionDenied) QrScanStatus.PERMISSION_DENIED else QrScanStatus.FAILED)
        }
    }

    /** Requests another scan only after an invalid code or camera failure. */
    fun retry() {
        if (state.value.status in setOf(QrScanStatus.INVALID, QrScanStatus.PERMISSION_DENIED, QrScanStatus.FAILED)) {
            update(QrScanStatus.READY)
        }
    }

    /** Saves simple values so an activity-result callback can finish a scan after process restoration. */
    private fun update(status: QrScanStatus, campsiteId: String? = null) {
        savedState["status"] = status.name
        savedState["campsiteId"] = campsiteId
        mutableState.value = QrScanState(status, campsiteId)
    }
}
