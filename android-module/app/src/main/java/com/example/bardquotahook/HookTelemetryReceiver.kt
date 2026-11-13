package com.example.bardquotahook

import android.content.Context
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight In-Memory UI Listener Helper (`HookTelemetryReceiver` — v2.1.0).
 *
 * All manifest broadcast wakeups and stopped-package background intents have been
 * removed. Notifies `MainActivity` strictly in-memory when open in the foreground.
 */
object HookTelemetryReceiver {

    data class AppUiPrefs(
        val lang: QuotaHudView.Lang = QuotaSyncProvider.defaultLangForDevice(),
        val theme: QuotaHudView.ThemeMode = QuotaHudView.ThemeMode.MIUIX,
        val sessionId: String = "",
        val xposedBound: Boolean = false,
        val isApi102: Boolean = false,
        val lastHeartbeatMs: Long = 0L,
        val lastPkg: String = "",
        val lastBridge: String = ""
    )

    private val uiListenerRef = AtomicReference<WeakReference<() -> Unit>>(WeakReference(null))

    fun setUiListener(listener: (() -> Unit)?) {
        uiListenerRef.set(WeakReference(listener))
    }

    fun notifyUiListeners() {
        uiListenerRef.get().get()?.invoke()
    }

    fun loadUiPrefs(context: Context): AppUiPrefs {
        return try {
            val st = QuotaSyncProvider.ensureLoaded(context)
            AppUiPrefs(
                lang = st.lang,
                theme = st.theme,
                sessionId = st.sessionId,
                xposedBound = st.isActivelyBound,
                isApi102 = st.isApi102,
                lastHeartbeatMs = st.lastHeartbeatMs,
                lastPkg = st.lastPkg,
                lastBridge = st.lastBridge
            )
        } catch (_: Throwable) {
            AppUiPrefs()
        }
    }
}
