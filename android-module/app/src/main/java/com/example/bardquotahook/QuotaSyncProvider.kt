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
import java.util.concurrent.atomic.AtomicReference

/**
 * Single Authoritative Cross-Process Data Source (`content://com.example.bardquotahook.provider`).
 *
 * Unifies strictly in-app captured quota values (`5h`, `5h reset`, `Weekly`, `Weekly reset`),
 * i18n language (`ZH` / `EN`), theme mode (`MIUIX` / `MD3`), and `ModernQuotaModule` (API 102)
 * Single-HUD Exclusion state across:
 * 1. Gemini's in-app Xposed `QuotaHudView` (`com.google.android.apps.bard` UI & service processes)
 * 2. Module Settings live preview `QuotaHudView` (`MainActivity`)
 * 3. Passive `OverlayHudController` (auto-suppressed whenever Xposed binds)
 */
class QuotaSyncProvider : ContentProvider() {

    data class UnifiedState(
        val snapshot: QuotaHudView.Snapshot = QuotaHudView.Snapshot(),
        val lang: QuotaHudView.Lang = QuotaHudView.Lang.ZH,
        val theme: QuotaHudView.ThemeMode = QuotaHudView.ThemeMode.MIUIX,
        val xposedBound: Boolean = false,
        val isApi102: Boolean = false,
        val lastHeartbeatMs: Long = 0L,
        val lastQuotaMs: Long = 0L,
        val lastPkg: String = "",
        val lastProc: String = "",
        val lastBridge: String = "",
        val overlayEnabled: Boolean = false
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
                putString(KEY_LANG, lang.code)
                putString(KEY_THEME, theme.code)
                putBoolean(KEY_XPOSED_BOUND, isActivelyBound)
                putBoolean(KEY_API_102, isApi102)
                putLong(KEY_LAST_HB_MS, lastHeartbeatMs)
                putLong(KEY_LAST_QUOTA_MS, lastQuotaMs)
                putString(KEY_LAST_PKG, lastPkg)
                putString(KEY_LAST_PROC, lastProc)
                putString(KEY_LAST_BRIDGE, lastBridge)
                putBoolean(KEY_OVERLAY_ENABLED, overlayEnabled && !isActivelyBound)
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
                    source = bundle.getString(KEY_SRC, "UnifiedStore") ?: "UnifiedStore"
                ).sanitized()
                return UnifiedState(
                    snapshot = snap,
                    lang = QuotaHudView.Lang.fromCode(bundle.getString(KEY_LANG, "ZH")),
                    theme = QuotaHudView.ThemeMode.fromCode(bundle.getString(KEY_THEME, "MIUIX")),
                    xposedBound = bundle.getBoolean(KEY_XPOSED_BOUND, false),
                    isApi102 = bundle.getBoolean(KEY_API_102, false),
                    lastHeartbeatMs = bundle.getLong(KEY_LAST_HB_MS, 0L),
                    lastQuotaMs = bundle.getLong(KEY_LAST_QUOTA_MS, 0L),
                    lastPkg = bundle.getString(KEY_LAST_PKG, "") ?: "",
                    lastProc = bundle.getString(KEY_LAST_PROC, "") ?: "",
                    lastBridge = bundle.getString(KEY_LAST_BRIDGE, "") ?: "",
                    overlayEnabled = bundle.getBoolean(KEY_OVERLAY_ENABLED, false)
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
                        source = extras?.getString(KEY_SRC, "InAppHook") ?: "InAppHook"
                    ).sanitized()
                    val updated = mutateLocalStore(appCtx, incomingSnapshot = incoming)
                    dispatchLocalSideEffects(appCtx, updated)
                    updated.toBundle()
                }

                METHOD_PUT_PREFS -> {
                    val lang = extras?.getString(KEY_LANG)?.let { QuotaHudView.Lang.fromCode(it) }
                    val theme = extras?.getString(KEY_THEME)?.let { QuotaHudView.ThemeMode.fromCode(it) }
                    val overlayFlag = if (extras?.containsKey(KEY_OVERLAY_ENABLED) == true) {
                        extras.getBoolean(KEY_OVERLAY_ENABLED)
                    } else {
                        null
                    }
                    val updated = mutateLocalStore(
                        appCtx = appCtx,
                        lang = lang,
                        theme = theme,
                        overlayEnabled = overlayFlag
                    )
                    dispatchLocalSideEffects(appCtx, updated)
                    updated.toBundle()
                }

                METHOD_HEARTBEAT -> {
                    val pkg = extras?.getString(KEY_LAST_PKG) ?: arg ?: ""
                    val proc = extras?.getString(KEY_LAST_PROC) ?: ""
                    val bridge = extras?.getString(KEY_LAST_BRIDGE) ?: "Xposed"
                    val api102 = extras?.getBoolean(KEY_API_102, false) ?: false
                    val candidate = QuotaHudView.Snapshot(
                        fiveHourQuota = extras?.getString(KEY_FIVE, "--") ?: "--",
                        fiveHourReset = extras?.getString(KEY_FIVE_RESET, "--") ?: "--",
                        weeklyLimit = extras?.getString(KEY_WEEK, "--") ?: "--",
                        weeklyReset = extras?.getString(KEY_WEEK_RESET, "--") ?: "--",
                        source = extras?.getString(KEY_SRC, "InAppHook") ?: "InAppHook"
                    ).sanitized()

                    val updated = mutateLocalStore(
                        appCtx = appCtx,
                        incomingSnapshot = if (candidate.hasLiveData) candidate else null,
                        pkg = pkg,
                        proc = proc,
                        bridge = bridge,
                        isApi102 = api102,
                        markXposedBound = true
                    )

                    // Single-HUD Exclusion: immediately suppress App System Overlay when Xposed binds
                    OverlayHudController.enforceSingleHudExclusivity(appCtx)
                    dispatchLocalSideEffects(appCtx, updated)
                    updated.toBundle()
                }

                METHOD_REQUEST_REFRESH -> {
                    val current = ensureLoaded(appCtx)
                    HookTelemetryReceiver.syncStateToHostApps(
                        context = appCtx,
                        state = current,
                        includePing = true,
                        triggerInAppScan = true
                    )
                    current.toBundle()
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
        const val METHOD_REQUEST_REFRESH = "request_refresh"

        const val KEY_FIVE = "five"
        const val KEY_FIVE_RESET = "five_reset"
        const val KEY_WEEK = "week"
        const val KEY_WEEK_RESET = "week_reset"
        const val KEY_SRC = "src"
        const val KEY_LANG = "ui_lang"
        const val KEY_THEME = "ui_theme"
        const val KEY_XPOSED_BOUND = "xposed_bound"
        const val KEY_API_102 = "api_102_bound"
        const val KEY_LAST_HB_MS = "last_hb_ms"
        const val KEY_LAST_QUOTA_MS = "last_quota_ms"
        const val KEY_LAST_PKG = "last_pkg"
        const val KEY_LAST_PROC = "last_proc"
        const val KEY_LAST_BRIDGE = "last_bridge"
        const val KEY_OVERLAY_ENABLED = "overlay_enabled"

        private const val ACTIVE_HOOK_WINDOW_MS = 12 * 60 * 60 * 1000L

        private val stateLock = Any()
        private val cachedState = AtomicReference<UnifiedState?>(null)

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

                val rawFive = prefs.getString(KEY_FIVE, "--") ?: "--"
                val rawFiveReset = prefs.getString(KEY_FIVE_RESET, null)
                    ?: prefs.getString("reset", "--")
                    ?: "--"
                val rawWeek = prefs.getString(KEY_WEEK, "--") ?: "--"
                val rawWeekReset = prefs.getString(KEY_WEEK_RESET, "--") ?: "--"
                val rawSrc = prefs.getString(KEY_SRC, "UnifiedStore") ?: "UnifiedStore"

                val cleanSnap = QuotaHudView.Snapshot(
                    fiveHourQuota = rawFive,
                    fiveHourReset = rawFiveReset,
                    weeklyLimit = rawWeek,
                    weeklyReset = rawWeekReset,
                    source = rawSrc
                ).sanitized()

                if (cleanSnap.fiveHourQuota != rawFive ||
                    cleanSnap.fiveHourReset != rawFiveReset ||
                    cleanSnap.weeklyLimit != rawWeek ||
                    cleanSnap.weeklyReset != rawWeekReset
                ) {
                    try {
                        prefs.edit()
                            .putString(KEY_FIVE, cleanSnap.fiveHourQuota)
                            .putString(KEY_FIVE_RESET, cleanSnap.fiveHourReset)
                            .putString(KEY_WEEK, cleanSnap.weeklyLimit)
                            .putString(KEY_WEEK_RESET, cleanSnap.weeklyReset)
                            .remove("reset")
                            .apply()
                    } catch (_: Throwable) {
                    }
                }

                val loaded = UnifiedState(
                    snapshot = cleanSnap,
                    lang = QuotaHudView.Lang.fromCode(prefs.getString(KEY_LANG, "ZH")),
                    theme = QuotaHudView.ThemeMode.fromCode(prefs.getString(KEY_THEME, "MIUIX")),
                    xposedBound = prefs.getBoolean(KEY_XPOSED_BOUND, false),
                    isApi102 = prefs.getBoolean(KEY_API_102, false),
                    lastHeartbeatMs = prefs.getLong(KEY_LAST_HB_MS, 0L),
                    lastQuotaMs = prefs.getLong(KEY_LAST_QUOTA_MS, 0L),
                    lastPkg = prefs.getString(KEY_LAST_PKG, "") ?: "",
                    lastProc = prefs.getString(KEY_LAST_PROC, "") ?: "",
                    lastBridge = prefs.getString(KEY_LAST_BRIDGE, "") ?: "",
                    overlayEnabled = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)
                )
                cachedState.set(loaded)
                return loaded
            }
        }

        @Synchronized
        internal fun mutateLocalStore(
            appCtx: Context,
            incomingSnapshot: QuotaHudView.Snapshot? = null,
            lang: QuotaHudView.Lang? = null,
            theme: QuotaHudView.ThemeMode? = null,
            pkg: String? = null,
            proc: String? = null,
            bridge: String? = null,
            isApi102: Boolean? = null,
            markXposedBound: Boolean = false,
            overlayEnabled: Boolean? = null
        ): UnifiedState {
            val prev = ensureLoaded(appCtx)
            val now = System.currentTimeMillis()

            val cleanIncoming = incomingSnapshot?.sanitized()
            val mergedSnap = if (cleanIncoming != null && cleanIncoming.hasLiveData) {
                QuotaHudView.Snapshot(
                    fiveHourQuota = if (cleanIncoming.fiveHourQuota != "--") cleanIncoming.fiveHourQuota else prev.snapshot.fiveHourQuota,
                    fiveHourReset = if (cleanIncoming.fiveHourReset != "--") cleanIncoming.fiveHourReset else prev.snapshot.fiveHourReset,
                    weeklyLimit = if (cleanIncoming.weeklyLimit != "--") cleanIncoming.weeklyLimit else prev.snapshot.weeklyLimit,
                    weeklyReset = if (cleanIncoming.weeklyReset != "--") cleanIncoming.weeklyReset else prev.snapshot.weeklyReset,
                    source = cleanIncoming.source
                ).sanitized()
            } else {
                prev.snapshot
            }

            val nextBound = if (markXposedBound) true else prev.xposedBound
            val nextHbMs = if (markXposedBound) now else prev.lastHeartbeatMs
            val nextQuotaMs = if (cleanIncoming != null && cleanIncoming.hasLiveData && mergedSnap != prev.snapshot) {
                now
            } else {
                prev.lastQuotaMs
            }
            val nextOverlay = when {
                markXposedBound -> false
                overlayEnabled != null -> overlayEnabled
                else -> prev.overlayEnabled
            }

            val next = prev.copy(
                snapshot = mergedSnap,
                lang = lang ?: prev.lang,
                theme = theme ?: prev.theme,
                xposedBound = nextBound,
                isApi102 = isApi102 ?: prev.isApi102,
                lastHeartbeatMs = nextHbMs,
                lastQuotaMs = nextQuotaMs,
                lastPkg = if (!pkg.isNullOrBlank()) pkg else prev.lastPkg,
                lastProc = if (!proc.isNullOrBlank()) proc else prev.lastProc,
                lastBridge = if (!bridge.isNullOrBlank()) bridge else prev.lastBridge,
                overlayEnabled = nextOverlay
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
                    .putString(KEY_LANG, next.lang.code)
                    .putString(KEY_THEME, next.theme.code)
                    .putBoolean(KEY_XPOSED_BOUND, next.xposedBound)
                    .putBoolean(KEY_API_102, next.isApi102)
                    .putLong(KEY_LAST_HB_MS, next.lastHeartbeatMs)
                    .putLong(KEY_LAST_QUOTA_MS, next.lastQuotaMs)
                    .putString(KEY_LAST_PKG, next.lastPkg)
                    .putString(KEY_LAST_PROC, next.lastProc)
                    .putString(KEY_LAST_BRIDGE, next.lastBridge)
                    .putBoolean(KEY_OVERLAY_ENABLED, next.overlayEnabled)
                    .apply()
            } catch (_: Throwable) {
            }

            try {
                appCtx.contentResolver.notifyChange(STATE_URI, null)
            } catch (_: Throwable) {
            }

            return next
        }

        private fun dispatchLocalSideEffects(appCtx: Context, state: UnifiedState) {
            OverlayHudController.updateFromUnifiedState(appCtx, state)
            HookTelemetryReceiver.syncStateToHostApps(appCtx, state, includePing = false)
            HookTelemetryReceiver.notifyUiListeners()
        }

        // =====================================================================
        // Cross-Process Client API (Callable from BOTH Module & Hooked Gemini)
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
            localSnapshot: QuotaHudView.Snapshot
        ): UnifiedState? {
            return try {
                val clean = localSnapshot.sanitized()
                val extras = Bundle().apply {
                    putString(KEY_LAST_PKG, pkg)
                    putString(KEY_LAST_PROC, proc)
                    putString(KEY_LAST_BRIDGE, bridge)
                    putBoolean(KEY_API_102, isApi102)
                    if (clean.hasLiveData) {
                        putString(KEY_FIVE, clean.fiveHourQuota)
                        putString(KEY_FIVE_RESET, clean.fiveHourReset)
                        putString(KEY_WEEK, clean.weeklyLimit)
                        putString(KEY_WEEK_RESET, clean.weeklyReset)
                        putString(KEY_SRC, clean.source)
                    }
                }
                val reply = context.contentResolver.call(AUTHORITY_URI, METHOD_HEARTBEAT, pkg, extras)
                UnifiedState.fromBundle(reply)
            } catch (_: Throwable) {
                null
            }
        }

        fun publishQuota(context: Context, snapshot: QuotaHudView.Snapshot): UnifiedState? {
            val clean = snapshot.sanitized()
            if (!clean.hasLiveData) return readUnifiedState(context)
            return try {
                if (isInModuleProcess(context)) {
                    val appCtx = context.applicationContext ?: context
                    val updated = mutateLocalStore(appCtx, incomingSnapshot = clean)
                    dispatchLocalSideEffects(appCtx, updated)
                    updated
                } else {
                    val extras = Bundle().apply {
                        putString(KEY_FIVE, clean.fiveHourQuota)
                        putString(KEY_FIVE_RESET, clean.fiveHourReset)
                        putString(KEY_WEEK, clean.weeklyLimit)
                        putString(KEY_WEEK_RESET, clean.weeklyReset)
                        putString(KEY_SRC, clean.source)
                    }
                    val reply = context.contentResolver.call(AUTHORITY_URI, METHOD_PUT_QUOTA, null, extras)
                    UnifiedState.fromBundle(reply)
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun updatePreferences(
            context: Context,
            lang: QuotaHudView.Lang? = null,
            theme: QuotaHudView.ThemeMode? = null,
            overlayEnabled: Boolean? = null
        ): UnifiedState? {
            return try {
                if (isInModuleProcess(context)) {
                    val appCtx = context.applicationContext ?: context
                    val updated = mutateLocalStore(
                        appCtx = appCtx,
                        lang = lang,
                        theme = theme,
                        overlayEnabled = overlayEnabled
                    )
                    dispatchLocalSideEffects(appCtx, updated)
                    updated
                } else {
                    val extras = Bundle().apply {
                        if (lang != null) putString(KEY_LANG, lang.code)
                        if (theme != null) putString(KEY_THEME, theme.code)
                        if (overlayEnabled != null) putBoolean(KEY_OVERLAY_ENABLED, overlayEnabled)
                    }
                    val reply = context.contentResolver.call(AUTHORITY_URI, METHOD_PUT_PREFS, null, extras)
                    UnifiedState.fromBundle(reply)
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun requestInProcessRefresh(context: Context, requesterPkg: String) {
            try {
                if (isInModuleProcess(context)) {
                    val appCtx = context.applicationContext ?: context
                    val st = ensureLoaded(appCtx)
                    HookTelemetryReceiver.syncStateToHostApps(
                        context = appCtx,
                        state = st,
                        includePing = true,
                        triggerInAppScan = true
                    )
                } else {
                    val extras = Bundle().apply {
                        putString("requester_pkg", requesterPkg)
                    }
                    context.contentResolver.call(AUTHORITY_URI, METHOD_REQUEST_REFRESH, requesterPkg, extras)
                }
            } catch (_: Throwable) {
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
    }
}
