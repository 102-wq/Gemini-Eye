package com.example.bardquotahook

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val USAGE_URL = "https://gemini.google.com/usage"
    }

    private var titleView: TextView? = null
    private var pkgView: TextView? = null
    private var previewCaptionView: TextView? = null
    private var liveCapsulePreview: QuotaHudView? = null

    private var settingsSectionTitle: TextView? = null
    private var langZhBtn: Button? = null
    private var langEnBtn: Button? = null
    private var themeMiuixBtn: Button? = null
    private var themeMd3Btn: Button? = null

    private var hookStatusView: TextView? = null
    private var quotaPreviewView: TextView? = null
    private var pingBtn: Button? = null

    private var syncDescView: TextView? = null
    private var syncWebBtn: Button? = null
    private var webContainer: FrameLayout? = null
    private var embeddedWebView: WebView? = null

    private var overlayToggleBtn: Button? = null
    private var launchGeminiBtn: Button? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var stateObserver: ContentObserver? = null
    private var webExpanded = false
    private var bardInstalled = false
    private var gsaInstalled = false

    private fun dp(v: Float): Int = QuotaHudView.dp(this, v)

    private fun localizedContext(lang: QuotaHudView.Lang): Context {
        return try {
            val locale = if (lang == QuotaHudView.Lang.ZH) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
            val cfg = Configuration(resources.configuration).apply {
                setLocale(locale)
            }
            createConfigurationContext(cfg)
        } catch (_: Throwable) {
            this
        }
    }

    private fun isPkgInstalled(pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Throwable) {
            packageManager.getLaunchIntentForPackage(pkg) != null
        }
    }

    private fun pingHookedProcesses() {
        val st = QuotaSyncProvider.ensureLoaded(this)
        HookTelemetryReceiver.syncStateToHostApps(this, st, includePing = true)
    }

    private fun launchGeminiWithFallback() {
        val candidates = listOf(
            packageManager.getLaunchIntentForPackage("com.google.android.apps.bard"),
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(
                    "com.google.android.apps.bard",
                    "com.google.android.apps.bard.shellapp.BardEntryPointActivity"
                )
            },
            packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox"),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/app"))
        )

        for (intent in candidates) {
            if (intent == null) continue
            val started = runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            }.getOrDefault(false)
            if (started) return
        }
    }

    private fun makeCardDrawable(bgHex: String, borderHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = dp(16f).toFloat()
            setStroke(maxOf(1, dp(1f)), Color.parseColor(borderHex))
        }
    }

    private fun makeBtnDrawable(bgHex: String, borderHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = dp(12f).toFloat()
            if (borderHex != null) {
                setStroke(maxOf(1, dp(1f)), Color.parseColor(borderHex))
            }
        }
    }

    private fun refreshAllUi() {
        val state = QuotaSyncProvider.ensureLoaded(this)
        val snap = state.snapshot.sanitized()
        val zh = state.lang == QuotaHudView.Lang.ZH
        val loc = localizedContext(state.lang)

        if (state.isActivelyBound && OverlayHudController.isShowing) {
            OverlayHudController.enforceSingleHudExclusivity(this)
        }

        titleView?.text = loc.getString(R.string.settings_title)

        val installedLabel = loc.getString(R.string.settings_status_installed)
        val missingLabel = loc.getString(R.string.settings_status_missing)
        pkgView?.text = loc.getString(
            R.string.settings_targets_fmt,
            if (bardInstalled) installedLabel else missingLabel,
            if (gsaInstalled) installedLabel else missingLabel
        )

        previewCaptionView?.text = loc.getString(R.string.settings_preview_caption)

        liveCapsulePreview?.applyPreferences(state.lang, state.theme)
        liveCapsulePreview?.updateSnapshot(snap)

        settingsSectionTitle?.text = loc.getString(R.string.settings_appearance_title)

        langZhBtn?.apply {
            text = loc.getString(R.string.settings_lang_zh)
            background = if (zh) makeBtnDrawable("#2563EB") else makeBtnDrawable("#0F172A", "#334155")
            setTextColor(if (zh) Color.WHITE else Color.parseColor("#94A3B8"))
        }
        langEnBtn?.apply {
            text = loc.getString(R.string.settings_lang_en)
            background = if (!zh) makeBtnDrawable("#2563EB") else makeBtnDrawable("#0F172A", "#334155")
            setTextColor(if (!zh) Color.WHITE else Color.parseColor("#94A3B8"))
        }

        val isMiuix = state.theme == QuotaHudView.ThemeMode.MIUIX
        themeMiuixBtn?.apply {
            text = loc.getString(R.string.settings_theme_miuix)
            background = if (isMiuix) makeBtnDrawable("#0284C7") else makeBtnDrawable("#0F172A", "#334155")
            setTextColor(if (isMiuix) Color.WHITE else Color.parseColor("#94A3B8"))
        }
        themeMd3Btn?.apply {
            text = loc.getString(R.string.settings_theme_md3)
            background = if (!isMiuix) makeBtnDrawable("#7C3AED") else makeBtnDrawable("#0F172A", "#334155")
            setTextColor(if (!isMiuix) Color.WHITE else Color.parseColor("#94A3B8"))
        }

        hookStatusView?.text = if (state.isActivelyBound) {
            val secAgo = maxOf(0L, (System.currentTimeMillis() - state.lastHeartbeatMs) / 1000L)
            loc.getString(
                R.string.settings_hook_bound_fmt,
                state.lastBridge.ifBlank { "Xposed" },
                state.lastPkg.ifBlank { "com.google.android.apps.bard" },
                secAgo
            )
        } else {
            loc.getString(R.string.settings_hook_waiting)
        }

        val label5h = loc.getString(R.string.hud_label_5h)
        val labelWk = loc.getString(R.string.hud_label_weekly)
        quotaPreviewView?.text =
            "$label5h: ${snap.formatFiveHourDisplay()}   |   $labelWk: ${snap.formatWeeklyDisplay()}   [${snap.source}]"

        pingBtn?.text = loc.getString(R.string.settings_sync_now)
        syncDescView?.text = loc.getString(R.string.settings_sync_desc)
        syncWebBtn?.text = if (webExpanded) {
            loc.getString(R.string.settings_reload_web_sync)
        } else {
            loc.getString(R.string.settings_open_web_sync)
        }

        val overlayOn = OverlayHudController.isShowing
        overlayToggleBtn?.apply {
            if (state.isActivelyBound) {
                isEnabled = false
                text = loc.getString(R.string.settings_overlay_exclusive)
                background = makeBtnDrawable("#1E293B", "#334155")
                setTextColor(Color.parseColor("#94A3B8"))
            } else {
                isEnabled = true
                setTextColor(Color.WHITE)
                text = if (overlayOn) {
                    loc.getString(R.string.settings_overlay_disable)
                } else {
                    loc.getString(R.string.settings_overlay_enable)
                }
                background = makeBtnDrawable(if (overlayOn) "#059669" else "#334155")
            }
        }

        launchGeminiBtn?.text = loc.getString(R.string.settings_launch_gemini)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun toggleOrReloadUsageWebView() {
        val frame = webContainer ?: return
        if (!webExpanded) {
            webExpanded = true
            frame.visibility = View.VISIBLE

            if (embeddedWebView == null) {
                try {
                    val wv = WebView(this).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                val currentUrl = url.orEmpty().lowercase()
                                if (currentUrl.contains("gemini.google.com/usage")) {
                                    extractFromEmbeddedWebView(900L)
                                    extractFromEmbeddedWebView(2600L)
                                }
                            }
                        }
                    }
                    embeddedWebView = wv
                    frame.addView(
                        wv,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            dp(340f)
                        )
                    )
                } catch (_: Throwable) {
                    return
                }
            }
            embeddedWebView?.loadUrl(USAGE_URL)
        } else {
            embeddedWebView?.reload()
            extractFromEmbeddedWebView(1200L)
        }
        refreshAllUi()
    }

    private fun extractFromEmbeddedWebView(delayMs: Long) {
        uiHandler.postDelayed(r@{
            val wv = embeddedWebView ?: return@r
            if (isFinishing || isDestroyed) return@r
            try {
                val activeUrl = wv.url.orEmpty().lowercase()
                if (!activeUrl.contains("gemini.google.com/usage")) return@r
                wv.evaluateJavascript(
                    "(function(){return document.body ? document.body.innerText : '';})()"
                ) { raw ->
                    try {
                        if (!raw.isNullOrBlank() && raw != "null") {
                            val text = raw.trim('"')
                                .replace("\\n", "\n")
                                .replace("\\\"", "\"")
                            val prev = QuotaSyncProvider.ensureLoaded(this@MainActivity).snapshot.sanitized()
                            val snap = QuotaTextParser.parseUiBlock(
                                block = text,
                                prev = prev,
                                source = "UsageWeb",
                                requireUsageStructure = true
                            )
                            if (snap != null) {
                                QuotaSyncProvider.publishQuota(this@MainActivity, snap)
                                refreshAllUi()
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }, delayMs)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bardInstalled = isPkgInstalled("com.google.android.apps.bard")
        gsaInstalled = isPkgInstalled("com.google.android.googlequicksearchbox")

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1120"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(16f)
            val padV = dp(22f)
            setPadding(padH, padV, padH, padV)
        }

        val headerTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
        }
        titleView = headerTitle

        val headerSub = TextView(this).apply {
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, dp(5f), 0, dp(12f))
        }
        pkgView = headerSub

        // 0. Live Single-Line Capsule HUD Preview Card
        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = makeCardDrawable("#131C31", "#263554")
            val p = dp(14f)
            setPadding(p, p, p, p)
        }

        val previewCap = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10f))
        }
        previewCaptionView = previewCap

        val hudPreview = QuotaHudView(
            context = this,
            onRefreshClick = {
                OverlayHudController.triggerHeadlessSync(this@MainActivity)
                pingHookedProcesses()
            },
            onOpenUsageClick = {
                QuotaHudView.openUsageWebpage(this@MainActivity)
            },
            draggableInFrame = false
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        liveCapsulePreview = hudPreview

        previewCard.addView(previewCap)
        previewCard.addView(hudPreview)

        // 1. Localized Language (EN/CN) & Dual Theme Engine (MIUIX vs MD3) Card
        val prefCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardDrawable("#1E293B", "#334155")
            val p = dp(14f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12f)
            }
        }

        val prefTitle = TextView(this).apply {
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10f))
        }
        settingsSectionTitle = prefTitle

        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val btnZh = Button(this).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply {
                marginEnd = dp(5f)
            }
            setOnClickListener {
                QuotaSyncProvider.updatePreferences(this@MainActivity, lang = QuotaHudView.Lang.ZH)
                refreshAllUi()
            }
        }
        langZhBtn = btnZh

        val btnEn = Button(this).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply {
                marginStart = dp(5f)
            }
            setOnClickListener {
                QuotaSyncProvider.updatePreferences(this@MainActivity, lang = QuotaHudView.Lang.EN)
                refreshAllUi()
            }
        }
        langEnBtn = btnEn

        langRow.addView(btnZh)
        langRow.addView(btnEn)

        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8f)
            }
        }

        val btnMiuix = Button(this).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply {
                marginEnd = dp(5f)
            }
            setOnClickListener {
                QuotaSyncProvider.updatePreferences(this@MainActivity, theme = QuotaHudView.ThemeMode.MIUIX)
                refreshAllUi()
            }
        }
        themeMiuixBtn = btnMiuix

        val btnMd3 = Button(this).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply {
                marginStart = dp(5f)
            }
            setOnClickListener {
                QuotaSyncProvider.updatePreferences(this@MainActivity, theme = QuotaHudView.ThemeMode.MD3)
                refreshAllUi()
            }
        }
        themeMd3Btn = btnMd3

        themeRow.addView(btnMiuix)
        themeRow.addView(btnMd3)

        prefCard.addView(prefTitle)
        prefCard.addView(langRow)
        prefCard.addView(themeRow)

        // 2. Live Hook & Unified Quota Store Telemetry Card
        val diagCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardDrawable("#1E293B", "#334155")
            val p = dp(14f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12f)
            }
        }

        val statusTv = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(3f).toFloat(), 1.1f)
        }
        hookStatusView = statusTv

        val quotaTv = TextView(this).apply {
            setTextColor(Color.parseColor("#34D399"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(10f), 0, dp(10f))
        }
        quotaPreviewView = quotaTv

        val btnPing = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            background = makeBtnDrawable("#0284C7")
            setOnClickListener {
                liveCapsulePreview?.triggerRefreshSpin()
                OverlayHudController.triggerHeadlessSync(this@MainActivity)
                pingHookedProcesses()
                uiHandler.postDelayed({ refreshAllUi() }, 500L)
            }
        }
        pingBtn = btnPing

        diagCard.addView(statusTv)
        diagCard.addView(quotaTv)
        diagCard.addView(btnPing)

        // 3. Direct gemini.google.com/usage Live Login & Sync Card
        val syncCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardDrawable("#1E293B", "#334155")
            val p = dp(14f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12f)
            }
        }

        val syncDesc = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(3f).toFloat(), 1.1f)
        }
        syncDescView = syncDesc

        val webBtn = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            background = makeBtnDrawable("#2563EB")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10f)
            }
            setOnClickListener { toggleOrReloadUsageWebView() }
        }
        syncWebBtn = webBtn

        val webHolder = FrameLayout(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10f)
            }
        }
        webContainer = webHolder

        syncCard.addView(syncDesc)
        syncCard.addView(webBtn)
        syncCard.addView(webHolder)

        // 4. Single-HUD Exclusive System Overlay Button
        val overlayBtn = Button(this).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12f)
            }
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            setOnClickListener {
                if (QuotaSyncProvider.ensureLoaded(this@MainActivity).isActivelyBound) {
                    OverlayHudController.enforceSingleHudExclusivity(this@MainActivity)
                    refreshAllUi()
                    return@setOnClickListener
                }
                if (OverlayHudController.isShowing) {
                    OverlayHudController.hide(this@MainActivity)
                    uiHandler.postDelayed({ refreshAllUi() }, 200L)
                } else if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val permIntent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    runCatching { startActivity(permIntent) }
                } else {
                    OverlayHudController.show(this@MainActivity)
                    uiHandler.postDelayed({ refreshAllUi() }, 250L)
                }
            }
        }
        overlayToggleBtn = overlayBtn

        // 5. Launch Gemini Button
        val launchBtn = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            gravity = Gravity.CENTER
            background = makeBtnDrawable("#4F46E5")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10f)
            }
            setPadding(dp(12f), dp(13f), dp(12f), dp(13f))
            setOnClickListener { launchGeminiWithFallback() }
        }
        launchGeminiBtn = launchBtn

        container.addView(headerTitle)
        container.addView(headerSub)
        container.addView(previewCard)
        container.addView(prefCard)
        container.addView(diagCard)
        container.addView(syncCard)
        container.addView(overlayBtn)
        container.addView(launchBtn)
        scroll.addView(container)
        setContentView(scroll)

        HookTelemetryReceiver.setUiListener {
            runOnUiThread { refreshAllUi() }
        }
        stateObserver = QuotaSyncProvider.registerStateObserver(this) {
            runOnUiThread { refreshAllUi() }
        }
        pingHookedProcesses()
        refreshAllUi()
    }

    override fun onResume() {
        super.onResume()
        pingHookedProcesses()
        refreshAllUi()
    }

    override fun onDestroy() {
        HookTelemetryReceiver.setUiListener(null)
        QuotaSyncProvider.unregisterStateObserver(this, stateObserver)
        stateObserver = null
        embeddedWebView?.let { wv ->
            runCatching {
                wv.stopLoading()
                wv.destroy()
            }
        }
        embeddedWebView = null
        super.onDestroy()
    }
}
