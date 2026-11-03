package com.example.bardquotahook

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
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

    private var hookStatusView: TextView? = null
    private var quotaPreviewView: TextView? = null
    private var overlayToggleBtn: Button? = null
    private var syncWebBtn: Button? = null
    private var webContainer: FrameLayout? = null
    private var embeddedWebView: WebView? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var webExpanded = false

    private fun dp(v: Float): Int = QuotaHudLayout.dp(this, v)

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
        val targets = arrayOf(
            "com.google.android.apps.bard",
            "com.google.android.googlequicksearchbox"
        )
        for (pkg in targets) {
            try {
                val ping = Intent(QuotaTextParser.ACTION_QUOTA_EXTERNAL_SYNC).apply {
                    setPackage(pkg)
                    putExtra("ping", true)
                }
                sendBroadcast(ping)
            } catch (_: Throwable) {
            }
        }
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
            cornerRadius = dp(14f).toFloat()
            setStroke(maxOf(1, dp(1f)), Color.parseColor(borderHex))
        }
    }

    private fun makeBtnDrawable(bgHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = dp(12f).toFloat()
        }
    }

    private fun refreshStatusTexts() {
        val prefs = getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
        val lastHb = prefs.getLong("last_hb_ms", 0L)
        val lastPkg = prefs.getString("last_pkg", "") ?: ""
        val lastBridge = prefs.getString("last_bridge", "") ?: ""
        val snap = HookTelemetryReceiver.loadSnapshot(this)

        val hookLine = if (lastHb > 0L) {
            val secAgo = maxOf(0L, (System.currentTimeMillis() - lastHb) / 1000L)
            "✅ Xposed 已连接 · 引擎: $lastBridge\n宿主: $lastPkg (${secAgo}s 前活跃)"
        } else {
            "⚠️ 尚未收到宿主 Hook 回执：\n" +
                "• Vector / LSPosed API 102 用户：本版已补齐 META-INF/xposed/* 原生入口，请重启或强行停止 Google 与 Gemini。\n" +
                "• Magisk / Shamiko 用户：若在「排除列表 (DenyList)」中勾选了 Google 或 Gemini，Zygisk 会剥离所有模块导致无法 Hook！请将它们移出排除列表，或直接开启下方「免 Hook 全局悬浮胶囊」。"
        }
        hookStatusView?.text = hookLine

        quotaPreviewView?.text =
            "当前缓存额度 · 5h: ${snap.fiveHourQuota}  |  周: ${snap.weeklyLimit}  |  重置: ${snap.resetTimer}  (来源: ${snap.source})"

        val overlayOn = OverlayHudController.isShowing
        overlayToggleBtn?.text = if (overlayOn) {
            "关闭全局悬浮胶囊 (当前运行中 · 自动同步)"
        } else {
            "开启免 Hook 全局悬浮胶囊 (过 DenyList 保底方案)"
        }
        overlayToggleBtn?.background = makeBtnDrawable(if (overlayOn) "#059669" else "#334155")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun toggleOrReloadUsageWebView() {
        val frame = webContainer ?: return
        if (!webExpanded) {
            webExpanded = true
            frame.visibility = View.VISIBLE
            syncWebBtn?.text = "刷新 gemini.google.com/usage 并提取最新额度"

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
    }

    private fun extractFromEmbeddedWebView(delayMs: Long) {
        uiHandler.postDelayed({
            val wv = embeddedWebView ?: return@postDelayed
            if (isFinishing || isDestroyed) return@postDelayed
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
                                refreshStatusTexts()
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

        val bardInstalled = isPkgInstalled("com.google.android.apps.bard")
        val gsaInstalled = isPkgInstalled("com.google.android.googlequicksearchbox")

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(18f)
            val padV = dp(24f)
            setPadding(padH, padV, padH, padV)
        }

        val titleView = TextView(this).apply {
            text = "Gemini-Eye 用量监控 (v1.8.0 · 双引擎)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val pkgView = TextView(this).apply {
            text = "包检测 · Gemini: ${if (bardInstalled) "已安装" else "未安装"} | Google 主程序: ${if (gsaInstalled) "已安装" else "未检测到"} | API 102 + API 82"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6f), 0, dp(14f))
        }

        // 1. Live Hook Diagnostics Card
        val diagCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardDrawable("#1E293B", "#334155")
            val p = dp(14f)
            setPadding(p, p, p, p)
        }

        val statusTv = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
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

        val pingBtn = Button(this).apply {
            text = "主动检测 Hook 连接状态 (Ping 宿主)"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            background = makeBtnDrawable("#0284C7")
            setOnClickListener {
                pingHookedProcesses()
                uiHandler.postDelayed({ refreshStatusTexts() }, 500L)
            }
        }

        diagCard.addView(statusTv)
        diagCard.addView(quotaTv)
        diagCard.addView(pingBtn)

        // 2. Direct gemini.google.com/usage Live Sync Card
        val syncCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardDrawable("#1E293B", "#334155")
            val p = dp(14f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14f)
            }
        }

        val syncDesc = TextView(this).apply {
            text = "为什么聊天界面默认显示 -- ？\nGoogle 聊天流采用二进制 Protobuf 且不附带额度字段；真实百分比仅在 gemini.google.com/usage 提供。点击下方按钮登录一次 Google 账号，即可直接抓取实时 5h % / 周限额 % 并自动推送至 Gemini 悬浮胶囊！"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(dp(3f).toFloat(), 1.1f)
        }

        val webBtn = Button(this).apply {
            text = "展开内置网页登录并同步 gemini.google.com/usage"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
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

        // 3. System Overlay Fallback Button (Works even without Xposed / under DenyList)
        val overlayBtn = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14f)
            }
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            setOnClickListener {
                if (OverlayHudController.isShowing) {
                    OverlayHudController.hide(this@MainActivity)
                    uiHandler.postDelayed({ refreshStatusTexts() }, 200L)
                } else if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val permIntent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    runCatching { startActivity(permIntent) }
                } else {
                    OverlayHudController.show(this@MainActivity)
                    uiHandler.postDelayed({ refreshStatusTexts() }, 250L)
                }
            }
        }
        overlayToggleBtn = overlayBtn

        // 4. Launch Gemini Button
        val launchBtn = Button(this).apply {
            text = "启动 Gemini / Launch Gemini"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            background = makeBtnDrawable("#4F46E5")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12f)
            }
            setPadding(dp(12f), dp(13f), dp(12f), dp(13f))
            setOnClickListener { launchGeminiWithFallback() }
        }

        container.addView(titleView)
        container.addView(pkgView)
        container.addView(diagCard)
        container.addView(syncCard)
        container.addView(overlayBtn)
        container.addView(launchBtn)
        scroll.addView(container)
        setContentView(scroll)

        HookTelemetryReceiver.setUiListener {
            runOnUiThread { refreshStatusTexts() }
        }
        pingHookedProcesses()
        refreshStatusTexts()
    }

    override fun onResume() {
        super.onResume()
        pingHookedProcesses()
        refreshStatusTexts()
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
