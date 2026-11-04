package com.example.bardquotahook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * Broadcast bridge that coordinates cross-process heartbeats and in-process memory scan triggers
 * against the authoritative `QuotaSyncProvider` store (zero web/HTTP fallback).
 */
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
        private val uiListenerRef = AtomicReference<WeakReference<() -> Unit>>(WeakReference(null))

        fun setUiListener(listener: (() -> Unit)?) {
            uiListenerRef.set(WeakReference(listener))
        }

        fun notifyUiListeners() {
            uiListenerRef.get().get()?.invoke()
        }

        fun isXposedActivelyBound(context: Context): Boolean {
            return QuotaSyncProvider.ensureLoaded(context).isActivelyBound
        }

        fun loadUiPrefs(context: Context): AppUiPrefs {
            return try {
                val st = QuotaSyncProvider.ensureLoaded(context)
                AppUiPrefs(
                    lang = st.lang,
                    theme = st.theme,
                    xposedBound = st.isActivelyBound,
                    isApi102 = st.isApi102,
                    lastHeartbeatMs = st.lastHeartbeatMs,
                    lastPkg = st.lastPkg,
                    lastBridge = st.lastBridge
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
            QuotaSyncProvider.updatePreferences(
                context = context,
                lang = lang,
                theme = theme
            )
        }

        fun loadSnapshot(context: Context): QuotaHudView.Snapshot {
            return try {
                QuotaSyncProvider.ensureLoaded(context).snapshot.sanitized()
            } catch (_: Throwable) {
                QuotaHudView.Snapshot()
            }
        }

        fun syncStateToHostApps(
            context: Context,
            state: QuotaSyncProvider.UnifiedState? = null,
            includePing: Boolean = false,
            triggerInAppScan: Boolean = false
        ) {
            val st = state ?: QuotaSyncProvider.ensureLoaded(context)
            val snap = st.snapshot.sanitized()
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
                        putExtra("ui_lang", st.lang.code)
                        putExtra("ui_theme", st.theme.code)
                        if (includePing) putExtra("ping", true)
                        if (triggerInAppScan) putExtra("rescan_memory", true)
                    }
                    context.sendBroadcast(intent)
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val appCtx = context.applicationContext ?: context
        val act = intent.action ?: return

        try {
            when (act) {
                QuotaTextParser.ACTION_HOOK_HEARTBEAT -> {
                    val pkg = intent.getStringExtra("pkg") ?: ""
                    val proc = intent.getStringExtra("proc") ?: ""
                    val bridge = intent.getStringExtra("bridge") ?: "Xposed"
                    val isApi102 = intent.getBooleanExtra("api_102", false)
                    val incoming = QuotaHudView.Snapshot(
                        fiveHourQuota = intent.getStringExtra("five") ?: "--",
                        fiveHourReset = intent.getStringExtra("five_reset")
                            ?: intent.getStringExtra("reset")
                            ?: "--",
                        weeklyLimit = intent.getStringExtra("week") ?: "--",
                        weeklyReset = intent.getStringExtra("week_reset") ?: "--",
                        source = intent.getStringExtra("src") ?: "InAppHook"
                    ).sanitized()

                    val updated = QuotaSyncProvider.mutateLocalStore(
                        appCtx = appCtx,
                        incomingSnapshot = if (incoming.hasLiveData) incoming else null,
                        pkg = pkg,
                        proc = proc,
                        bridge = bridge,
                        isApi102 = isApi102,
                        markXposedBound = true
                    )

                    OverlayHudController.enforceSingleHudExclusivity(appCtx)
                    syncStateToHostApps(appCtx, updated, includePing = false)
                    notifyUiListeners()
                }

                QuotaTextParser.ACTION_REQUEST_QUOTA_REFRESH -> {
                    syncStateToHostApps(
                        context = appCtx,
                        includePing = true,
                        triggerInAppScan = true
                    )
                }
            }
        } catch (_: Throwable) {
        }
    }
}
