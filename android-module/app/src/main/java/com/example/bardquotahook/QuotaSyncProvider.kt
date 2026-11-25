package com.example.bardquotahook

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Passive, Zero-Background Cross-Process Preference & Session-Scoped Quota Store
 * (`content://com.example.bardquotahook.provider`).
 *
 * - Zero background services, zero timers, and zero wake-up broadcasts.
 * - Tracks `sessionId` (`sess_<12hex>`) alongside `Snapshot` so switching Google accounts inside
 *   Gemini cleanly replaces stale previous-account values (`replaceSession = true`) instead of
 *   bleeding or merging quotas across accounts.
 * - Notifies only currently foregrounded `ContentObserver`s (`MainActivity` or foreground Gemini).
 */
class QuotaSyncProvider : ContentProvider() {

    data class DevConsoleLogEntry(
        val eventType: String = "ZERO_GUARD",
        val eventTimeMs: Long = 0L,
        val cachedAtMs: Long = 0L,
        val sessionId: String = "",
        val source: String = "",
        val restoredQuota: String = "",
        val triggerCount: Int = 0,
        val detailZh: String = "",
        val detailEn: String = ""
    )

    data class UnifiedState(
        val snapshot: QuotaHudView.Snapshot = QuotaHudView.Snapshot(),
        val sessionId: String = "",
        val lang: QuotaHudView.Lang = defaultLangForDevice(),
        val theme: QuotaHudView.ThemeMode = QuotaHudView.ThemeMode.MIUIX,
        val hudOpacity: Int = DEFAULT_HUD_OPACITY,
        val highBatteryThreshold: Int = DEFAULT_HIGH_ENERGY_THRESHOLD,
        val trendConsecutiveThreshold: Int = DEFAULT_TREND_CONSECUTIVE_THRESHOLD,
        val trendAutoDismissEnabled: Boolean = DEFAULT_TREND_AUTO_DISMISS_ENABLED,
        val trendAutoDismissSec: Int = DEFAULT_TREND_AUTO_DISMISS_SEC,
        val xposedBound: Boolean = false,
        val isApi102: Boolean = false,
        val lastHeartbeatMs: Long = 0L,
        val lastQuotaMs: Long = 0L,
        val lastPkg: String = "",
        val lastProc: String = "",
        val lastBridge: String = "",
        val overlayEnabled: Boolean = false,
        val zeroGuardCount: Int = 0,
        val linkAnomalyCount: Int = 0,
        val forceScanSeq: Long = 0L,
        val resetTrendSeq: Long = 0L,
        val devLogsJson: String = "[]",
        val energyStatsJson: String = "{}"
    ) {
        val isActivelyBound: Boolean
            get() = xposedBound &&
                lastHeartbeatMs > 0L &&
                (System.currentTimeMillis() - lastHeartbeatMs) in 0L..ACTIVE_HOOK_WINDOW_MS

        fun toBundle(): Bundle {
            val clean = snapshot.sanitized()
            return Bundle().apply {
                putString(KEY_FIVE, clean.fiveHourQuota)
                putString(KEY_FIVE_RESET, clean.fiveHourReset)
                putString(KEY_WEEK, clean.weeklyLimit)
                putString(KEY_WEEK_RESET, clean.weeklyReset)
                putString(KEY_SRC, clean.source)
                putBoolean(KEY_STALE_OFFLINE, clean.isStaleOffline)
                putString(KEY_SESSION_ID, sessionId)
                putString(KEY_LANG, lang.code)
                putString(KEY_THEME, theme.code)
                putInt(KEY_HUD_OPACITY, hudOpacity.coerceIn(20, 100))
                putInt(KEY_HIGH_ENERGY_THRESHOLD, highBatteryThreshold.coerceIn(5, 60))
                putInt(KEY_TREND_CONSECUTIVE_THRESHOLD, trendConsecutiveThreshold.coerceIn(2, 10))
                putBoolean(KEY_TREND_AUTO_DISMISS_ENABLED, trendAutoDismissEnabled)
                putInt(KEY_TREND_AUTO_DISMISS_SEC, trendAutoDismissSec.coerceIn(5, 60))
                putBoolean(KEY_XPOSED_BOUND, isActivelyBound)
                putBoolean(KEY_API_102, isApi102)
                putLong(KEY_LAST_HB_MS, lastHeartbeatMs)
                putLong(KEY_LAST_QUOTA_MS, lastQuotaMs)
                putString(KEY_LAST_PKG, lastPkg)
                putString(KEY_LAST_PROC, lastProc)
                putString(KEY_LAST_BRIDGE, lastBridge)
                putBoolean(KEY_OVERLAY_ENABLED, false)
                putInt(KEY_ZERO_GUARD_COUNT, zeroGuardCount)
                putInt(KEY_LINK_ANOMALY_COUNT, linkAnomalyCount)
                putLong(KEY_FORCE_SCAN_SEQ, forceScanSeq)
                putLong(KEY_RESET_TREND_SEQ, resetTrendSeq)
                putString(KEY_DEV_LOGS_JSON, devLogsJson)
                putString(KEY_ENERGY_STATS_JSON, energyStatsJson)
            }
        }

        companion object {
            fun fromBundle(bundle: Bundle?): UnifiedState? {
                if (bundle == null) return null
                val snap = QuotaHudView.Snapshot(
                    fiveHourQuota = bundle.getString(KEY_FIVE, "--") ?: "--",
                    fiveHourReset = bundle.getString(KEY_FIVE_RESET, "--") ?: "--",
                    weeklyLimit = bundle.getString(KEY_WEEK, "--") ?: "--",
                    weeklyReset = bundle.getString(KEY_WEEK_RESET, "--") ?: "--",
                    source = bundle.getString(KEY_SRC, "UnifiedStore") ?: "UnifiedStore",
                    isStaleOffline = bundle.getBoolean(KEY_STALE_OFFLINE, false)
                ).sanitized()
                return UnifiedState(
                    snapshot = snap,
                    sessionId = bundle.getString(KEY_SESSION_ID, "") ?: "",
                    lang = bundle.getString(KEY_LANG, null)?.let { QuotaHudView.Lang.fromCode(it) } ?: defaultLangForDevice(),
                    theme = QuotaHudView.ThemeMode.fromCode(bundle.getString(KEY_THEME, "MIUIX")),
                    hudOpacity = bundle.getInt(KEY_HUD_OPACITY, DEFAULT_HUD_OPACITY).coerceIn(20, 100),
                    highBatteryThreshold = bundle.getInt(KEY_HIGH_ENERGY_THRESHOLD, DEFAULT_HIGH_ENERGY_THRESHOLD).coerceIn(5, 60),
                    trendConsecutiveThreshold = bundle.getInt(KEY_TREND_CONSECUTIVE_THRESHOLD, DEFAULT_TREND_CONSECUTIVE_THRESHOLD).coerceIn(2, 10),
                    trendAutoDismissEnabled = bundle.getBoolean(KEY_TREND_AUTO_DISMISS_ENABLED, DEFAULT_TREND_AUTO_DISMISS_ENABLED),
                    trendAutoDismissSec = bundle.getInt(KEY_TREND_AUTO_DISMISS_SEC, DEFAULT_TREND_AUTO_DISMISS_SEC).coerceIn(5, 60),
                    xposedBound = bundle.getBoolean(KEY_XPOSED_BOUND, false),
                    isApi102 = bundle.getBoolean(KEY_API_102, false),
                    lastHeartbeatMs = bundle.getLong(KEY_LAST_HB_MS, 0L),
                    lastQuotaMs = bundle.getLong(KEY_LAST_QUOTA_MS, 0L),
                    lastPkg = bundle.getString(KEY_LAST_PKG, "") ?: "",
                    lastProc = bundle.getString(KEY_LAST_PROC, "") ?: "",
                    lastBridge = bundle.getString(KEY_LAST_BRIDGE, "") ?: "",
                    overlayEnabled = false,
                    zeroGuardCount = bundle.getInt(KEY_ZERO_GUARD_COUNT, 0),
                    linkAnomalyCount = bundle.getInt(KEY_LINK_ANOMALY_COUNT, 0),
                    forceScanSeq = bundle.getLong(KEY_FORCE_SCAN_SEQ, 0L),
                    resetTrendSeq = bundle.getLong(KEY_RESET_TREND_SEQ, 0L),
                    devLogsJson = bundle.getString(KEY_DEV_LOGS_JSON, "[]") ?: "[]",
                    energyStatsJson = bundle.getString(KEY_ENERGY_STATS_JSON, "{}") ?: "{}"
                )
            }
        }
    }

    override fun onCreate(): Boolean {
        context?.let { ensureLoaded(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val appCtx = ctx.applicationContext ?: ctx
        return try {
            when (method) {
                METHOD_GET_STATE -> ensureLoaded(appCtx).toBundle()

                METHOD_PUT_QUOTA -> {
                    val incoming = QuotaHudView.Snapshot(
                        fiveHourQuota = extras?.getString(KEY_FIVE, "--") ?: "--",
                        fiveHourReset = extras?.getString(KEY_FIVE_RESET, "--") ?: "--",
                        weeklyLimit = extras?.getString(KEY_WEEK, "--") ?: "--",
                        weeklyReset = extras?.getString(KEY_WEEK_RESET, "--") ?: "--",
                        source = extras?.getString(KEY_SRC, "InAppHook") ?: "InAppHook",
                        isStaleOffline = extras?.getBoolean(KEY_STALE_OFFLINE, false) ?: false
                    ).sanitized()
                    val incomingSession = extras?.getString(KEY_SESSION_ID)
                    val replaceSession = extras?.getBoolean(KEY_REPLACE_SESSION, false) ?: false

                    val updated = mutateLocalStore(
                        appCtx = appCtx,
                        incomingSnapshot = incoming,
                        sessionId = incomingSession,
                        replaceSession = replaceSession
                    )
                    dispatchForegroundNotifications(appCtx, updated)
                    updated.toBundle()
                }

                METHOD_PUT_PREFS -> {
                    val lang = extras?.getString(KEY_LANG)?.let { QuotaHudView.Lang.fromCode(it) }
                    val theme = extras?.getString(KEY_THEME)?.let { QuotaHudView.ThemeMode.fromCode(it) }
                    val hudOpacity = if (extras?.containsKey(KEY_HUD_OPACITY) == true) {
                        extras.getInt(KEY_HUD_OPACITY, DEFAULT_HUD_OPACITY).coerceIn(20, 100)
                    } else {
                        null
                    }
                    val highThreshold = if (extras?.containsKey(KEY_HIGH_ENERGY_THRESHOLD) == true) {
                        extras.getInt(KEY_HIGH_ENERGY_THRESHOLD, DEFAULT_HIGH_ENERGY_THRESHOLD).coerceIn(5, 60)
                    } else {
                        null
                    }
                    val trendThreshold = if (extras?.containsKey(KEY_TREND_CONSECUTIVE_THRESHOLD) == true) {
                        extras.getInt(KEY_TREND_CONSECUTIVE_THRESHOLD, DEFAULT_TREND_CONSECUTIVE_THRESHOLD).coerceIn(2, 10)
                    } else {
                        null
                    }
                    val autoDismissEnabled = if (extras?.containsKey(KEY_TREND_AUTO_DISMISS_ENABLED) == true) {
                        extras.getBoolean(KEY_TREND_AUTO_DISMISS_ENABLED, DEFAULT_TREND_AUTO_DISMISS_ENABLED)
                    } else {
                        null
                    }
                    val autoDismissSec = if (extras?.containsKey(KEY_TREND_AUTO_DISMISS_SEC) == true) {
                        extras.getInt(KEY_TREND_AUTO_DISMISS_SEC, DEFAULT_TREND_AUTO_DISMISS_SEC).coerceIn(5, 60)
                    } else {
                        null
                    }
                    val updated = mutateLocalStore(
                        appCtx = appCtx,
                        lang = lang,
                        theme = theme,
                        hudOpacity = hudOpacity,
                        highBatteryThreshold = highThreshold,
                        trendConsecutiveThreshold = trendThreshold,
                        trendAutoDismissEnabled = autoDismissEnabled,
                        trendAutoDismissSec = autoDismissSec
                    )
                    dispatchForegroundNotifications(appCtx, updated)
                    updated.toBundle()
                }

                METHOD_HEARTBEAT -> {
                    val pkg = extras?.getString(KEY_LAST_PKG) ?: arg ?: ""
                    val proc = extras?.getString(KEY_LAST_PROC) ?: ""
                    val bridge = extras?.getString(KEY_LAST_BRIDGE) ?: "Xposed"
                    val api102 = extras?.getBoolean(KEY_API_102, false) ?: false
                    val incomingSession = extras?.getString(KEY_SESSION_ID)
                    val candidate = QuotaHudView.Snapshot(
                        fiveHourQuota = extras?.getString(KEY_FIVE, "--") ?: "--",
                        fiveHourReset = extras?.getString(KEY_FIVE_RESET, "--") ?: "--",
                        weeklyLimit = extras?.getString(KEY_WEEK, "--") ?: "--",
                        weeklyReset = extras?.getString(KEY_WEEK_RESET, "--") ?: "--",
                        source = extras?.getString(KEY_SRC, "InAppHook") ?: "InAppHook",
                        isStaleOffline = extras?.getBoolean(KEY_STALE_OFFLINE, false) ?: false
                    ).sanitized()

                    val updated = mutateLocalStore(
                        appCtx = appCtx,
                        incomingSnapshot = if (candidate.hasLiveData) candidate else null,
                        sessionId = incomingSession,
                        pkg = pkg,
                        proc = proc,
                        bridge = bridge,
                        isApi102 = api102,
                        markXposedBound = true
                    )

                    OverlayHudController.enforceSingleHudExclusivity(appCtx)
                    dispatchForegroundNotifications(appCtx, updated)
                    updated.toBundle()
                }

                METHOD_APPEND_DEV_EVENT -> {
                    val eventJson = extras?.getString(KEY_DEV_EVENT_JSON)
                    val incrementZeroGuard = extras?.getBoolean(KEY_INCREMENT_ZERO_GUARD, false) ?: false
                    val incrementLinkAnomaly = extras?.getBoolean(KEY_INCREMENT_LINK_ANOMALY, false) ?: false
                    val updated = appendDevEventLocal(appCtx, eventJson, incrementZeroGuard, incrementLinkAnomaly)
                    HookTelemetryReceiver.notifyUiListeners()
                    updated.toBundle()
                }

                METHOD_CLEAR_DEV_EVENTS -> {
                    val updated = clearDevEventsLocal(appCtx)
                    HookTelemetryReceiver.notifyUiListeners()
                    updated.toBundle()
                }

                METHOD_REQUEST_FULL_SCAN -> {
                    val updated = bumpForceScanSeqLocal(appCtx)
                    HookTelemetryReceiver.notifyUiListeners()
                    updated.toBundle()
                }

                METHOD_RESET_TREND_HISTORY -> {
                    val updated = resetTrendAlertHistoryLocal(appCtx)
                    HookTelemetryReceiver.notifyUiListeners()
                    updated.toBundle()
                }

                METHOD_PUT_ENERGY_STATS -> {
                    val energyJson = extras?.getString(KEY_ENERGY_STATS_JSON) ?: "{}"
                    val updated = mutateLocalStore(appCtx = appCtx, energyStatsJson = energyJson)
                    HookTelemetryReceiver.notifyUiListeners()
                    updated.toBundle()
                }

                else -> ensureLoaded(appCtx).toBundle()
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cols = arrayOf(
            KEY_FIVE,
            KEY_FIVE_RESET,
            KEY_WEEK,
            KEY_WEEK_RESET,
            KEY_SRC,
            KEY_SESSION_ID,
            KEY_LANG,
            KEY_THEME,
            KEY_XPOSED_BOUND,
            KEY_API_102,
            KEY_LAST_HB_MS,
            KEY_LAST_QUOTA_MS
        )
        val cursor = MatrixCursor(cols, 1)
        val ctx = context
        if (ctx != null) {
            val st = ensureLoaded(ctx.applicationContext ?: ctx)
            val snap = st.snapshot.sanitized()
            cursor.addRow(
                arrayOf<Any>(
                    snap.fiveHourQuota,
                    snap.fiveHourReset,
                    snap.weeklyLimit,
                    snap.weeklyReset,
                    snap.source,
                    st.sessionId,
                    st.lang.code,
                    st.theme.code,
                    if (st.isActivelyBound) 1 else 0,
                    if (st.isApi102) 1 else 0,
                    st.lastHeartbeatMs,
                    st.lastQuotaMs
                )
            )
            cursor.setNotificationUri(ctx.contentResolver, STATE_URI)
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.bardquotahook.state"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val MODULE_PACKAGE = "com.example.bardquotahook"
        const val AUTHORITY = "com.example.bardquotahook.provider"
        val AUTHORITY_URI: Uri = Uri.parse("content://$AUTHORITY")
        val STATE_URI: Uri = Uri.parse("content://$AUTHORITY/state")

        const val METHOD_GET_STATE = "get_state"
        const val METHOD_PUT_QUOTA = "put_quota"
        const val METHOD_PUT_PREFS = "put_prefs"
        const val METHOD_HEARTBEAT = "heartbeat"
        const val METHOD_APPEND_DEV_EVENT = "append_dev_event"
        const val METHOD_CLEAR_DEV_EVENTS = "clear_dev_events"
        const val METHOD_REQUEST_FULL_SCAN = "request_full_scan"
        const val METHOD_RESET_TREND_HISTORY = "reset_trend_history"
        const val METHOD_PUT_ENERGY_STATS = "put_energy_stats"

        const val KEY_FIVE = "five"
        const val KEY_FIVE_RESET = "five_reset"
        const val KEY_WEEK = "week"
        const val KEY_WEEK_RESET = "week_reset"
        const val KEY_SRC = "src"
        const val KEY_STALE_OFFLINE = "stale_offline"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_REPLACE_SESSION = "replace_session"
        const val KEY_LANG = "ui_lang"
        const val KEY_THEME = "ui_theme"
        const val KEY_HUD_OPACITY = "hud_opacity"
        const val DEFAULT_HUD_OPACITY = 85
        const val KEY_HIGH_ENERGY_THRESHOLD = "high_energy_threshold"
        const val DEFAULT_HIGH_ENERGY_THRESHOLD = 20
        const val KEY_TREND_CONSECUTIVE_THRESHOLD = "trend_consecutive_threshold"
        const val DEFAULT_TREND_CONSECUTIVE_THRESHOLD = 3
        const val KEY_TREND_AUTO_DISMISS_ENABLED = "trend_auto_dismiss_enabled"
        const val DEFAULT_TREND_AUTO_DISMISS_ENABLED = true
        const val KEY_TREND_AUTO_DISMISS_SEC = "trend_auto_dismiss_sec"
        const val DEFAULT_TREND_AUTO_DISMISS_SEC = 15
        const val KEY_XPOSED_BOUND = "xposed_bound"
        const val KEY_API_102 = "api_102_bound"
        const val KEY_LAST_HB_MS = "last_hb_ms"
        const val KEY_LAST_QUOTA_MS = "last_quota_ms"
        const val KEY_LAST_PKG = "last_pkg"
        const val KEY_LAST_PROC = "last_proc"
        const val KEY_LAST_BRIDGE = "last_bridge"
        const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        const val KEY_ZERO_GUARD_COUNT = "zero_guard_count"
        const val KEY_LINK_ANOMALY_COUNT = "link_anomaly_count"
        const val KEY_FORCE_SCAN_SEQ = "force_scan_seq"
        const val KEY_RESET_TREND_SEQ = "reset_trend_seq"
        const val KEY_DEV_LOGS_JSON = "dev_logs_json"
        const val KEY_DEV_EVENT_JSON = "dev_event_json"
        const val KEY_ENERGY_STATS_JSON = "energy_stats_json"
        const val KEY_INCREMENT_ZERO_GUARD = "increment_zero_guard"
        const val KEY_INCREMENT_LINK_ANOMALY = "increment_link_anomaly"

        private const val ACTIVE_HOOK_WINDOW_MS = 12 * 60 * 60 * 1000L
        private const val MAX_DEV_CONSOLE_LOGS = 30

        private val stateLock = Any()
        private val cachedState = AtomicReference<UnifiedState?>(null)

        fun defaultLangForDevice(): QuotaHudView.Lang {
            val lang = try {
                Locale.getDefault().language
            } catch (_: Throwable) {
                ""
            }
            return if (lang.equals("zh", ignoreCase = true)) {
                QuotaHudView.Lang.ZH
            } else {
                QuotaHudView.Lang.EN
            }
        }

        private fun isInModuleProcess(context: Context): Boolean {
            return context.packageName == MODULE_PACKAGE
        }

        fun ensureLoaded(context: Context): UnifiedState {
            val existing = cachedState.get()
            if (existing != null) return existing

            synchronized(stateLock) {
                val again = cachedState.get()
                if (again != null) return again

                val appCtx = context.applicationContext ?: context
                val prefs = appCtx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)

                val cleanSnap = QuotaHudView.Snapshot(
                    fiveHourQuota = prefs.getString(KEY_FIVE, "--") ?: "--",
                    fiveHourReset = prefs.getString(KEY_FIVE_RESET, "--") ?: "--",
                    weeklyLimit = prefs.getString(KEY_WEEK, "--") ?: "--",
                    weeklyReset = prefs.getString(KEY_WEEK_RESET, "--") ?: "--",
                    source = prefs.getString(KEY_SRC, "UnifiedStore") ?: "UnifiedStore"
                ).sanitized()

                val loaded = UnifiedState(
                    snapshot = cleanSnap,
                    sessionId = prefs.getString(KEY_SESSION_ID, "") ?: "",
                    lang = prefs.getString(KEY_LANG, null)?.let { QuotaHudView.Lang.fromCode(it) } ?: defaultLangForDevice(),
                    theme = QuotaHudView.ThemeMode.fromCode(prefs.getString(KEY_THEME, "MIUIX")),
                    hudOpacity = prefs.getInt(KEY_HUD_OPACITY, DEFAULT_HUD_OPACITY).coerceIn(20, 100),
                    highBatteryThreshold = prefs.getInt(KEY_HIGH_ENERGY_THRESHOLD, DEFAULT_HIGH_ENERGY_THRESHOLD).coerceIn(5, 60),
                    trendConsecutiveThreshold = prefs.getInt(KEY_TREND_CONSECUTIVE_THRESHOLD, DEFAULT_TREND_CONSECUTIVE_THRESHOLD).coerceIn(2, 10),
                    trendAutoDismissEnabled = prefs.getBoolean(KEY_TREND_AUTO_DISMISS_ENABLED, DEFAULT_TREND_AUTO_DISMISS_ENABLED),
                    trendAutoDismissSec = prefs.getInt(KEY_TREND_AUTO_DISMISS_SEC, DEFAULT_TREND_AUTO_DISMISS_SEC).coerceIn(5, 60),
                    xposedBound = prefs.getBoolean(KEY_XPOSED_BOUND, false),
                    isApi102 = prefs.getBoolean(KEY_API_102, false),
                    lastHeartbeatMs = prefs.getLong(KEY_LAST_HB_MS, 0L),
                    lastQuotaMs = prefs.getLong(KEY_LAST_QUOTA_MS, 0L),
                    lastPkg = prefs.getString(KEY_LAST_PKG, "") ?: "",
                    lastProc = prefs.getString(KEY_LAST_PROC, "") ?: "",
                    lastBridge = prefs.getString(KEY_LAST_BRIDGE, "") ?: "",
                    overlayEnabled = false,
                    zeroGuardCount = prefs.getInt(KEY_ZERO_GUARD_COUNT, 0),
                    linkAnomalyCount = prefs.getInt(KEY_LINK_ANOMALY_COUNT, 0),
                    forceScanSeq = prefs.getLong(KEY_FORCE_SCAN_SEQ, 0L),
                    resetTrendSeq = prefs.getLong(KEY_RESET_TREND_SEQ, 0L),
                    devLogsJson = prefs.getString(KEY_DEV_LOGS_JSON, "[]") ?: "[]",
                    energyStatsJson = prefs.getString(KEY_ENERGY_STATS_JSON, "{}") ?: "{}"
                )
                cachedState.set(loaded)
                return loaded
            }
        }

        @Synchronized
        internal fun mutateLocalStore(
            appCtx: Context,
            incomingSnapshot: QuotaHudView.Snapshot? = null,
            sessionId: String? = null,
            replaceSession: Boolean = false,
            lang: QuotaHudView.Lang? = null,
            theme: QuotaHudView.ThemeMode? = null,
            hudOpacity: Int? = null,
            highBatteryThreshold: Int? = null,
            trendConsecutiveThreshold: Int? = null,
            trendAutoDismissEnabled: Boolean? = null,
            trendAutoDismissSec: Int? = null,
            pkg: String? = null,
            proc: String? = null,
            bridge: String? = null,
            isApi102: Boolean? = null,
            markXposedBound: Boolean = false,
            energyStatsJson: String? = null
        ): UnifiedState {
            val prev = ensureLoaded(appCtx)
            val now = System.currentTimeMillis()

            val nextSessionId = if (!sessionId.isNullOrBlank() && sessionId != "sess_default") {
                sessionId
            } else {
                prev.sessionId
            }

            // Only wipe/reset snapshot on an explicitly confirmed account switch (`replaceSession == true`),
            // never on transient heartbeats or session-tag strength upgrades.
            val cleanIncoming = incomingSnapshot?.sanitized()
            val prevIsReal = prev.snapshot.hasLiveData && prev.snapshot.source != "SessionEstimate"
            val incomingIsEstimate = cleanIncoming?.source == "SessionEstimate"

            val mergedSnap = when {
                replaceSession -> {
                    cleanIncoming ?: QuotaHudView.Snapshot(source = "AccountSwitch")
                }
                cleanIncoming != null && cleanIncoming.hasLiveData && !(prevIsReal && incomingIsEstimate) -> {
                    QuotaHudView.Snapshot(
                        fiveHourQuota = if (cleanIncoming.fiveHourQuota != "--") cleanIncoming.fiveHourQuota else prev.snapshot.fiveHourQuota,
                        fiveHourReset = if (cleanIncoming.fiveHourReset != "--") cleanIncoming.fiveHourReset else prev.snapshot.fiveHourReset,
                        weeklyLimit = if (cleanIncoming.weeklyLimit != "--") cleanIncoming.weeklyLimit else prev.snapshot.weeklyLimit,
                        weeklyReset = if (cleanIncoming.weeklyReset != "--") cleanIncoming.weeklyReset else prev.snapshot.weeklyReset,
                        source = cleanIncoming.source,
                        isStaleOffline = cleanIncoming.isStaleOffline
                    ).sanitized()
                }
                else -> prev.snapshot
            }

            val nextBound = if (markXposedBound) true else prev.xposedBound
            val nextHbMs = if (markXposedBound) now else prev.lastHeartbeatMs
            val nextQuotaMs = if (mergedSnap != prev.snapshot) now else prev.lastQuotaMs

            val next = prev.copy(
                snapshot = mergedSnap,
                sessionId = nextSessionId,
                lang = lang ?: prev.lang,
                theme = theme ?: prev.theme,
                hudOpacity = hudOpacity?.coerceIn(20, 100) ?: prev.hudOpacity,
                highBatteryThreshold = highBatteryThreshold?.coerceIn(5, 60) ?: prev.highBatteryThreshold,
                trendConsecutiveThreshold = trendConsecutiveThreshold?.coerceIn(2, 10) ?: prev.trendConsecutiveThreshold,
                trendAutoDismissEnabled = trendAutoDismissEnabled ?: prev.trendAutoDismissEnabled,
                trendAutoDismissSec = trendAutoDismissSec?.coerceIn(5, 60) ?: prev.trendAutoDismissSec,
                xposedBound = nextBound,
                isApi102 = isApi102 ?: prev.isApi102,
                lastHeartbeatMs = nextHbMs,
                lastQuotaMs = nextQuotaMs,
                lastPkg = if (!pkg.isNullOrBlank()) pkg else prev.lastPkg,
                lastProc = if (!proc.isNullOrBlank()) proc else prev.lastProc,
                lastBridge = if (!bridge.isNullOrBlank()) bridge else prev.lastBridge,
                overlayEnabled = false,
                energyStatsJson = energyStatsJson?.takeIf { it.isNotBlank() } ?: prev.energyStatsJson
            )

            cachedState.set(next)

            try {
                appCtx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_FIVE, next.snapshot.fiveHourQuota)
                    .putString(KEY_FIVE_RESET, next.snapshot.fiveHourReset)
                    .putString(KEY_WEEK, next.snapshot.weeklyLimit)
                    .putString(KEY_WEEK_RESET, next.snapshot.weeklyReset)
                    .putString(KEY_SRC, next.snapshot.source)
                    .putString(KEY_SESSION_ID, next.sessionId)
                    .putString(KEY_LANG, next.lang.code)
                    .putString(KEY_THEME, next.theme.code)
                    .putInt(KEY_HUD_OPACITY, next.hudOpacity)
                    .putInt(KEY_HIGH_ENERGY_THRESHOLD, next.highBatteryThreshold)
                    .putInt(KEY_TREND_CONSECUTIVE_THRESHOLD, next.trendConsecutiveThreshold)
                    .putBoolean(KEY_TREND_AUTO_DISMISS_ENABLED, next.trendAutoDismissEnabled)
                    .putInt(KEY_TREND_AUTO_DISMISS_SEC, next.trendAutoDismissSec)
                    .putBoolean(KEY_XPOSED_BOUND, next.xposedBound)
                    .putBoolean(KEY_API_102, next.isApi102)
                    .putLong(KEY_LAST_HB_MS, next.lastHeartbeatMs)
                    .putLong(KEY_LAST_QUOTA_MS, next.lastQuotaMs)
                    .putString(KEY_LAST_PKG, next.lastPkg)
                    .putString(KEY_LAST_PROC, next.lastProc)
                    .putString(KEY_LAST_BRIDGE, next.lastBridge)
                    .putBoolean(KEY_OVERLAY_ENABLED, false)
                    .putString(KEY_ENERGY_STATS_JSON, next.energyStatsJson)
                    .apply()
            } catch (_: Throwable) {
            }

            try {
                appCtx.contentResolver.notifyChange(STATE_URI, null)
            } catch (_: Throwable) {
            }

            return next
        }

        private fun dispatchForegroundNotifications(appCtx: Context, state: UnifiedState) {
            OverlayHudController.updateFromUnifiedState(appCtx, state)
            HookTelemetryReceiver.notifyUiListeners()
        }

        // =====================================================================
        // Cross-Process Client API (Demand-Driven, Zero Background Broadcasts)
        // =====================================================================

        fun readUnifiedState(context: Context): UnifiedState? {
            return try {
                if (isInModuleProcess(context)) {
                    ensureLoaded(context.applicationContext ?: context)
                } else {
                    val reply = context.contentResolver.call(AUTHORITY_URI, METHOD_GET_STATE, null, null)
                    UnifiedState.fromBundle(reply)
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun sendHeartbeatAndFetchState(
            context: Context,
            pkg: String,
            proc: String,
            bridge: String,
            isApi102: Boolean,
            localSnapshot: QuotaHudView.Snapshot,
            sessionId: String = ""
        ): UnifiedState? {
            return try {
                val clean = localSnapshot.sanitized()
                val extras = Bundle().apply {
                    putString(KEY_LAST_PKG, pkg)
                    putString(KEY_LAST_PROC, proc)
                    putString(KEY_LAST_BRIDGE, bridge)
                    putBoolean(KEY_API_102, isApi102)
                    if (sessionId.isNotBlank()) {
                        putString(KEY_SESSION_ID, sessionId)
                    }
                    if (clean.hasLiveData) {
                        putString(KEY_FIVE, clean.fiveHourQuota)
                        putString(KEY_FIVE_RESET, clean.fiveHourReset)
                        putString(KEY_WEEK, clean.weeklyLimit)
                        putString(KEY_WEEK_RESET, clean.weeklyReset)
                        putString(KEY_SRC, clean.source)
                        putBoolean(KEY_STALE_OFFLINE, clean.isStaleOffline)
                    }
                }
                val reply = context.contentResolver.call(AUTHORITY_URI, METHOD_HEARTBEAT, pkg, extras)
                UnifiedState.fromBundle(reply)
            } catch (_: Throwable) {
                null
            }
        }

        fun publishSessionQuota(
            context: Context,
            snapshot: QuotaHudView.Snapshot,
            sessionId: String,
            replaceSession: Boolean
        ): UnifiedState? {
            val clean = snapshot.sanitized()
            if (!replaceSession && !clean.hasLiveData) return readUnifiedState(context)
            return try {
                if (isInModuleProcess(context)) {
                    val appCtx = context.applicationContext ?: context
                    val updated = mutateLocalStore(
                        appCtx = appCtx,
                        incomingSnapshot = clean,
                        sessionId = sessionId,
                        replaceSession = replaceSession
                    )
                    dispatchForegroundNotifications(appCtx, updated)
                    updated
                } else {
                    val extras = Bundle().apply {
                        putString(KEY_FIVE, clean.fiveHourQuota)
                        putString(KEY_FIVE_RESET, clean.fiveHourReset)
                        putString(KEY_WEEK, clean.weeklyLimit)
                        putString(KEY_WEEK_RESET, clean.weeklyReset)
                        putString(KEY_SRC, clean.source)
                        putBoolean(KEY_STALE_OFFLINE, clean.isStaleOffline)
                        putString(KEY_SESSION_ID, sessionId)
                        putBoolean(KEY_REPLACE_SESSION, replaceSession)
                    }
                    val reply = context.contentResolver.call(AUTHORITY_URI, METHOD_PUT_QUOTA, null, extras)
                    UnifiedState.fromBundle(reply)
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun publishQuota(context: Context, snapshot: QuotaHudView.Snapshot): UnifiedState? {
            return publishSessionQuota(
                context = context,
                snapshot = snapshot,
                sessionId = "",
                replaceSession = false
            )
        }

        fun updatePreferences(
            context: Context,
            lang: QuotaHudView.Lang? = null,
            theme: QuotaHudView.ThemeMode? = null,
            hudOpacity: Int? = null,
            highBatteryThreshold: Int? = null,
            trendConsecutiveThreshold: Int? = null,
            trendAutoDismissEnabled: Boolean? = null,
            trendAutoDismissSec: Int? = null,
            overlayEnabled: Boolean? = null
        ): UnifiedState? {
            return try {
                if (isInModuleProcess(context)) {
                    val appCtx = context.applicationContext ?: context
                    val updated = mutateLocalStore(
                        appCtx = appCtx,
                        lang = lang,
                        theme = theme,
                        hudOpacity = hudOpacity,
                        highBatteryThreshold = highBatteryThreshold,
                        trendConsecutiveThreshold = trendConsecutiveThreshold,
                        trendAutoDismissEnabled = trendAutoDismissEnabled,
                        trendAutoDismissSec = trendAutoDismissSec
                    )
                    dispatchForegroundNotifications(appCtx, updated)
                    updated
                } else {
                    val extras = Bundle().apply {
                        if (lang != null) putString(KEY_LANG, lang.code)
                        if (theme != null) putString(KEY_THEME, theme.code)
                        if (hudOpacity != null) putInt(KEY_HUD_OPACITY, hudOpacity.coerceIn(20, 100))
                        if (highBatteryThreshold != null) putInt(KEY_HIGH_ENERGY_THRESHOLD, highBatteryThreshold.coerceIn(5, 60))
                        if (trendConsecutiveThreshold != null) putInt(KEY_TREND_CONSECUTIVE_THRESHOLD, trendConsecutiveThreshold.coerceIn(2, 10))
                        if (trendAutoDismissEnabled != null) putBoolean(KEY_TREND_AUTO_DISMISS_ENABLED, trendAutoDismissEnabled)
                        if (trendAutoDismissSec != null) putInt(KEY_TREND_AUTO_DISMISS_SEC, trendAutoDismissSec.coerceIn(5, 60))
                    }
                    val reply = context.contentResolver.call(AUTHORITY_URI, METHOD_PUT_PREFS, null, extras)
                    UnifiedState.fromBundle(reply)
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun registerStateObserver(context: Context, onChanged: () -> Unit): ContentObserver? {
            return try {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        onChanged()
                    }

                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        onChanged()
                    }
                }
                context.contentResolver.registerContentObserver(STATE_URI, true, observer)
                observer
            } catch (_: Throwable) {
                null
            }
        }

        fun unregisterStateObserver(context: Context, observer: ContentObserver?) {
            if (observer == null) return
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (_: Throwable) {
            }
        }

        fun parseDevConsoleLogs(rawJson: String?): List<DevConsoleLogEntry> {
            if (rawJson.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(rawJson)
                val out = ArrayList<DevConsoleLogEntry>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    out.add(
                        DevConsoleLogEntry(
                            eventType = obj.optString("eventType", "ZERO_GUARD"),
                            eventTimeMs = obj.optLong("eventTimeMs", 0L),
                            cachedAtMs = obj.optLong("cachedAtMs", 0L),
                            sessionId = obj.optString("sessionId", ""),
                            source = obj.optString("source", ""),
                            restoredQuota = obj.optString("restoredQuota", ""),
                            triggerCount = obj.optInt("triggerCount", 0),
                            detailZh = obj.optString("detailZh", ""),
                            detailEn = obj.optString("detailEn", "")
                        )
                    )
                }
                out
            } catch (_: Throwable) {
                emptyList()
            }
        }

        @Synchronized
        internal fun appendDevEventLocal(
            appCtx: Context,
            eventJson: String?,
            incrementZeroGuard: Boolean,
            incrementLinkAnomaly: Boolean = false
        ): UnifiedState {
            val prev = ensureLoaded(appCtx)
            if (eventJson.isNullOrBlank()) return prev

            val nextZeroCount = if (incrementZeroGuard) prev.zeroGuardCount + 1 else prev.zeroGuardCount
            val nextAnomalyCount = if (incrementLinkAnomaly) prev.linkAnomalyCount + 1 else prev.linkAnomalyCount
            val nextArr = JSONArray()
            try {
                val incoming = JSONObject(eventJson)
                if (incrementZeroGuard && !incoming.has("triggerCount")) {
                    incoming.put("triggerCount", nextZeroCount)
                }
                if (incrementLinkAnomaly && !incoming.has("triggerCount")) {
                    incoming.put("triggerCount", nextAnomalyCount)
                }
                nextArr.put(incoming)
                val prevArr = JSONArray(prev.devLogsJson.ifBlank { "[]" })
                val limit = minOf(prevArr.length(), MAX_DEV_CONSOLE_LOGS - 1)
                for (i in 0 until limit) {
                    prevArr.optJSONObject(i)?.let { nextArr.put(it) }
                }
            } catch (_: Throwable) {
                return prev
            }

            val nextJson = nextArr.toString()
            val next = prev.copy(
                zeroGuardCount = nextZeroCount,
                linkAnomalyCount = nextAnomalyCount,
                devLogsJson = nextJson
            )
            cachedState.set(next)

            try {
                appCtx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_ZERO_GUARD_COUNT, nextZeroCount)
                    .putInt(KEY_LINK_ANOMALY_COUNT, nextAnomalyCount)
                    .putString(KEY_DEV_LOGS_JSON, nextJson)
                    .apply()
            } catch (_: Throwable) {
            }

            try {
                appCtx.contentResolver.notifyChange(STATE_URI, null)
            } catch (_: Throwable) {
            }
            return next
        }

        @Synchronized
        internal fun clearDevEventsLocal(appCtx: Context): UnifiedState {
            val prev = ensureLoaded(appCtx)
            val next = prev.copy(
                zeroGuardCount = 0,
                linkAnomalyCount = 0,
                devLogsJson = "[]"
            )
            cachedState.set(next)

            try {
                appCtx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_ZERO_GUARD_COUNT, 0)
                    .putInt(KEY_LINK_ANOMALY_COUNT, 0)
                    .putString(KEY_DEV_LOGS_JSON, "[]")
                    .apply()
            } catch (_: Throwable) {
            }

            try {
                appCtx.contentResolver.notifyChange(STATE_URI, null)
            } catch (_: Throwable) {
            }
            return next
        }

        fun recordZeroGuardTriggeredEvent(
            context: Context,
            sessionId: String,
            source: String,
            cachedAtMs: Long,
            restoredQuota: String,
            detailZh: String,
            detailEn: String,
            triggerCount: Int
        ) {
            try {
                val obj = JSONObject().apply {
                    put("eventType", "ZERO_GUARD")
                    put("eventTimeMs", System.currentTimeMillis())
                    put("cachedAtMs", cachedAtMs)
                    put("sessionId", sessionId)
                    put("source", source)
                    put("restoredQuota", restoredQuota)
                    put("triggerCount", triggerCount)
                    put("detailZh", detailZh)
                    put("detailEn", detailEn)
                }
                if (isInModuleProcess(context)) {
                    appendDevEventLocal(context.applicationContext ?: context, obj.toString(), true, false)
                    HookTelemetryReceiver.notifyUiListeners()
                } else {
                    val extras = Bundle().apply {
                        putString(KEY_DEV_EVENT_JSON, obj.toString())
                        putBoolean(KEY_INCREMENT_ZERO_GUARD, true)
                    }
                    context.contentResolver.call(AUTHORITY_URI, METHOD_APPEND_DEV_EVENT, null, extras)
                }
            } catch (_: Throwable) {
            }
        }

        fun recordLinkAnomalyEvent(
            context: Context,
            sessionId: String,
            source: String,
            cleanedSummary: String,
            detailZh: String,
            detailEn: String,
            anomalyCount: Int
        ) {
            try {
                val obj = JSONObject().apply {
                    put("eventType", "LINK_ANOMALY")
                    put("eventTimeMs", System.currentTimeMillis())
                    put("cachedAtMs", 0L)
                    put("sessionId", sessionId)
                    put("source", source)
                    put("restoredQuota", cleanedSummary)
                    put("triggerCount", anomalyCount)
                    put("detailZh", detailZh)
                    put("detailEn", detailEn)
                }
                if (isInModuleProcess(context)) {
                    appendDevEventLocal(context.applicationContext ?: context, obj.toString(), false, true)
                    HookTelemetryReceiver.notifyUiListeners()
                } else {
                    val extras = Bundle().apply {
                        putString(KEY_DEV_EVENT_JSON, obj.toString())
                        putBoolean(KEY_INCREMENT_LINK_ANOMALY, true)
                    }
                    context.contentResolver.call(AUTHORITY_URI, METHOD_APPEND_DEV_EVENT, null, extras)
                }
            } catch (_: Throwable) {
            }
        }

        fun recordFullProtocolScanEvent(
            context: Context,
            sessionId: String,
            source: String,
            summary: String,
            detailZh: String,
            detailEn: String,
            scanCount: Int = 1
        ) {
            try {
                val obj = JSONObject().apply {
                    put("eventType", "FULL_PROTOCOL_SCAN")
                    put("eventTimeMs", System.currentTimeMillis())
                    put("cachedAtMs", 0L)
                    put("sessionId", sessionId)
                    put("source", source)
                    put("restoredQuota", summary)
                    put("triggerCount", scanCount)
                    put("detailZh", detailZh)
                    put("detailEn", detailEn)
                }
                if (isInModuleProcess(context)) {
                    appendDevEventLocal(context.applicationContext ?: context, obj.toString(), false, false)
                    HookTelemetryReceiver.notifyUiListeners()
                } else {
                    val extras = Bundle().apply {
                        putString(KEY_DEV_EVENT_JSON, obj.toString())
                    }
                    context.contentResolver.call(AUTHORITY_URI, METHOD_APPEND_DEV_EVENT, null, extras)
                }
            } catch (_: Throwable) {
            }
        }

        fun publishEnergyStats(context: Context, energyStatsJson: String): UnifiedState? {
            return try {
                if (isInModuleProcess(context)) {
                    val updated = mutateLocalStore(
                        appCtx = context.applicationContext ?: context,
                        energyStatsJson = energyStatsJson
                    )
                    HookTelemetryReceiver.notifyUiListeners()
                    updated
                } else {
                    val extras = Bundle().apply {
                        putString(KEY_ENERGY_STATS_JSON, energyStatsJson)
                    }
                    val reply = context.contentResolver.call(AUTHORITY_URI, METHOD_PUT_ENERGY_STATS, null, extras)
                    UnifiedState.fromBundle(reply)
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun recordBatteryImpactEvent(
            context: Context,
            sessionId: String,
            source: String,
            summary: String,
            detailZh: String,
            detailEn: String,
            energyStatsJson: String? = null,
            eventCount: Int = 1
        ) {
            try {
                if (!energyStatsJson.isNullOrBlank()) {
                    publishEnergyStats(context, energyStatsJson)
                }
                val obj = JSONObject().apply {
                    put("eventType", "BATTERY_IMPACT")
                    put("eventTimeMs", System.currentTimeMillis())
                    put("cachedAtMs", 0L)
                    put("sessionId", sessionId)
                    put("source", source)
                    put("restoredQuota", summary)
                    put("triggerCount", eventCount)
                    put("detailZh", detailZh)
                    put("detailEn", detailEn)
                }
                if (isInModuleProcess(context)) {
                    appendDevEventLocal(context.applicationContext ?: context, obj.toString(), false, false)
                    HookTelemetryReceiver.notifyUiListeners()
                } else {
                    val extras = Bundle().apply {
                        putString(KEY_DEV_EVENT_JSON, obj.toString())
                    }
                    context.contentResolver.call(AUTHORITY_URI, METHOD_APPEND_DEV_EVENT, null, extras)
                }
            } catch (_: Throwable) {
            }
        }

        @Synchronized
        internal fun bumpForceScanSeqLocal(appCtx: Context): UnifiedState {
            val prev = ensureLoaded(appCtx)
            val nextSeq = maxOf(prev.forceScanSeq + 1L, System.currentTimeMillis())
            val next = prev.copy(forceScanSeq = nextSeq)
            cachedState.set(next)
            try {
                appCtx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_FORCE_SCAN_SEQ, nextSeq)
                    .apply()
            } catch (_: Throwable) {
            }
            try {
                appCtx.contentResolver.notifyChange(STATE_URI, null)
            } catch (_: Throwable) {
            }
            return next
        }

        fun requestFullProtocolScan(context: Context): UnifiedState? {
            return try {
                if (isInModuleProcess(context)) {
                    val updated = bumpForceScanSeqLocal(context.applicationContext ?: context)
                    HookTelemetryReceiver.notifyUiListeners()
                    updated
                } else {
                    val reply = context.contentResolver.call(AUTHORITY_URI, METHOD_REQUEST_FULL_SCAN, null, null)
                    UnifiedState.fromBundle(reply)
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun clearDevConsoleLogs(context: Context) {
            try {
                if (isInModuleProcess(context)) {
                    clearDevEventsLocal(context.applicationContext ?: context)
                    HookTelemetryReceiver.notifyUiListeners()
                } else {
                    context.contentResolver.call(AUTHORITY_URI, METHOD_CLEAR_DEV_EVENTS, null, null)
                }
            } catch (_: Throwable) {
            }
        }

        @Synchronized
        internal fun resetTrendAlertHistoryLocal(appCtx: Context): UnifiedState {
            val prev = ensureLoaded(appCtx)
            val nextSeq = maxOf(prev.resetTrendSeq + 1L, System.currentTimeMillis())
            val nextEnergyJson = try {
                val obj = JSONObject(prev.energyStatsJson.ifBlank { "{}" })
                obj.put("highUsageTrendActive", false)
                obj.put("trendConsecutiveIncreases", 0)
                obj.put("trendWindowDelta", 0.0)
                obj.put("lastTrendAlertAtMs", 0L)
                obj.toString()
            } catch (_: Throwable) {
                prev.energyStatsJson
            }
            val next = prev.copy(
                resetTrendSeq = nextSeq,
                energyStatsJson = nextEnergyJson
            )
            cachedState.set(next)
            try {
                appCtx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_RESET_TREND_SEQ, nextSeq)
                    .putString(KEY_ENERGY_STATS_JSON, nextEnergyJson)
                    .apply()
            } catch (_: Throwable) {
            }
            try {
                appCtx.contentResolver.notifyChange(STATE_URI, null)
            } catch (_: Throwable) {
            }
            return next
        }

        fun resetTrendAlertHistory(context: Context): UnifiedState? {
            return try {
                if (isInModuleProcess(context)) {
                    val updated = resetTrendAlertHistoryLocal(context.applicationContext ?: context)
                    HookTelemetryReceiver.notifyUiListeners()
                    updated
                } else {
                    val reply = context.contentResolver.call(AUTHORITY_URI, METHOD_RESET_TREND_HISTORY, null, null)
                    UnifiedState.fromBundle(reply)
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
