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
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
    private var hudOpacityLabelView: TextView? = null
    private var hudOpacitySeekBar: SeekBar? = null
    private var isUpdatingOpacitySeekBar = false

    private var hookStatusView: TextView? = null
    private var quotaPreviewView: TextView? = null
    private var engineDescView: TextView? = null
    private var devConsoleTitleView: TextView? = null
    private var devConsoleCountView: TextView? = null
    private var devConsoleResetTrendBtn: Button? = null
    private var devConsoleClearBtn: Button? = null
    private var devConsoleTrendRangeRow: LinearLayout? = null
    private var devConsoleTrendRangeLabel: TextView? = null
    private var devConsoleTrendRangeSpinner: Spinner? = null
    private var isUpdatingTrendRangeSpinner = false
    private var selectedTrendRangeIdx = 4
    private var devConsoleLogsView: TextView? = null
    private var energyCardTitleView: TextView? = null
    private var energyToggleBtn: Button? = null
    private var energyExportBtn: Button? = null
    private var energyCsvExportBtn: Button? = null
    private var energyThresholdLabelView: TextView? = null
    private var energyThresholdSeekBar: SeekBar? = null
    private var isUpdatingThresholdSeekBar = false
    private var energyTrendThresholdLabelView: TextView? = null
    private var energyTrendThresholdSeekBar: SeekBar? = null
    private var isUpdatingTrendThresholdSeekBar = false
    private var energyTrendAutoDismissLabelView: TextView? = null
    private var energyTrendAutoDismissToggleBtn: Button? = null
    private var energyTrendAutoDismissSeekBar: SeekBar? = null
    private var isUpdatingTrendAutoDismissSeekBar = false
    private var energyStatsView: TextView? = null
    private var energyExportReportView: TextView? = null
    private var forceScanBtn: Button? = null
    private var launchGeminiBtn: Button? = null

    private var stateObserver: ContentObserver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val trendAutoDismissRefreshRunnable = Runnable { refreshAllUi() }
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

        val energyObj = try {
            JSONObject(state.energyStatsJson.ifBlank { "{}" })
        } catch (_: Throwable) {
            JSONObject()
        }
        val eScore = energyObj.optDouble("energyScore", 0.0)

        liveCapsulePreview?.applyPreferences(
            state.lang,
            state.theme,
            state.hudOpacity,
            state.highBatteryThreshold.toDouble()
        )
        liveCapsulePreview?.updateSnapshot(snap)
        liveCapsulePreview?.updateEnergyScore(eScore)

        val opacityPct = state.hudOpacity.coerceIn(20, 100)
        val blurPx = (4 + ((opacityPct - 20) / 80f) * 28f).toInt()
        hudOpacityLabelView?.text = if (zh) {
            "HUD 不透明度 & MIUIX 模糊强度: ${opacityPct}% (Blur ${blurPx}px)"
        } else {
            "HUD Opacity & MIUIX Blur Intensity: ${opacityPct}% (Blur ${blurPx}px)"
        }
        if (hudOpacitySeekBar != null && hudOpacitySeekBar?.progress != (opacityPct - 20)) {
            isUpdatingOpacitySeekBar = true
            hudOpacitySeekBar?.progress = opacityPct - 20
            isUpdatingOpacitySeekBar = false
        }

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
        val hasHighUsageTrendRaw = energyObj.optBoolean("highUsageTrendActive", false)
        val lastTrendAlertAtMs = energyObj.optLong("lastTrendAlertAtMs", 0L)
        val autoDismissEnabled = state.trendAutoDismissEnabled
        val autoDismissSec = state.trendAutoDismissSec.coerceIn(5, 60)
        val remainingAutoDismissSec: Int? = if (hasHighUsageTrendRaw && autoDismissEnabled && lastTrendAlertAtMs > 0L) {
            val remainingMs = (lastTrendAlertAtMs + autoDismissSec * 1000L) - System.currentTimeMillis()
            if (remainingMs <= 0L) 0 else ((remainingMs + 999L) / 1000L).toInt()
        } else {
            null
        }
        val hasHighUsageTrend = hasHighUsageTrendRaw && (remainingAutoDismissSec == null || remainingAutoDismissSec > 0)
        mainHandler.removeCallbacks(trendAutoDismissRefreshRunnable)
        if (hasHighUsageTrend && remainingAutoDismissSec != null && remainingAutoDismissSec > 0) {
            mainHandler.postDelayed(trendAutoDismissRefreshRunnable, 1000L)
        }
        val trendStreak = energyObj.optInt("trendConsecutiveIncreases", 0)
        val trendStart = energyObj.optDouble("trendStartScore", 0.0)
        val trendCur = energyObj.optDouble("trendCurrentScore", 0.0)
        val trendDelta = energyObj.optDouble("trendWindowDelta", 0.0)
        val trendHistoryArr = energyObj.optJSONArray("trendAlertHistory")

        devConsoleTitleView?.text = if (zh) "开发者控制台 (Dev Console)" else "Developer Console"
        devConsoleCountView?.text = if (zh) {
            "归零防护: $zeroCount | 链路异常: $anomalyCount | 功耗: $batteryCount"
        } else {
            "Zero-Guard: $zeroCount | Anomaly: $anomalyCount | Energy: $batteryCount"
        }
        devConsoleClearBtn?.text = if (zh) "清空" else "Clear"
        val hasTrendHistory = trendHistoryArr != null && trendHistoryArr.length() > 0
        devConsoleResetTrendBtn?.apply {
            text = if (zh) "重置告警历史" else "Reset Alert History"
            visibility = if (hasHighUsageTrend || trendStreak > 0 || hasTrendHistory) View.VISIBLE else View.GONE
        }
        devConsoleTrendRangeRow?.visibility = if (hasTrendHistory) View.VISIBLE else View.GONE
        devConsoleTrendRangeLabel?.text = if (zh) "趋势触发时间范围:" else "Trend Occurrence Range:"
        devConsoleTrendRangeSpinner?.let { sp ->
            val items = if (zh) {
                listOf("最近 15 分钟", "最近 1 小时", "最近 6 小时", "最近 24 小时", "全部时间")
            } else {
                listOf("Last 15m", "Last 1h", "Last 6h", "Last 24h", "All Time")
            }
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            isUpdatingTrendRangeSpinner = true
            sp.adapter = adapter
            sp.setSelection(selectedTrendRangeIdx.coerceIn(0, 4), false)
            isUpdatingTrendRangeSpinner = false
        }
        devConsoleLogsView?.text = buildString {
            if (hasHighUsageTrend) {
                val countdownHint = if (remainingAutoDismissSec != null) {
                    if (zh) " · ${remainingAutoDismissSec}s 后自动消失" else " · Auto-dismiss in ${remainingAutoDismissSec}s"
                } else {
                    ""
                }
                if (zh) {
                    append("⚠ High Usage Trend · 5分钟滑动窗口连续上升 $trendStreak 次 ($trendStart -> $trendCur, +$trendDelta 分)$countdownHint\n")
                } else {
                    append("⚠ High Usage Trend · $trendStreak consecutive increases in 5m window ($trendStart -> $trendCur, +$trendDelta pts)$countdownHint\n")
                }
            }
            if (hasTrendHistory && trendHistoryArr != null) {
                val nowMs = System.currentTimeMillis()
                val windowMs = when (selectedTrendRangeIdx) {
                    0 -> 15 * 60 * 1000L
                    1 -> 60 * 60 * 1000L
                    2 -> 6 * 60 * 60 * 1000L
                    3 -> 24 * 60 * 60 * 1000L
                    else -> Long.MAX_VALUE
                }
                val filteredItems = ArrayList<JSONObject>()
                for (idx in 0 until trendHistoryArr.length()) {
                    val item = trendHistoryArr.optJSONObject(idx) ?: continue
                    val tsMs = item.optLong("ts", 0L)
                    if (windowMs == Long.MAX_VALUE || (tsMs > 0L && (nowMs - tsMs) <= windowMs)) {
                        filteredItems.add(item)
                    }
                }
                val totalCount = trendHistoryArr.length()
                append(
                    if (zh) {
                        "  近期触发历史 (${filteredItems.size}/$totalCount 条):\n"
                    } else {
                        "  Recent Alert Occurrences (${filteredItems.size}/$totalCount):\n"
                    }
                )
                if (filteredItems.isEmpty()) {
                    append(if (zh) "   (所选时间范围内无记录)\n" else "   (No occurrences in selected time range)\n")
                } else {
                    val limit = minOf(filteredItems.size, 8)
                    for (idx in 0 until limit) {
                        val item = filteredItems[idx]
                        val ts = formatLogTime(item.optLong("ts", 0L))
                        val st = item.optInt("streak", 0)
                        val th = item.optInt("threshold", state.trendConsecutiveThreshold)
                        val s0 = item.optDouble("startScore", 0.0)
                        val s1 = item.optDouble("curScore", 0.0)
                        val d = item.optDouble("delta", 0.0)
                        val r = item.optString("reason", "SAMPLE")
                        append("   • $ts · ${st}x>=$th · $s0->$s1 (+$d) [$r]\n")
                    }
                }
                append('\n')
            } else if (hasHighUsageTrend) {
                append('\n')
            }
            if (entries.isEmpty()) {
                if (zh) {
                    append("暂无「归零防护已触发」、「链路异常」或「能耗监控」事件记录。\n当触发 Protobuf 归零补偿、解析失败、连续 3 次空数据或前台休眠里程碑时，此处将输出诊断详情。")
                } else {
                    append("No ZERO_GUARD_TRIGGERED, LINK_ANOMALY, or BATTERY_IMPACT events recorded yet.\nZero compensation, parse failures, 3 consecutive empties, and energy milestones appear here.")
                }
            } else {
                append(entries.joinToString("\n\n") { entry ->
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
                })
            }
        }

        val eRating = energyObj.optString("rating", "Minimal")
        val eMaScore = energyObj.optDouble("smoothedEnergyScore", eScore)
        val eMaWindow = energyObj.optInt("maWindowSize", 4)
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
        val eSurgeDelta = energyObj.optDouble("lastSurgeDelta", 0.0)
        val surgeLine = if (eSurgeDelta > 15.0) {
            if (zh) {
                "\n⚠ 能耗突增告警: 单心跳周期上升 +$eSurgeDelta 分 (>15)"
            } else {
                "\n⚠ Energy Surge WARN: +$eSurgeDelta pts in 1 Heartbeat Cycle (>15)"
            }
        } else {
            ""
        }
        val trendLine = if (hasHighUsageTrend) {
            if (zh) {
                "\n⚠ High Usage Trend: 5分钟窗口连续上升 $trendStreak 次 (+$trendDelta)"
            } else {
                "\n⚠ High Usage Trend: $trendStreak consecutive increases in 5m window (+$trendDelta)"
            }
        } else {
            ""
        }

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
        val timelineCount = energyObj.optInt("timelineCount", 0)
        energyCsvExportBtn?.text = if (zh) {
            "Historical Impact CSV (导出 CSV${if (timelineCount > 0) " · $timelineCount" else ""})"
        } else {
            "Historical Impact CSV${if (timelineCount > 0) " ($timelineCount)" else ""}"
        }
        val activeThreshold = state.highBatteryThreshold.coerceIn(5, 60)
        val isAboveThreshold = eScore >= activeThreshold.toDouble()
        energyThresholdLabelView?.text = if (zh) {
            "高能耗告警阈值 (High Battery Impact): >= $activeThreshold 分 (${if (isAboveThreshold) "已触发告警" else "监控中"})"
        } else {
            "High Battery Impact Threshold: >= $activeThreshold pts (${if (isAboveThreshold) "ALERT ACTIVE" else "Monitoring"})"
        }
        if (energyThresholdSeekBar != null && energyThresholdSeekBar?.progress != (activeThreshold - 5)) {
            isUpdatingThresholdSeekBar = true
            energyThresholdSeekBar?.progress = activeThreshold - 5
            isUpdatingThresholdSeekBar = false
        }
        val activeTrendThreshold = state.trendConsecutiveThreshold.coerceIn(2, 10)
        energyTrendThresholdLabelView?.text = if (zh) {
            "High Usage Trend 灵敏度 (连续上升次数): >= ${activeTrendThreshold}x / 5m (${if (hasHighUsageTrend) "已触发 $trendStreak/$activeTrendThreshold" else "监控中"})"
        } else {
            "High Usage Trend Sensitivity: >= ${activeTrendThreshold}x / 5m (${if (hasHighUsageTrend) "ALERT $trendStreak/$activeTrendThreshold" else "Monitoring"})"
        }
        if (energyTrendThresholdSeekBar != null && energyTrendThresholdSeekBar?.progress != (activeTrendThreshold - 2)) {
            isUpdatingTrendThresholdSeekBar = true
            energyTrendThresholdSeekBar?.progress = activeTrendThreshold - 2
            isUpdatingTrendThresholdSeekBar = false
        }
        energyTrendAutoDismissLabelView?.text = if (zh) {
            if (autoDismissEnabled) "Trend 通知自动消失计时: ${autoDismissSec}s (5-60s)" else "Trend 通知自动消失计时: 已关闭"
        } else {
            if (autoDismissEnabled) "Trend Alert Auto-Dismiss Timer: ${autoDismissSec}s (5-60s)" else "Trend Alert Auto-Dismiss Timer: OFF"
        }
        energyTrendAutoDismissToggleBtn?.apply {
            text = if (autoDismissEnabled) "AUTO" else "OFF"
            background = if (autoDismissEnabled) makeBtnDrawable("#B45309") else makeBtnDrawable("#1E293B", "#475569")
            setTextColor(if (autoDismissEnabled) Color.WHITE else Color.parseColor("#94A3B8"))
        }
        energyTrendAutoDismissSeekBar?.apply {
            isEnabled = autoDismissEnabled
            alpha = if (autoDismissEnabled) 1.0f else 0.45f
            if (progress != (autoDismissSec - 5)) {
                isUpdatingTrendAutoDismissSeekBar = true
                progress = autoDismissSec - 5
                isUpdatingTrendAutoDismissSeekBar = false
            }
        }
        energyStatsView?.apply {
            visibility = if (showEnergyImpactStats) View.VISIBLE else View.GONE
            text = if (zh) {
                "能耗评级: $eRating (MA($eMaWindow)=$eMaScore | Raw=$eScore) | 估算耗电: ~${eMah} mAh (电量 $eBatStr)\n" +
                    "后台唤醒: 0 次 (1ns 门控拦截: $eBgSkip 次) | 线程池: ${ePoolAct}/${ePoolSize} 线程\n" +
                    "前台按需派发: $eFg 次 | Hook 事件: $eHooks | CPU/墙钟: ${eCpuMs}ms / ${eWallMs}ms\n" +
                    "网络重放与拦截传输: $eNetReq 次 / $eNetBytes Bytes | 日志条目: $batteryCount$surgeLine$trendLine"
            } else {
                "Energy Rating: $eRating (MA($eMaWindow)=$eMaScore | Raw=$eScore) | Est. Draw: ~${eMah} mAh (Bat $eBatStr)\n" +
                    "BG Wakeups: 0 (1ns Gated: $eBgSkip) | Thread Pool: ${ePoolAct}/${ePoolSize} threads\n" +
                    "FG Dispatches: $eFg | Hook Events: $eHooks | CPU/Wall: ${eCpuMs}ms / ${eWallMs}ms\n" +
                    "Network Replays/Bytes: $eNetReq reqs / $eNetBytes B | Log Entries: $batteryCount$surgeLine$trendLine"
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

    private fun csvEscape(value: Any): String {
        val s = value.toString()
        return if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
    }

    private fun formatIsoUtc(ms: Long): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.format(Date(ms))
        } catch (_: Throwable) {
            ms.toString()
        }
    }

    private fun formatHms(ms: Long): String {
        return try {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
        } catch (_: Throwable) {
            ms.toString()
        }
    }

    private fun buildHistoricalImpactCsv(state: QuotaSyncProvider.UnifiedState): String {
        val energyObj = try {
            JSONObject(state.energyStatsJson.ifBlank { "{}" })
        } catch (_: Throwable) {
            JSONObject()
        }
        val maWindowSize = energyObj.optInt("maWindowSize", 4)
        val timelineArr = energyObj.optJSONArray("timelineSamples")
        val sb = StringBuilder(512)
        sb.append("index,timestamp_iso,time_hms,session_id,event,ma_window_size,filtered_ma_score,raw_score,score_delta,spike_bypass,above_high_threshold,cpu_ms,wall_ms,fg_dispatches,net_reqs,estimated_mah\n")
        if (timelineArr != null && timelineArr.length() > 0) {
            for (i in 0 until timelineArr.length()) {
                val item = timelineArr.optJSONObject(i) ?: continue
                val ts = item.optLong("ts", System.currentTimeMillis())
                sb.append(item.optInt("i", i + 1)).append(',')
                    .append(csvEscape(formatIsoUtc(ts))).append(',')
                    .append(csvEscape(formatHms(ts))).append(',')
                    .append(csvEscape(item.optString("sess", state.sessionId.ifBlank { "sess_default" }))).append(',')
                    .append(csvEscape(item.optString("evt", "SAMPLE"))).append(',')
                    .append(maWindowSize).append(',')
                    .append(item.optDouble("ma", 0.0)).append(',')
                    .append(item.optDouble("raw", 0.0)).append(',')
                    .append(item.optDouble("d", 0.0)).append(',')
                    .append(item.optBoolean("sb", false)).append(',')
                    .append(item.optBoolean("ht", false)).append(',')
                    .append(item.optDouble("cpu", 0.0)).append(',')
                    .append(item.optDouble("wall", 0.0)).append(',')
                    .append(item.optLong("fg", 0L)).append(',')
                    .append(item.optLong("net", 0L)).append(',')
                    .append(item.optDouble("mah", 0.0)).append('\n')
            }
        } else {
            val nowMs = energyObj.optLong("updatedAtMs", System.currentTimeMillis()).let {
                if (it > 0L) it else System.currentTimeMillis()
            }
            val rawScore = energyObj.optDouble("energyScore", 0.0)
            val maScore = energyObj.optDouble("smoothedEnergyScore", rawScore)
            sb.append(1).append(',')
                .append(csvEscape(formatIsoUtc(nowMs))).append(',')
                .append(csvEscape(formatHms(nowMs))).append(',')
                .append(csvEscape(state.sessionId.ifBlank { "sess_default" })).append(',')
                .append("SNAPSHOT_BASELINE").append(',')
                .append(maWindowSize).append(',')
                .append(maScore).append(',')
                .append(rawScore).append(',')
                .append(0.0).append(',')
                .append(energyObj.optBoolean("spikeBypassActive", false)).append(',')
                .append(rawScore >= energyObj.optInt("highBatteryThreshold", state.highBatteryThreshold).toDouble()).append(',')
                .append(energyObj.optDouble("cpuMs", 0.0)).append(',')
                .append(energyObj.optDouble("wallMs", 0.0)).append(',')
                .append(energyObj.optLong("fgDispatches", 0L)).append(',')
                .append(energyObj.optLong("networkReplays", 0L)).append(',')
                .append(energyObj.optDouble("estimatedMah", 0.0)).append('\n')
        }
        return sb.toString().trimEnd('\n')
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

        val opacityLabelTv = TextView(this).apply {
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10f), 0, dp(4f))
        }
        hudOpacityLabelView = opacityLabelTv

        val opacitySlider = SeekBar(this).apply {
            max = 80
            progress = QuotaSyncProvider.DEFAULT_HUD_OPACITY - 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (isUpdatingOpacitySeekBar || !fromUser) return
                    val pct = (progress + 20).coerceIn(20, 100)
                    liveCapsulePreview?.setHudOpacity(pct)
                    QuotaSyncProvider.updatePreferences(this@MainActivity, hudOpacity = pct)
                    refreshAllUi()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        hudOpacitySeekBar = opacitySlider

        prefCard.addView(prefTitle)
        prefCard.addView(langRow)
        prefCard.addView(themeRow)
        prefCard.addView(opacityLabelTv)
        prefCard.addView(opacitySlider)

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

        val devResetTrend = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.parseColor("#FDE68A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            background = makeBtnDrawable("#78350F", "#F59E0B")
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            minimumHeight = 0
            minHeight = dp(28f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6f)
            }
            visibility = View.GONE
            setOnClickListener {
                QuotaSyncProvider.resetTrendAlertHistory(this@MainActivity)
                refreshAllUi()
            }
        }
        devConsoleResetTrendBtn = devResetTrend

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
        devHeaderRow.addView(devResetTrend)
        devHeaderRow.addView(devClear)

        val devRangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, 0)
            visibility = View.GONE
        }
        devConsoleTrendRangeRow = devRangeRow

        val devRangeLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#FDA4AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(8f), 0)
        }
        devConsoleTrendRangeLabel = devRangeLabel

        val devRangeSpinner = Spinner(this).apply {
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    (view as? TextView)?.apply {
                        setTextColor(Color.parseColor("#FECDD3"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    }
                    if (isUpdatingTrendRangeSpinner || selectedTrendRangeIdx == position) return
                    selectedTrendRangeIdx = position
                    refreshAllUi()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        devConsoleTrendRangeSpinner = devRangeSpinner

        devRangeRow.addView(devRangeLabel)
        devRangeRow.addView(devRangeSpinner)

        val devLogsTv = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2f).toFloat(), 1.15f)
            setPadding(0, dp(10f), 0, 0)
        }
        devConsoleLogsView = devLogsTv

        devCard.addView(devHeaderRow)
        devCard.addView(devRangeRow)
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

        val energyCsvExport = Button(this).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            background = makeBtnDrawable("#047857")
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            minimumHeight = 0
            minHeight = dp(28f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8f)
            }
            setOnClickListener {
                val st = QuotaSyncProvider.ensureLoaded(this@MainActivity)
                val csv = buildHistoricalImpactCsv(st)
                val sess = st.sessionId.ifBlank { "sess_default" }
                var savedPath = ""
                try {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    val outFile = File(dir, "gemini-eye-historical-impact-$sess.csv")
                    outFile.writeText("\uFEFF$csv", Charsets.UTF_8)
                    savedPath = outFile.absolutePath
                } catch (_: Throwable) {
                }
                val displayReport = if (savedPath.isNotEmpty()) {
                    "[Historical Impact CSV Saved: $savedPath]\n\n$csv"
                } else {
                    csv
                }
                lastExportedBatteryReport = displayReport
                energyExportReportView?.text = displayReport
                energyExportReportView?.visibility = View.VISIBLE
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("GeminiEyeHistoricalImpactCsv", csv))
                } catch (_: Throwable) {
                }
                refreshAllUi()
            }
        }
        energyCsvExportBtn = energyCsvExport

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

        val energyThresholdLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#FCD34D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10f), 0, dp(4f))
        }
        energyThresholdLabelView = energyThresholdLabel

        val energyThresholdSlider = SeekBar(this).apply {
            max = 55
            progress = QuotaSyncProvider.DEFAULT_HIGH_ENERGY_THRESHOLD - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (isUpdatingThresholdSeekBar || !fromUser) return
                    val th = (progress + 5).coerceIn(5, 60)
                    liveCapsulePreview?.setHighBatteryThreshold(th.toDouble())
                    QuotaSyncProvider.updatePreferences(this@MainActivity, highBatteryThreshold = th)
                    refreshAllUi()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        energyThresholdSeekBar = energyThresholdSlider

        val energyTrendThresholdLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#FDA4AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8f), 0, dp(4f))
        }
        energyTrendThresholdLabelView = energyTrendThresholdLabel

        val energyTrendThresholdSlider = SeekBar(this).apply {
            max = 8
            progress = QuotaSyncProvider.DEFAULT_TREND_CONSECUTIVE_THRESHOLD - 2
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (isUpdatingTrendThresholdSeekBar || !fromUser) return
                    val t = (progress + 2).coerceIn(2, 10)
                    QuotaSyncProvider.updatePreferences(this@MainActivity, trendConsecutiveThreshold = t)
                    refreshAllUi()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        energyTrendThresholdSeekBar = energyTrendThresholdSlider

        val energyTrendAutoDismissHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, dp(4f))
        }
        val energyTrendAutoDismissLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        energyTrendAutoDismissLabelView = energyTrendAutoDismissLabel

        val energyTrendAutoDismissToggle = Button(this).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            setOnClickListener {
                val cur = QuotaSyncProvider.ensureLoaded(this@MainActivity)
                QuotaSyncProvider.updatePreferences(
                    this@MainActivity,
                    trendAutoDismissEnabled = !cur.trendAutoDismissEnabled
                )
                refreshAllUi()
            }
        }
        energyTrendAutoDismissToggleBtn = energyTrendAutoDismissToggle
        energyTrendAutoDismissHeaderRow.addView(energyTrendAutoDismissLabel)
        energyTrendAutoDismissHeaderRow.addView(energyTrendAutoDismissToggle)

        val energyTrendAutoDismissSlider = SeekBar(this).apply {
            max = 55
            progress = QuotaSyncProvider.DEFAULT_TREND_AUTO_DISMISS_SEC - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (isUpdatingTrendAutoDismissSeekBar || !fromUser) return
                    val sec = (progress + 5).coerceIn(5, 60)
                    QuotaSyncProvider.updatePreferences(this@MainActivity, trendAutoDismissSec = sec)
                    refreshAllUi()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        energyTrendAutoDismissSeekBar = energyTrendAutoDismissSlider

        energyCard.addView(energyHeaderRow)
        energyCard.addView(energyCsvExport)
        energyCard.addView(energyThresholdLabel)
        energyCard.addView(energyThresholdSlider)
        energyCard.addView(energyTrendThresholdLabel)
        energyCard.addView(energyTrendThresholdSlider)
        energyCard.addView(energyTrendAutoDismissHeaderRow)
        energyCard.addView(energyTrendAutoDismissSlider)
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
        mainHandler.removeCallbacks(trendAutoDismissRefreshRunnable)
        HookTelemetryReceiver.setUiListener(null)
        QuotaSyncProvider.unregisterStateObserver(this, stateObserver)
        stateObserver = null
        super.onDestroy()
    }
}
