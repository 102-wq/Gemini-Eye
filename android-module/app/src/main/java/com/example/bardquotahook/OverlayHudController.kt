package com.example.bardquotahook

import android.content.Context
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * Passive Fallback System Overlay Controller (`OverlayHudController`).
 *
 * Strictly zero external HTTP polling or web fallback:
 * - Enforces **Single-HUD Exclusion**: automatically suppresses and removes the App System Overlay
 *   as soon as `ModernQuotaModule` (API 102) or `QuotaHookEntry` (API 82) binds to Gemini.
 * - Displays values solely from `QuotaSyncProvider` and dispatches in-process memory scan pings.
 */
object OverlayHudController {

    private val hudRef = AtomicReference<WeakReference<QuotaHudView>>(WeakReference(null))
    private val latestSnapshot = AtomicReference(QuotaHudView.Snapshot())

    @Volatile private var wmParams: WindowManager.LayoutParams? = null
    @Volatile private var mainHandler: Handler? = null
    @Volatile private var stateObserver: ContentObserver? = null
    @Volatile var isShowing: Boolean = false
        private set

    private fun runOnUi(delayMs: Long = 0L, block: () -> Unit) {
        try {
            val looper = Looper.getMainLooper() ?: return
            val handler = mainHandler ?: Handler(looper).also { mainHandler = it }
            val r = Runnable {
                try {
                    block()
                } catch (_: Throwable) {
                }
            }
            if (delayMs > 0L) handler.postDelayed(r, delayMs) else handler.post(r)
        } catch (_: Throwable) {
        }
    }

    /**
     * Automatically suppresses and removes the App System Overlay whenever Xposed binds to Gemini.
     */
    fun enforceSingleHudExclusivity(context: Context) {
        val appCtx = context.applicationContext ?: context
        if (isShowing || hudRef.get().get() != null) {
            hideWindowOnly(appCtx)
        }
    }

    fun updateFromUnifiedState(context: Context, state: QuotaSyncProvider.UnifiedState) {
        val appCtx = context.applicationContext ?: context
        val clean = state.snapshot.sanitized()
        latestSnapshot.set(clean)

        if (state.isActivelyBound) {
            enforceSingleHudExclusivity(appCtx)
            return
        }

        runOnUi {
            val pill = hudRef.get().get() ?: return@runOnUi
            pill.applyPreferences(state.lang, state.theme)
            pill.updateSnapshot(clean)
            updateBlurFlagIfNeeded(appCtx, pill, state.theme)
        }
    }

    private fun updateBlurFlagIfNeeded(
        appCtx: Context,
        pill: QuotaHudView,
        theme: QuotaHudView.ThemeMode
    ) {
        val params = wmParams ?: return
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (theme == QuotaHudView.ThemeMode.MIUIX) {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    params.blurBehindRadius = QuotaHudView.dp(appCtx, 22f)
                } else {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                    params.blurBehindRadius = 0
                }
                wm.updateViewLayout(pill, params)
            } catch (_: Throwable) {
            }
        }
    }

    fun show(context: Context): Boolean {
        val appCtx = context.applicationContext ?: context
        val state = QuotaSyncProvider.ensureLoaded(appCtx)
        if (state.isActivelyBound) {
            enforceSingleHudExclusivity(appCtx)
            return false
        }
        if (!Settings.canDrawOverlays(appCtx)) return false
        if (isShowing && hudRef.get().get() != null) {
            updateFromUnifiedState(appCtx, state)
            QuotaSyncProvider.requestInProcessRefresh(appCtx, appCtx.packageName)
            return true
        }

        latestSnapshot.set(state.snapshot.sanitized())
        QuotaSyncProvider.updatePreferences(appCtx, overlayEnabled = true)

        runOnUi {
            val latestState = QuotaSyncProvider.ensureLoaded(appCtx)
            if (latestState.isActivelyBound) return@runOnUi
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@runOnUi

            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            if (latestState.theme == QuotaHudView.ThemeMode.MIUIX && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = QuotaHudView.dp(appCtx, 10f)
                y = QuotaHudView.dp(appCtx, 54f)
                if (latestState.theme == QuotaHudView.ThemeMode.MIUIX && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        blurBehindRadius = QuotaHudView.dp(appCtx, 22f)
                    } catch (_: Throwable) {
                    }
                }
            }
            wmParams = params

            val pill = QuotaHudView(
                context = appCtx,
                onRefreshClick = {
                    QuotaSyncProvider.requestInProcessRefresh(appCtx, appCtx.packageName)
                },
                onOpenUsageClick = {
                    QuotaHudView.openModuleSettings(appCtx)
                },
                onWindowDrag = { dx, dy ->
                    params.x = maxOf(0, params.x - dx)
                    params.y = maxOf(0, params.y + dy)
                    try {
                        wm.updateViewLayout(hudRef.get().get(), params)
                    } catch (_: Throwable) {
                    }
                },
                draggableInFrame = false
            )
            pill.applyPreferences(latestState.lang, latestState.theme)
            pill.updateSnapshot(latestState.snapshot.sanitized())

            try {
                wm.addView(pill, params)
                hudRef.set(WeakReference(pill))
                isShowing = true
                if (stateObserver == null) {
                    stateObserver = QuotaSyncProvider.registerStateObserver(appCtx) {
                        val s = QuotaSyncProvider.ensureLoaded(appCtx)
                        updateFromUnifiedState(appCtx, s)
                    }
                }
                QuotaSyncProvider.requestInProcessRefresh(appCtx, appCtx.packageName)
            } catch (_: Throwable) {
                isShowing = false
            }
        }
        return true
    }

    fun hide(context: Context) {
        val appCtx = context.applicationContext ?: context
        QuotaSyncProvider.updatePreferences(appCtx, overlayEnabled = false)
        hideWindowOnly(appCtx)
    }

    private fun hideWindowOnly(appCtx: Context) {
        runOnUi {
            QuotaSyncProvider.unregisterStateObserver(appCtx, stateObserver)
            stateObserver = null
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val pill = hudRef.get().get()
            if (wm != null && pill != null) {
                try {
                    wm.removeView(pill)
                } catch (_: Throwable) {
                }
            }
            hudRef.set(WeakReference(null))
            isShowing = false
            HookTelemetryReceiver.notifyUiListeners()
        }
    }
}
