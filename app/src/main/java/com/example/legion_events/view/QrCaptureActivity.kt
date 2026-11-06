/*
 * QrCaptureActivity.kt
 *
 * Purpose: The camera screen used by S05, with a visible cancel button (US 01.12.01).
 * Role: View; extends the ZXing CaptureActivity, which manages the camera.
 * Outstanding issues: None.
 */
package com.example.legion_events.view

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/** Adds a visible cancel button while the scanner library manages the camera and its lifecycle. */
class QrCaptureActivity : CaptureActivity() {
    /**
     * Overlays Back to search on the camera preview, clear of system bars and display cutouts.
     * Finishing with a cancelled result uses the controller's existing return-to-search flow.
     * @return The scanner view initialized by the camera library.
     */
    override fun initializeContent(): DecoratedBarcodeView {
        val scanner = super.initializeContent()
        val margin = (16 * resources.displayMetrics.density).toInt()
        val back = Button(this).apply {
            text = "Back to search"
            isAllCaps = false
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
        addContentView(back, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        ).apply { topMargin = margin; marginStart = margin })
        ViewCompat.setOnApplyWindowInsetsListener(back) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = margin + safe.top
                marginStart = margin + if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) safe.right else safe.left
            }
            insets
        }
        ViewCompat.requestApplyInsets(back)
        return scanner
    }
}
