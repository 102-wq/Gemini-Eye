package com.example.bardquotahook

import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
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
 * Connected directly to `QuotaSyncProvider` (single unified data source):
 * - Enforces **Single HUD Exclusion**: automatically closes the fallback System Overlay whenever
 *   Xposed (`ModernQuotaModule` API 102 or `QuotaHookEntry` API 82) binds to Gemini.
 * - Services background `https://gemini.google.com/usage` sync requests from ANY HUD view and
 *   publishes sanitized values into `QuotaSyncProvider`.
 */
object OverlayHudController {

    private const val USAGE_URL = "https://gemini.google.com/usage"

    private val hudRef = AtomicReference<WeakReference<QuotaHudView>>(WeakReference(null))
    private val bgWebViewRef = AtomicReference<WeakReference<WebView>>(WeakReference(null))
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
     * Automatically disables and removes the System Overlay as soon as Xposed binds to Gemini.
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
            triggerHeadlessSync(appCtx)
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
                ensureBackgroundPoller(appCtx)
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
        destroyBackgroundWebView()
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
        val st = QuotaSyncProvider.ensureLoaded(appCtx)
        updateFromUnifiedState(appCtx, st)
    }

    fun updateSnapshot(context: Context, snapshot: QuotaHudView.Snapshot) {
        val appCtx = context.applicationContext ?: context
        val clean = snapshot.sanitized()
        latestSnapshot.set(clean)
        runOnUi {
            val pill = hudRef.get().get() ?: return@runOnUi
            pill.updateSnapshot(clean)
        }
    }

    /**
     * Headless background API/WebView sync of `https://gemini.google.com/usage`.
     * Writes fresh quota into `QuotaSyncProvider` so all HUDs update identically.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun triggerHeadlessSync(context: Context) {
        val appCtx = context.applicationContext ?: context
        latestSnapshot.set(QuotaSyncProvider.ensureLoaded(appCtx).snapshot.sanitized())
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
                                val currentUrl = url.orEmpty().lowercase()
                                if (currentUrl.contains("gemini.google.com/usage")) {
                                    scrapeWebView(appCtx, view, 1100L)
                                    scrapeWebView(appCtx, view, 3000L)
                                }
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
                val activeUrl = webView.url.orEmpty().lowercase()
                if (!activeUrl.contains("gemini.google.com/usage")) return@runOnUi
                webView.evaluateJavascript(
                    "(function(){return document.body ? document.body.innerText : '';})()"
                ) { raw ->
                    try {
                        if (!raw.isNullOrBlank() && raw != "null") {
                            val text = raw.trim('"')
                                .replace("\\n", "\n")
                                .replace("\\\"", "\"")
                            val baseSnap = QuotaSyncProvider.ensureLoaded(appCtx).snapshot.sanitized()
                            val snap = QuotaTextParser.parseUiBlock(
                                block = text,
                                prev = baseSnap,
                                source = "UsageWeb",
                                requireUsageStructure = true
                            )
                            if (snap != null) {
                                QuotaSyncProvider.publishQuota(appCtx, snap)
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
