package com.example.bardquotahook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional System-Wide Floating Capsule Fallback (`TYPE_APPLICATION_OVERLAY`).
 * Works even when Magisk DenyList / Shamiko blocks Xposed injection into Google App,
 * and silently refreshes `https://gemini.google.com/usage` in the background while active.
 */
object OverlayHudController {

    private const val USAGE_URL = "https://gemini.google.com/usage"

    private val containerRef = AtomicReference<WeakReference<LinearLayout>>(WeakReference(null))
    private val bgWebViewRef = AtomicReference<WeakReference<WebView>>(WeakReference(null))
    private val latestSnapshot = AtomicReference(QuotaHudLayout.Snapshot())

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

    fun show(context: Context): Boolean {
        val appCtx = context.applicationContext
        if (!Settings.canDrawOverlays(appCtx)) return false
        if (isShowing && containerRef.get().get() != null) {
            refreshNow(appCtx)
            return true
        }

        val saved = HookTelemetryReceiver.loadSnapshot(appCtx)
        latestSnapshot.set(saved)

        runOnUi {
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@runOnUi
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = QuotaHudLayout.dp(appCtx, 12f)
                y = QuotaHudLayout.dp(appCtx, 56f)
            }
            wmParams = params

            val pill = QuotaHudLayout.createContainer(
                context = appCtx,
                snapshotProvider = { latestSnapshot.get() },
                onTapRefresh = { refreshNow(appCtx) },
                onWindowDrag = { dx, dy ->
                    // Gravity.END inverts horizontal X offset direction
                    params.x = maxOf(0, params.x - dx)
                    params.y = maxOf(0, params.y + dy)
                    try {
                        wm.updateViewLayout(containerRef.get().get(), params)
                    } catch (_: Throwable) {
                    }
                }
            )
            QuotaHudLayout.bind(appCtx, pill, latestSnapshot.get())
            try {
                wm.addView(pill, params)
                containerRef.set(WeakReference(pill))
                isShowing = true
                ensureBackgroundPoller(appCtx)
            } catch (_: Throwable) {
                isShowing = false
            }
        }
        return true
    }

    fun hide(context: Context) {
        val appCtx = context.applicationContext
        runOnUi {
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val pill = containerRef.get().get()
            if (wm != null && pill != null) {
                try {
                    wm.removeView(pill)
                } catch (_: Throwable) {
                }
            }
            containerRef.set(WeakReference(null))
            isShowing = false

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

    fun updateSnapshot(context: Context, snapshot: QuotaHudLayout.Snapshot) {
        latestSnapshot.set(snapshot)
        val appCtx = context.applicationContext
        runOnUi {
            val pill = containerRef.get().get() ?: return@runOnUi
            QuotaHudLayout.bind(appCtx, pill, snapshot)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun refreshNow(context: Context) {
        val appCtx = context.applicationContext
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
                                scrapeWebView(appCtx, view, 1200L)
                                scrapeWebView(appCtx, view, 3200L)
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
        refreshNow(appCtx)
        schedulePeriodicLoop(appCtx)
    }

    private fun schedulePeriodicLoop(appCtx: Context) {
        runOnUi(delayMs = 90_000L) {
            if (!isShowing) return@runOnUi
            refreshNow(appCtx)
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
                            val snap = QuotaTextParser.parseUiBlock(text, latestSnapshot.get(), "UsageWeb")
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
