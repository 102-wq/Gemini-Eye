package com.example.bardquotahook

import android.content.Context

/**
 * Zero-Background Single-HUD Exclusivity Guard (`OverlayHudController` — v2.1.0).
 *
 * Persistent system-wide `TYPE_APPLICATION_OVERLAY` windows and background services have been
 * completely removed so the HUD exists strictly inside Gemini's foreground `Activity` lifecycle
 * and sleeps with 0 background overhead when Gemini goes to the background.
 */
object OverlayHudController {

    val isShowing: Boolean = false

    fun enforceSingleHudExclusivity(context: Context) {
        // Guaranteed by architecture: zero system-alert windows or background overlay services exist.
    }

    fun updateFromUnifiedState(context: Context, state: QuotaSyncProvider.UnifiedState) {
        // No-op: HUD renders strictly inside Gemini's foreground Activity decorView.
    }

    fun show(context: Context): Boolean = false

    fun hide(context: Context) {}
}
