package com.example.bardquotahook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * System-Wide Floating Capsule Controller & Headless Background API Sync Engine.
 *
 * Enforces **Single HUD Exclusion**:
 * - Whenever `ModernQuotaModule` (Xposed API 102) or `QuotaHookEntry` binds to Gemini,
 *   `enforceSingleHudExclusivity(context)` automatically closes and disables the System Overlay
 *   so ONLY the single in-app `QuotaHudView` capsule is rendered.
 * - Even while the System Overlay is hidden, `triggerHeadlessSync(context)` services the in-app
 *   HUD's Refresh icon button by scraping `https://gemini.google.com/usage` in the background
 *   and pushing fresh real-time values into `SharedPreferences` and Gemini's HUD.
 */
object OverlayHudController {

    private const val USAGE_URL = "https://gemini.google.com/usage"

    private val hudRef = AtomicReference<WeakReference<QuotaHudView>>(WeakReference(null))
    private val bgWebViewRef = AtomicReference<WeakReference<WebView>>(WeakReference(null))
    private val latestSnapshot = AtomicReference(QuotaHudView.Snapshot())

    @Volatile private var wmParams: WindowManager.LayoutParams? = null
    @Volatile private var mainHandler: Handler? = null
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
     * Automatically disables and removes the System Overlay as soon as Xposed binds to Gemini.
     */
    fun enforceSingleHudExclusivity(context: Context) {
        val appCtx = context.applicationContext ?: context
        try {
            appCtx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("overlay_enabled", false)
                .apply()
        } catch (_: Throwable) {
        }
        if (isShowing || hudRef.get().get() != null) {
            hideWindowOnly(appCtx)
        }
    }

    fun show(context: Context): Boolean {
        val appCtx = context.applicationContext ?: context
        // Single-HUD Exclusion: Do not open System Overlay if Xposed is bound to Gemini
        if (HookTelemetryReceiver.isXposedActivelyBound(appCtx)) {
            enforceSingleHudExclusivity(appCtx)
            return false
        }
        if (!Settings.canDrawOverlays(appCtx)) return false
        if (isShowing && hudRef.get().get() != null) {
            applySavedPreferences(appCtx)
            triggerHeadlessSync(appCtx)
            return true
        }

        val savedSnap = HookTelemetryReceiver.loadSnapshot(appCtx)
        val savedUi = HookTelemetryReceiver.loadUiPrefs(appCtx)
        latestSnapshot.set(savedSnap)

        try {
            appCtx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("overlay_enabled", true)
                .apply()
        } catch (_: Throwable) {
        }

        runOnUi {
            if (HookTelemetryReceiver.isXposedActivelyBound(appCtx)) return@runOnUi
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@runOnUi

            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            if (savedUi.theme == QuotaHudView.ThemeMode.MIUIX && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
                if (savedUi.theme == QuotaHudView.ThemeMode.MIUIX && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        blurBehindRadius = QuotaHudView.dp(appCtx, 22f)
                    } catch (_: Throwable) {
                    }
                }
            }
            wmParams = params

            val pill = QuotaHudView(
                context = appCtx,
                onRefreshClick = { triggerHeadlessSync(appCtx) },
                onOpenUsageClick = { QuotaHudView.openUsageWebpage(appCtx) },
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
            pill.applyPreferences(savedUi.lang, savedUi.theme)
            pill.updateSnapshot(latestSnapshot.get())

            try {
                wm.addView(pill, params)
                hudRef.set(WeakReference(pill))
                isShowing = true
                ensureBackgroundPoller(appCtx)
            } catch (_: Throwable) {
                isShowing = false
            }
        }
        return true
    }

    fun hide(context: Context) {
        val appCtx = context.applicationContext ?: context
        try {
            appCtx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("overlay_enabled", false)
                .apply()
        } catch (_: Throwable) {
        }
        hideWindowOnly(appCtx)
        destroyBackgroundWebView()
    }

    private fun hideWindowOnly(appCtx: Context) {
        runOnUi {
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

    private fun destroyBackgroundWebView() {
        runOnUi {
            val wv = bgWebViewRef.get().get()
            if (wv != null) {
                try {
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Throwable) {
                }
            }
            bgWebViewRef.set(WeakReference(null))
        }
    }

    fun applySavedPreferences(context: Context) {
        val appCtx = context.applicationContext ?: context
        val ui = HookTelemetryReceiver.loadUiPrefs(appCtx)
        runOnUi {
            val pill = hudRef.get().get() ?: return@runOnUi
            pill.applyPreferences(ui.lang, ui.theme)

            val params = wmParams
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (params != null && wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    if (ui.theme == QuotaHudView.ThemeMode.MIUIX) {
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
    }

    fun updateSnapshot(context: Context, snapshot: QuotaHudView.Snapshot) {
        latestSnapshot.set(snapshot)
        runOnUi {
            val pill = hudRef.get().get() ?: return@runOnUi
            pill.updateSnapshot(snapshot)
        }
    }

    /**
     * Headless background API/WebView sync of `https://gemini.google.com/usage`.
     * Invoked when clicking the Refresh icon button on `QuotaHudView` (either in-app Xposed HUD
     * or fallback System Overlay).
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun triggerHeadlessSync(context: Context) {
        val appCtx = context.applicationContext ?: context
        latestSnapshot.set(HookTelemetryReceiver.loadSnapshot(appCtx))
        runOnUi {
            var wv = bgWebViewRef.get().get()
            if (wv == null) {
                try {
                    wv = WebView(appCtx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                scrapeWebView(appCtx, view, 1100L)
                                scrapeWebView(appCtx, view, 3000L)
                            }
                        }
                    }
                    bgWebViewRef.set(WeakReference(wv))
                } catch (_: Throwable) {
                    return@runOnUi
                }
            }
            try {
                wv.loadUrl(USAGE_URL)
            } catch (_: Throwable) {
            }
        }
    }

    private fun ensureBackgroundPoller(appCtx: Context) {
        triggerHeadlessSync(appCtx)
        schedulePeriodicLoop(appCtx)
    }

    private fun schedulePeriodicLoop(appCtx: Context) {
        runOnUi(delayMs = 90_000L) {
            if (!isShowing) return@runOnUi
            triggerHeadlessSync(appCtx)
            schedulePeriodicLoop(appCtx)
        }
    }

    private fun scrapeWebView(appCtx: Context, webView: WebView?, delayMs: Long) {
        if (webView == null) return
        runOnUi(delayMs = delayMs) {
            try {
                webView.evaluateJavascript(
                    "(function(){return document.body ? document.body.innerText : '';})()"
                ) { raw ->
                    try {
                        if (!raw.isNullOrBlank() && raw != "null") {
                            val text = raw.trim('"')
                                .replace("\\n", "\n")
                                .replace("\\\"", "\"")
                            val baseSnap = HookTelemetryReceiver.loadSnapshot(appCtx)
                            val snap = QuotaTextParser.parseUiBlock(text, baseSnap, "UsageWeb")
                            if (snap != null) {
                                HookTelemetryReceiver.pushExternalQuotaToHostApps(appCtx, snap)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }
}
