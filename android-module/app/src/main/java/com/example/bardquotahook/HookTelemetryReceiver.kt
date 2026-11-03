package com.example.bardquotahook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

class HookTelemetryReceiver : BroadcastReceiver() {

    data class AppUiPrefs(
        val lang: QuotaHudView.Lang = QuotaHudView.Lang.ZH,
        val theme: QuotaHudView.ThemeMode = QuotaHudView.ThemeMode.MIUIX,
        val xposedBound: Boolean = false,
        val isApi102: Boolean = false,
        val lastHeartbeatMs: Long = 0L,
        val lastPkg: String = "",
        val lastBridge: String = ""
    )

    companion object {
        // Consider Xposed actively bound if Gemini sent a heartbeat within the current session window
        private const val ACTIVE_HOOK_WINDOW_MS = 12 * 60 * 60 * 1000L

        private val uiListenerRef = AtomicReference<WeakReference<() -> Unit>>(WeakReference(null))

        fun setUiListener(listener: (() -> Unit)?) {
            uiListenerRef.set(WeakReference(listener))
        }

        fun notifyUiListeners() {
            uiListenerRef.get().get()?.invoke()
        }

        fun isXposedActivelyBound(context: Context): Boolean {
            return try {
                val prefs = context.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                val boundFlag = prefs.getBoolean("xposed_bound", false)
                val lastHb = prefs.getLong("last_hb_ms", 0L)
                boundFlag && lastHb > 0L && (System.currentTimeMillis() - lastHb) in 0L..ACTIVE_HOOK_WINDOW_MS
            } catch (_: Throwable) {
                false
            }
        }

        fun loadUiPrefs(context: Context): AppUiPrefs {
            return try {
                val prefs = context.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                val lastHb = prefs.getLong("last_hb_ms", 0L)
                val active = isXposedActivelyBound(context)
                AppUiPrefs(
                    lang = QuotaHudView.Lang.fromCode(prefs.getString("ui_lang", "ZH")),
                    theme = QuotaHudView.ThemeMode.fromCode(prefs.getString("ui_theme", "MIUIX")),
                    xposedBound = active,
                    isApi102 = prefs.getBoolean("api_102_bound", false),
                    lastHeartbeatMs = lastHb,
                    lastPkg = prefs.getString("last_pkg", "") ?: "",
                    lastBridge = prefs.getString("last_bridge", "") ?: ""
                )
            } catch (_: Throwable) {
                AppUiPrefs()
            }
        }

        fun saveUiPrefs(
            context: Context,
            lang: QuotaHudView.Lang? = null,
            theme: QuotaHudView.ThemeMode? = null
        ) {
            try {
                val prefs = context.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                if (lang != null) editor.putString("ui_lang", lang.code)
                if (theme != null) editor.putString("ui_theme", theme.code)
                editor.apply()
            } catch (_: Throwable) {
            }
            OverlayHudController.applySavedPreferences(context)
            syncStateToHostApps(context, includePing = false)
            notifyUiListeners()
        }

        fun saveSnapshot(
            context: Context,
            snapshot: QuotaHudView.Snapshot,
            pkg: String? = null,
            proc: String? = null,
            bridge: String? = null,
            isApi102: Boolean? = null
        ) {
            try {
                val prefs = context.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                if (pkg != null) {
                    editor.putString("last_pkg", pkg)
                    editor.putLong("last_hb_ms", System.currentTimeMillis())
                    editor.putBoolean("xposed_bound", true)
                    editor.putBoolean("overlay_enabled", false)
                }
                if (proc != null) editor.putString("last_proc", proc)
                if (bridge != null) editor.putString("last_bridge", bridge)
                if (isApi102 != null) editor.putBoolean("api_102_bound", isApi102)

                if (snapshot.hasLiveData) {
                    if (snapshot.fiveHourQuota != "--") {
                        editor.putString("five", snapshot.fiveHourQuota)
                    }
                    if (snapshot.fiveHourReset != "--") {
                        editor.putString("five_reset", snapshot.fiveHourReset)
                    }
                    if (snapshot.weeklyLimit != "--") {
                        editor.putString("week", snapshot.weeklyLimit)
                    }
                    if (snapshot.weeklyReset != "--") {
                        editor.putString("week_reset", snapshot.weeklyReset)
                    }
                    editor.putString("src", snapshot.source)
                    editor.putLong("last_quota_ms", System.currentTimeMillis())
                }
                editor.apply()
            } catch (_: Throwable) {
            }
        }

        fun loadSnapshot(context: Context): QuotaHudView.Snapshot {
            return try {
                val prefs = context.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                QuotaHudView.Snapshot(
                    fiveHourQuota = prefs.getString("five", "--") ?: "--",
                    fiveHourReset = prefs.getString("five_reset", null)
                        ?: prefs.getString("reset", "--")
                        ?: "--",
                    weeklyLimit = prefs.getString("week", "--") ?: "--",
                    weeklyReset = prefs.getString("week_reset", "--") ?: "--",
                    source = prefs.getString("src", "SharedPrefs") ?: "SharedPrefs"
                )
            } catch (_: Throwable) {
                QuotaHudView.Snapshot()
            }
        }

        fun pushExternalQuotaToHostApps(context: Context, snapshot: QuotaHudView.Snapshot) {
            saveSnapshot(context, snapshot)
            OverlayHudController.updateSnapshot(context, loadSnapshot(context))
            syncStateToHostApps(context, includePing = false)
            notifyUiListeners()
        }

        fun syncStateToHostApps(context: Context, includePing: Boolean) {
            val snap = loadSnapshot(context)
            val ui = loadUiPrefs(context)
            val targets = arrayOf(
                "com.google.android.apps.bard",
                "com.google.android.googlequicksearchbox"
            )
            for (pkg in targets) {
                try {
                    val intent = Intent(QuotaTextParser.ACTION_QUOTA_EXTERNAL_SYNC).apply {
                        setPackage(pkg)
                        putExtra("five", snap.fiveHourQuota)
                        putExtra("five_reset", snap.fiveHourReset)
                        putExtra("week", snap.weeklyLimit)
                        putExtra("week_reset", snap.weeklyReset)
                        putExtra("src", snap.source)
                        putExtra("ui_lang", ui.lang.code)
                        putExtra("ui_theme", ui.theme.code)
                        if (includePing) putExtra("ping", true)
                    }
                    context.sendBroadcast(intent)
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val act = intent.action ?: return

        try {
            when (act) {
                QuotaTextParser.ACTION_HOOK_HEARTBEAT -> {
                    val pkg = intent.getStringExtra("pkg") ?: ""
                    val proc = intent.getStringExtra("proc") ?: ""
                    val bridge = intent.getStringExtra("bridge") ?: "Xposed"
                    val isApi102 = intent.getBooleanExtra("api_102", false)
                    val five = intent.getStringExtra("five") ?: "--"
                    val fiveReset = intent.getStringExtra("five_reset")
                        ?: intent.getStringExtra("reset")
                        ?: "--"
                    val week = intent.getStringExtra("week") ?: "--"
                    val weekReset = intent.getStringExtra("week_reset") ?: "--"
                    val src = intent.getStringExtra("src") ?: "Hook"

                    val incoming = QuotaHudView.Snapshot(
                        fiveHourQuota = five,
                        fiveHourReset = fiveReset,
                        weeklyLimit = week,
                        weeklyReset = weekReset,
                        source = src
                    )
                    saveSnapshot(
                        context = context,
                        snapshot = incoming,
                        pkg = pkg,
                        proc = proc,
                        bridge = bridge,
                        isApi102 = isApi102
                    )

                    // Single-HUD Exclusion: Automatically disable App System Overlay when Xposed binds to Gemini
                    OverlayHudController.enforceSingleHudExclusivity(context)

                    // Reply with authoritative SharedPreferences quota + i18n + ThemeMode
                    syncStateToHostApps(context, includePing = false)
                    notifyUiListeners()
                }

                QuotaTextParser.ACTION_REQUEST_QUOTA_REFRESH -> {
                    // Triggered by Refresh Icon Button on in-app QuotaHudView
                    OverlayHudController.triggerHeadlessSync(context)
                }
            }
        } catch (_: Throwable) {
        }
    }
}
