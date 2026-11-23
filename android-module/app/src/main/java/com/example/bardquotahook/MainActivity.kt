package com.example.bardquotahook

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

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
    private var engineDescView: TextView? = null
    private var devConsoleTitleView: TextView? = null
    private var devConsoleCountView: TextView? = null
    private var devConsoleClearBtn: Button? = null
    private var devConsoleLogsView: TextView? = null
    private var energyCardTitleView: TextView? = null
    private var energyToggleBtn: Button? = null
    private var energyExportBtn: Button? = null
    private var energyStatsView: TextView? = null
    private var energyExportReportView: TextView? = null
    private var forceScanBtn: Button? = null
    private var launchGeminiBtn: Button? = null

    private var stateObserver: ContentObserver? = null
    private var bardInstalled = false
    private var gsaInstalled = false
    private var showEnergyImpactStats = true
    private var lastExportedBatteryReport: String = ""

    private fun formatLogTime(ms: Long): String {
        if (ms <= 0L) return "--"
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
        } catch (_: Throwable) {
            ms.toString()
        }
    }

    private fun dp(v: Float): Int = QuotaHudView.dp(this, v)

    private fun localizedContext(lang: QuotaHudView.Lang): Context {
        return try {
            val locale = if (lang == QuotaHudView.Lang.ZH) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
            val cfg = Configuration(resources.configuration).apply {
                setLocale(locale)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setLocales(LocaleList(locale))
                }
                setLayoutDirection(locale)
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

    private fun launchGeminiNativeApp() {
        val candidates = listOf(
            packageManager.getLaunchIntentForPackage("com.google.android.apps.bard"),
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(
                    "com.google.android.apps.bard",
                    "com.google.android.apps.bard.shellapp.BardEntryPointActivity"
                )
            },
            packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")
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

        val sessionLabel = state.sessionId.ifBlank { "sess_awaiting" }
        hookStatusView?.text = if (state.isActivelyBound) {
            val secAgo = maxOf(0L, (System.currentTimeMillis() - state.lastHeartbeatMs) / 1000L)
            loc.getString(
                R.string.settings_hook_bound_fmt,
                state.lastBridge.ifBlank { "Xposed" },
                state.lastPkg.ifBlank { "com.google.android.apps.bard" },
                secAgo
            ) + "\nSession: $sessionLabel"
        } else {
            val extraHint = if (zh) {
                "\n• 排查要点：确认 LSPosed 已勾选「Gemini」并强制停止后重开 Gemini；若悬浮窗已显示但此处仍提示等待，请在本模块应用信息中开启「自启动 / 允许关联启动」以放行 QuotaSyncProvider 跨进程通信。"
            } else {
                "\n• Checklist: Check com.google.android.apps.bard in LSPosed scope and force-stop/relaunch Gemini; if HUD shows inside Gemini but this screen stays Waiting, enable Autostart / Associated Startup for this module so QuotaSyncProvider IPC works."
            }
            loc.getString(R.string.settings_hook_waiting) + extraHint
        }

        val label5h = loc.getString(R.string.hud_label_5h)
        val labelWk = if (zh) "每周" else "Weekly"
        quotaPreviewView?.text =
            "$label5h: ${snap.formatFiveHourDisplay()}   |   $labelWk: ${snap.formatWeeklyDisplay(state.lang)}   [${snap.source}]"

        engineDescView?.text = loc.getString(R.string.settings_sync_desc)

        val entries = QuotaSyncProvider.parseDevConsoleLogs(state.devLogsJson)
        val zeroCount = maxOf(state.zeroGuardCount, entries.count { it.eventType == "ZERO_GUARD" })
        val anomalyCount = maxOf(state.linkAnomalyCount, entries.count { it.eventType == "LINK_ANOMALY" })
        val batteryCount = entries.count { it.eventType == "BATTERY_IMPACT" }
        devConsoleTitleView?.text = if (zh) "开发者控制台 (Dev Console)" else "Developer Console"
        devConsoleCountView?.text = if (zh) {
            "归零防护: $zeroCount | 链路异常: $anomalyCount | 功耗: $batteryCount"
        } else {
            "Zero-Guard: $zeroCount | Anomaly: $anomalyCount | Energy: $batteryCount"
        }
        devConsoleClearBtn?.text = if (zh) "清空" else "Clear"
        devConsoleLogsView?.text = if (entries.isEmpty()) {
            if (zh) {
                "暂无「归零防护已触发」、「链路异常」或「能耗监控」事件记录。\n当触发 Protobuf 归零补偿、解析失败、连续 3 次空数据或前台休眠里程碑时，此处将输出诊断详情。"
            } else {
                "No ZERO_GUARD_TRIGGERED, LINK_ANOMALY, or BATTERY_IMPACT events recorded yet.\nZero compensation, parse failures, 3 consecutive empties, and energy milestones appear here."
            }
        } else {
            entries.joinToString("\n\n") { entry ->
                val evtTs = formatLogTime(entry.eventTimeMs)
                if (entry.eventType == "LINK_ANOMALY") {
                    val evtTag = if (zh) "[链路异常 #${entry.triggerCount}]" else "[LINK_ANOMALY #${entry.triggerCount}]"
                    if (zh) {
                        "$evtTs $evtTag [${entry.source}]\n${entry.detailZh}"
                    } else {
                        "$evtTs $evtTag [${entry.source}]\n${entry.detailEn}"
                    }
                } else if (entry.eventType == "FULL_PROTOCOL_SCAN") {
                    val evtTag = if (zh) "[全量协议扫描]" else "[FULL_PROTOCOL_SCAN]"
                    if (zh) {
                        "$evtTs $evtTag [${entry.source}]\n${entry.detailZh}"
                    } else {
                        "$evtTs $evtTag [${entry.source}]\n${entry.detailEn}"
                    }
                } else if (entry.eventType == "BATTERY_IMPACT") {
                    val evtTag = if (zh) "[能耗监控]" else "[BATTERY_IMPACT]"
                    if (zh) {
                        "$evtTs $evtTag [${entry.source}]\n${entry.detailZh}"
                    } else {
                        "$evtTs $evtTag [${entry.source}]\n${entry.detailEn}"
                    }
                } else {
                    val evtTag = if (zh) "[归零防护已触发 #${entry.triggerCount}]" else "[ZERO_GUARD_TRIGGERED #${entry.triggerCount}]"
                    val cachedTs = formatLogTime(entry.cachedAtMs)
                    if (zh) {
                        "$evtTs $evtTag [${entry.source}]\n缓存快照时间戳: $cachedTs\n已恢复的数值: ${entry.restoredQuota}"
                    } else {
                        "$evtTs $evtTag [${entry.source}]\nCached Snapshot Timestamp: $cachedTs\nRestored Values: ${entry.restoredQuota}"
                    }
                }
            }
        }

        val energyObj = try {
            JSONObject(state.energyStatsJson.ifBlank { "{}" })
        } catch (_: Throwable) {
            JSONObject()
        }
        val eRating = energyObj.optString("rating", "Minimal")
        val eScore = energyObj.optDouble("energyScore", 0.0)
        val eFg = energyObj.optLong("fgDispatches", 0L)
        val eBgSkip = energyObj.optLong("bgSuppressed", 0L)
        val eHooks = energyObj.optLong("hookEvents", 0L)
        val eNetReq = energyObj.optLong("networkReplays", 0L)
        val eNetBytes = energyObj.optLong("networkBytes", 0L)
        val eCpuMs = energyObj.optDouble("cpuMs", 0.0)
        val eWallMs = energyObj.optDouble("wallMs", 0.0)
        val eMah = energyObj.optDouble("estimatedMah", 0.0)
        val eBatPct = energyObj.optInt("batteryPct", -1)
        val eBatStr = if (eBatPct >= 0) "$eBatPct%" else "--"
        val ePoolAct = energyObj.optInt("poolActive", 0)
        val ePoolSize = energyObj.optInt("poolSize", 0)

        energyCardTitleView?.text = if (zh) "电池消耗影响 (Battery Impact)" else "Battery Impact & Energy Statistics"
        energyToggleBtn?.apply {
            text = if (zh) {
                if (showEnergyImpactStats) "Energy Impact: 开启" else "Energy Impact: 关闭"
            } else {
                if (showEnergyImpactStats) "Energy Impact: On" else "Energy Impact: Off"
            }
            background = if (showEnergyImpactStats) makeBtnDrawable("#0F766E") else makeBtnDrawable("#1E293B", "#475569")
            setTextColor(if (showEnergyImpactStats) Color.WHITE else Color.parseColor("#94A3B8"))
        }
        energyExportBtn?.text = if (zh) "Export Report (导出报告)" else "Export Report"
        energyStatsView?.apply {
            visibility = if (showEnergyImpactStats) View.VISIBLE else View.GONE
            text = if (zh) {
                "能耗评级: $eRating (Score=$eScore) | 估算耗电: ~${eMah} mAh (电量 $eBatStr)\n" +
                    "后台唤醒: 0 次 (1ns 门控拦截: $eBgSkip 次) | 线程池: ${ePoolAct}/${ePoolSize} 线程\n" +
                    "前台按需派发: $eFg 次 | Hook 事件: $eHooks | CPU/墙钟: ${eCpuMs}ms / ${eWallMs}ms\n" +
                    "网络重放与拦截传输: $eNetReq 次 / $eNetBytes Bytes | 日志条目: $batteryCount"
            } else {
                "Energy Rating: $eRating (Score=$eScore) | Est. Draw: ~${eMah} mAh (Bat $eBatStr)\n" +
                    "BG Wakeups: 0 (1ns Gated: $eBgSkip) | Thread Pool: ${ePoolAct}/${ePoolSize} threads\n" +
                    "FG Dispatches: $eFg | Hook Events: $eHooks | CPU/Wall: ${eCpuMs}ms / ${eWallMs}ms\n" +
                    "Network Replays/Bytes: $eNetReq reqs / $eNetBytes B | Log Entries: $batteryCount"
            }
        }
        energyExportReportView?.visibility =
            if (lastExportedBatteryReport.isNotBlank()) View.VISIBLE else View.GONE

        forceScanBtn?.text = if (zh) "强制全量协议扫描 (TE: trailers + 5B帧头)" else "Force Full-Protocol Scan (TE: trailers + 5B Frame)"
        launchGeminiBtn?.text = loc.getString(R.string.settings_launch_gemini)
    }

    private fun buildBatteryImpactTextReport(state: QuotaSyncProvider.UnifiedState): String {
        val zh = state.lang == QuotaHudView.Lang.ZH
        val energyObj = try {
            JSONObject(state.energyStatsJson.ifBlank { "{}" })
        } catch (_: Throwable) {
            JSONObject()
        }
        val entries = QuotaSyncProvider.parseDevConsoleLogs(state.devLogsJson)
            .filter { it.eventType == "BATTERY_IMPACT" }
        val eRating = energyObj.optString("rating", "Minimal")
        val eScore = energyObj.optDouble("energyScore", 0.0)
        val eFg = energyObj.optLong("fgDispatches", 0L)
        val eBgSkip = energyObj.optLong("bgSuppressed", 0L)
        val eHooks = energyObj.optLong("hookEvents", 0L)
        val eNetReq = energyObj.optLong("networkReplays", 0L)
        val eNetBytes = energyObj.optLong("networkBytes", 0L)
        val eCpuMs = energyObj.optDouble("cpuMs", 0.0)
        val eWallMs = energyObj.optDouble("wallMs", 0.0)
        val eMah = energyObj.optDouble("estimatedMah", 0.0)
        val eBatPct = energyObj.optInt("batteryPct", -1)
        val eBatStr = if (eBatPct >= 0) "$eBatPct%" else "--"
        val nowStr = formatLogTime(System.currentTimeMillis())

        val sb = StringBuilder(1024)
        if (zh) {
            sb.append("=== Gemini-Eye 电池消耗影响报告 (Battery Impact Report) ===\n")
            sb.append("生成时间: ").append(nowStr).append('\n')
            sb.append("当前会话: ").append(state.sessionId.ifBlank { "sess_default" }).append('\n')
            sb.append("Energy Impact 评级: ").append(eRating).append(" (Score=").append(eScore).append(")\n")
            sb.append("后台唤醒: 0 次 (门控拦截: ").append(eBgSkip).append(") | 前台派发: ").append(eFg).append('\n')
            sb.append("Hook 事件: ").append(eHooks).append(" | CPU/墙钟: ").append(eCpuMs).append("ms / ").append(eWallMs).append("ms\n")
            sb.append("网络重放: ").append(eNetReq).append(" 次 / ").append(eNetBytes).append(" Bytes | 估算耗电: ~").append(eMah).append(" mAh (电量 ").append(eBatStr).append(")\n")
            sb.append("\n--- 当前能耗事件日志 (").append(entries.size).append(") ---\n")
        } else {
            sb.append("=== Gemini-Eye Battery Impact Report ===\n")
            sb.append("Generated: ").append(nowStr).append('\n')
            sb.append("Session: ").append(state.sessionId.ifBlank { "sess_default" }).append('\n')
            sb.append("Energy Rating: ").append(eRating).append(" (Score=").append(eScore).append(")\n")
            sb.append("BG Wakeups: 0 (Gated: ").append(eBgSkip).append(") | FG Dispatches: ").append(eFg).append('\n')
            sb.append("Hook Events: ").append(eHooks).append(" | CPU/Wall: ").append(eCpuMs).append("ms / ").append(eWallMs).append("ms\n")
            sb.append("Network Replays: ").append(eNetReq).append(" reqs / ").append(eNetBytes).append(" Bytes | Est. Draw: ~").append(eMah).append(" mAh (Bat ").append(eBatStr).append(")\n")
            sb.append("\n--- Current Energy Usage Log (").append(entries.size).append(") ---\n")
        }
        if (entries.isEmpty()) {
            sb.append(if (zh) "  (暂无已记录的 BATTERY_IMPACT 日志)\n" else "  (No BATTERY_IMPACT entries recorded yet)\n")
        } else {
            for (e in entries) {
                val ts = formatLogTime(e.eventTimeMs)
                val msg = if (zh) e.detailZh else e.detailEn
                sb.append("  ").append(ts).append(" [").append(e.sessionId).append("] ").append(msg).append('\n')
            }
        }
        return sb.toString().trim()
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
                refreshAllUi()
            },
            onOpenUsageClick = {
                launchGeminiNativeApp()
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

        // 2. Zero-Background Dynamic Session Telemetry Card
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
            setPadding(0, dp(10f), 0, dp(6f))
        }
        quotaPreviewView = quotaTv

        val descTv = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setLineSpacing(dp(3f).toFloat(), 1.1f)
            setPadding(0, dp(6f), 0, 0)
        }
        engineDescView = descTv

        diagCard.addView(statusTv)
        diagCard.addView(quotaTv)
        diagCard.addView(descTv)

        // 2.5 On-Device Developer Console Card (Zero-Guard Frequency & Restored Snapshot Telemetry)
        val devCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardDrawable("#0F172A", "#334155")
            val p = dp(14f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12f)
            }
        }

        val devHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val devTitle = TextView(this).apply {
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        devConsoleTitleView = devTitle

        val devCount = TextView(this).apply {
            setTextColor(Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(8f), 0)
        }
        devConsoleCountView = devCount

        val devClear = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = makeBtnDrawable("#1E293B", "#475569")
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            minimumHeight = 0
            minHeight = dp(28f)
            setOnClickListener {
                QuotaSyncProvider.clearDevConsoleLogs(this@MainActivity)
                refreshAllUi()
            }
        }
        devConsoleClearBtn = devClear

        devHeaderRow.addView(devTitle)
        devHeaderRow.addView(devCount)
        devHeaderRow.addView(devClear)

        val devLogsTv = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2f).toFloat(), 1.15f)
            setPadding(0, dp(10f), 0, 0)
        }
        devConsoleLogsView = devLogsTv

        devCard.addView(devHeaderRow)
        devCard.addView(devLogsTv)

        // 2.6 Battery Impact & Energy Statistics Card (Toggle + Export Report)
        val energyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardDrawable("#0F172A", "#134E4A")
            val p = dp(14f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12f)
            }
        }

        val energyHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val energyTitle = TextView(this).apply {
            setTextColor(Color.parseColor("#5EEAD4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        energyCardTitleView = energyTitle

        val energyToggle = Button(this).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            minimumHeight = 0
            minHeight = dp(28f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6f)
            }
            setOnClickListener {
                showEnergyImpactStats = !showEnergyImpactStats
                refreshAllUi()
            }
        }
        energyToggleBtn = energyToggle

        val energyExport = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            background = makeBtnDrawable("#0F766E")
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            minimumHeight = 0
            minHeight = dp(28f)
            setOnClickListener {
                val st = QuotaSyncProvider.ensureLoaded(this@MainActivity)
                val report = buildBatteryImpactTextReport(st)
                lastExportedBatteryReport = report
                energyExportReportView?.text = report
                energyExportReportView?.visibility = View.VISIBLE
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("GeminiEyeBatteryReport", report))
                } catch (_: Throwable) {
                }
                refreshAllUi()
            }
        }
        energyExportBtn = energyExport

        energyHeaderRow.addView(energyTitle)
        energyHeaderRow.addView(energyToggle)
        energyHeaderRow.addView(energyExport)

        val energyStatsTv = TextView(this).apply {
            setTextColor(Color.parseColor("#CCFBF1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2f).toFloat(), 1.15f)
            setPadding(0, dp(10f), 0, 0)
        }
        energyStatsView = energyStatsTv

        val energyReportTv = TextView(this).apply {
            setTextColor(Color.parseColor("#99F6E4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2f).toFloat(), 1.15f)
            background = makeCardDrawable("#042F2E", "#115E59")
            val rp = dp(10f)
            setPadding(rp, rp, rp, rp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10f)
            }
            visibility = View.GONE
        }
        energyExportReportView = energyReportTv

        energyCard.addView(energyHeaderRow)
        energyCard.addView(energyStatsTv)
        energyCard.addView(energyReportTv)

        // 2.8 Force Full-Protocol Diagnostic Scan Button
        val scanBtn = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = makeBtnDrawable("#0891B2")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12f)
            }
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            setOnClickListener {
                val st = QuotaSyncProvider.ensureLoaded(this@MainActivity)
                QuotaSyncProvider.requestFullProtocolScan(this@MainActivity)
                QuotaSyncProvider.recordFullProtocolScanEvent(
                    context = this@MainActivity,
                    sessionId = st.sessionId.ifBlank { "sess_default" },
                    source = "MainActivity-Settings",
                    summary = "Queued",
                    detailZh = "[手动全量协议扫描已下发] 将在 Gemini 前台窗口激活 runForegroundFullProtocolDiagnostic()，打印出站 HTTP/2 TE: trailers 与 gRPC-RPC 5B 帧头并强制重拉",
                    detailEn = "[Manual Full-Protocol Scan Queued] Will invoke runForegroundFullProtocolDiagnostic() in Gemini foreground to dump H2 TE: trailers & gRPC-RPC 5B frames"
                )
                refreshAllUi()
            }
        }
        forceScanBtn = scanBtn

        // 3. Launch Gemini App Button
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
            setOnClickListener { launchGeminiNativeApp() }
        }
        launchGeminiBtn = launchBtn

        container.addView(headerTitle)
        container.addView(headerSub)
        container.addView(previewCard)
        container.addView(prefCard)
        container.addView(diagCard)
        container.addView(devCard)
        container.addView(energyCard)
        container.addView(scanBtn)
        container.addView(launchBtn)
        scroll.addView(container)
        setContentView(scroll)

        HookTelemetryReceiver.setUiListener {
            runOnUiThread { refreshAllUi() }
        }
        stateObserver = QuotaSyncProvider.registerStateObserver(this) {
            runOnUiThread { refreshAllUi() }
        }
        refreshAllUi()
    }

    override fun onResume() {
        super.onResume()
        refreshAllUi()
    }

    override fun onDestroy() {
        HookTelemetryReceiver.setUiListener(null)
        QuotaSyncProvider.unregisterStateObserver(this, stateObserver)
        stateObserver = null
        super.onDestroy()
    }
}
