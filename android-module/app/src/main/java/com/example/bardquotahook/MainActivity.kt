package com.example.bardquotahook

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
    private var webExpanded = false
    private var bardInstalled = false
    private var gsaInstalled = false

    private fun dp(v: Float): Int = QuotaHudView.dp(this, v)

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
        HookTelemetryReceiver.syncStateToHostApps(this, includePing = true)
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
        val ui = HookTelemetryReceiver.loadUiPrefs(this)
        val snap = HookTelemetryReceiver.loadSnapshot(this)
        val zh = ui.lang == QuotaHudView.Lang.ZH

        // Enforce Single-HUD Exclusion immediately if Xposed bound
        if (ui.xposedBound && OverlayHudController.isShowing) {
            OverlayHudController.enforceSingleHudExclusivity(this)
        }

        titleView?.text = if (zh) {
            "Gemini-Eye 用量胶囊 (v1.9.0)"
        } else {
            "Gemini-Eye Quota HUD (v1.9.0)"
        }

        pkgView?.text = if (zh) {
            "宿主检测 · Gemini: ${if (bardInstalled) "已安装" else "未安装"} | Google: ${if (gsaInstalled) "已安装" else "未安装"} | API 102 + 82"
        } else {
            "Targets · Gemini: ${if (bardInstalled) "Installed" else "Missing"} | Google: ${if (gsaInstalled) "Installed" else "Missing"} | API 102 + 82"
        }

        previewCaptionView?.text = if (zh) {
            "实时单行胶囊预览（与 Gemini 应用内悬浮窗共享同一份 SharedPreferences 实时数值）"
        } else {
            "Live Single-Line Capsule Preview (Synced via SharedPreferences with In-App HUD)"
        }

        liveCapsulePreview?.applyPreferences(ui.lang, ui.theme)
        liveCapsulePreview?.updateSnapshot(snap)

        settingsSectionTitle?.text = if (zh) {
            "界面语言 (i18n) 与双主题引擎 (MIUIX / MD3)"
        } else {
            "Language (i18n) & Dual Theme Engine (MIUIX / MD3)"
        }

        langZhBtn?.apply {
            text = "中文 (5h / 每周)"
            background = if (zh) makeBtnDrawable("#2563EB") else makeBtnDrawable("#0F172A", "#334155")
            setTextColor(if (zh) Color.WHITE else Color.parseColor("#94A3B8"))
        }
        langEnBtn?.apply {
            text = "English (5h / Weekly)"
            background = if (!zh) makeBtnDrawable("#2563EB") else makeBtnDrawable("#0F172A", "#334155")
            setTextColor(if (!zh) Color.WHITE else Color.parseColor("#94A3B8"))
        }

        val isMiuix = ui.theme == QuotaHudView.ThemeMode.MIUIX
        themeMiuixBtn?.apply {
            text = if (zh) "MIUIX 毛玻璃胶囊" else "MIUIX Frosted Pill"
            background = if (isMiuix) makeBtnDrawable("#0284C7") else makeBtnDrawable("#0F172A", "#334155")
            setTextColor(if (isMiuix) Color.WHITE else Color.parseColor("#94A3B8"))
        }
        themeMd3Btn?.apply {
            text = if (zh) "MD3 动态取色" else "MD3 Material You"
            background = if (!isMiuix) makeBtnDrawable("#7C3AED") else makeBtnDrawable("#0F172A", "#334155")
            setTextColor(if (!isMiuix) Color.WHITE else Color.parseColor("#94A3B8"))
        }

        val hookText = if (ui.xposedBound) {
            val secAgo = maxOf(0L, (System.currentTimeMillis() - ui.lastHeartbeatMs) / 1000L)
            if (zh) {
                "✅ 单 HUD 互斥已激活 · 引擎: ${ui.lastBridge}\n宿主: ${ui.lastPkg} (${secAgo}s 前心跳) · 系统全局悬浮窗已自动禁用"
            } else {
                "✅ Single-HUD Bound · Engine: ${ui.lastBridge}\nHost: ${ui.lastPkg} (${secAgo}s ago) · System Overlay Auto-Disabled"
            }
        } else {
            if (zh) {
                "⚠️ 暂未收到 Gemini 进程 Xposed 心跳：\n• 请在 LSPosed / Vector 中启用本模块并勾选 Gemini 与 Google，强行停止后重新打开。\n• 若使用 Shamiko / DenyList 隐藏了 Google，可临时使用下方系统悬浮窗保底。"
            } else {
                "⚠️ Waiting for Gemini Xposed heartbeat:\n• Enable Gemini-Eye in LSPosed / Vector for Gemini & Google, then force-stop Gemini.\n• If Magisk DenyList blocks Xposed, you may use the fallback System Overlay below."
            }
        }
        hookStatusView?.text = hookText

        val wkLabel = if (zh) "每周" else "Weekly"
        quotaPreviewView?.text =
            "5h: ${snap.formatFiveHourDisplay()}   |   $wkLabel: ${snap.formatWeeklyDisplay()}   [${snap.source}]"

        pingBtn?.text = if (zh) {
            "立即刷新额度并同步至宿主 HUD"
        } else {
            "Sync Quota & Push to Host HUD Now"
        }

        syncDescView?.text = if (zh) {
            "内置 gemini.google.com/usage 实时同步：登录一次 Google 账号后，点击胶囊右侧「↻ 刷新图标」即可在后台静默拉取最新 5h 百分比、每周百分比及各自重置倒计时。"
        } else {
            "Built-in gemini.google.com/usage Sync: Sign in once below so tapping the HUD's Refresh icon (↻) silently fetches live 5h %, Weekly %, and reset countdowns in the background."
        }

        syncWebBtn?.text = if (webExpanded) {
            if (zh) "重新加载 gemini.google.com/usage 并提取最新数值" else "Reload gemini.google.com/usage & Extract Quota"
        } else {
            if (zh) "展开内置用量网页登录同步 (gemini.google.com/usage)" else "Open Embedded Login & Sync (gemini.google.com/usage)"
        }

        val overlayOn = OverlayHudController.isShowing
        overlayToggleBtn?.apply {
            if (ui.xposedBound) {
                isEnabled = false
                text = if (zh) {
                    "单 HUD 独占模式生效中 (已由 Xposed 应用内胶囊接管)"
                } else {
                    "Single-HUD Exclusive Active (Managed by In-App Xposed HUD)"
                }
                background = makeBtnDrawable("#1E293B", "#334155")
                setTextColor(Color.parseColor("#94A3B8"))
            } else {
                isEnabled = true
                setTextColor(Color.WHITE)
                text = if (overlayOn) {
                    if (zh) "关闭系统全局悬浮胶囊 (运行中)" else "Disable System Overlay HUD (Running)"
                } else {
                    if (zh) "开启免 Hook 系统悬浮胶囊 (仅未注入时可用)" else "Enable Fallback System Overlay (When Unhooked)"
                }
                background = makeBtnDrawable(if (overlayOn) "#059669" else "#334155")
            }
        }

        launchGeminiBtn?.text = if (zh) "启动 Gemini 应用" else "Launch Gemini App"
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
                                extractFromEmbeddedWebView(900L)
                                extractFromEmbeddedWebView(2600L)
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
                wv.evaluateJavascript(
                    "(function(){return document.body ? document.body.innerText : '';})()"
                ) { raw ->
                    try {
                        if (!raw.isNullOrBlank() && raw != "null") {
                            val text = raw.trim('"')
                                .replace("\\n", "\n")
                                .replace("\\\"", "\"")
                            val prev = HookTelemetryReceiver.loadSnapshot(this@MainActivity)
                            val snap = QuotaTextParser.parseUiBlock(text, prev, "UsageWeb")
                            if (snap != null) {
                                HookTelemetryReceiver.pushExternalQuotaToHostApps(this@MainActivity, snap)
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

        // 1. Dual Language (i18n) & Dual Theme Engine (MIUIX vs MD3) Card
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
                HookTelemetryReceiver.saveUiPrefs(this@MainActivity, lang = QuotaHudView.Lang.ZH)
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
                HookTelemetryReceiver.saveUiPrefs(this@MainActivity, lang = QuotaHudView.Lang.EN)
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
                HookTelemetryReceiver.saveUiPrefs(this@MainActivity, theme = QuotaHudView.ThemeMode.MIUIX)
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
                HookTelemetryReceiver.saveUiPrefs(this@MainActivity, theme = QuotaHudView.ThemeMode.MD3)
                refreshAllUi()
            }
        }
        themeMd3Btn = btnMd3

        themeRow.addView(btnMiuix)
        themeRow.addView(btnMd3)

        prefCard.addView(prefTitle)
        prefCard.addView(langRow)
        prefCard.addView(themeRow)

        // 2. Live Hook & Shared Quota Telemetry Card
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
                if (HookTelemetryReceiver.isXposedActivelyBound(this@MainActivity)) {
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
