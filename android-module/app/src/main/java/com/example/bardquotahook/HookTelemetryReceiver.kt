package com.example.bardquotahook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

class HookTelemetryReceiver : BroadcastReceiver() {

    companion object {
        private val uiListenerRef = AtomicReference<WeakReference<() -> Unit>>(WeakReference(null))

        fun setUiListener(listener: (() -> Unit)?) {
            uiListenerRef.set(WeakReference(listener))
        }

        fun saveSnapshot(
            context: Context,
            snapshot: QuotaHudLayout.Snapshot,
            pkg: String? = null,
            proc: String? = null,
            bridge: String? = null
        ) {
            try {
                val prefs = context.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                if (pkg != null) {
                    editor.putString("last_pkg", pkg)
                    editor.putLong("last_hb_ms", System.currentTimeMillis())
                }
                if (proc != null) editor.putString("last_proc", proc)
                if (bridge != null) editor.putString("last_bridge", bridge)
                if (snapshot.hasLiveData) {
                    editor.putString("five", snapshot.fiveHourQuota)
                    editor.putString("week", snapshot.weeklyLimit)
                    editor.putString("reset", snapshot.resetTimer)
                    editor.putString("src", snapshot.source)
                    editor.putLong("last_quota_ms", System.currentTimeMillis())
                }
                editor.apply()
            } catch (_: Throwable) {
            }
        }

        fun loadSnapshot(context: Context): QuotaHudLayout.Snapshot {
            return try {
                val prefs = context.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                QuotaHudLayout.Snapshot(
                    fiveHourQuota = prefs.getString("five", "--") ?: "--",
                    weeklyLimit = prefs.getString("week", "--") ?: "--",
                    resetTimer = prefs.getString("reset", "--") ?: "--",
                    source = prefs.getString("src", "init") ?: "init"
                )
            } catch (_: Throwable) {
                QuotaHudLayout.Snapshot()
            }
        }

        fun pushExternalQuotaToHostApps(context: Context, snapshot: QuotaHudLayout.Snapshot) {
            saveSnapshot(context, snapshot)
            OverlayHudController.updateSnapshot(context, snapshot)

            val targets = arrayOf(
                "com.google.android.apps.bard",
                "com.google.android.googlequicksearchbox"
            )
            for (pkg in targets) {
                try {
                    val intent = Intent(QuotaTextParser.ACTION_QUOTA_EXTERNAL_SYNC).apply {
                        setPackage(pkg)
                        putExtra("five", snapshot.fiveHourQuota)
                        putExtra("week", snapshot.weeklyLimit)
                        putExtra("reset", snapshot.resetTimer)
                        putExtra("src", snapshot.source)
                    }
                    context.sendBroadcast(intent)
                } catch (_: Throwable) {
                }
            }
            uiListenerRef.get().get()?.invoke()
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != QuotaTextParser.ACTION_HOOK_HEARTBEAT) return
        try {
            val pkg = intent.getStringExtra("pkg") ?: ""
            val proc = intent.getStringExtra("proc") ?: ""
            val bridge = intent.getStringExtra("bridge") ?: "Xposed"
            val five = intent.getStringExtra("five") ?: "--"
            val week = intent.getStringExtra("week") ?: "--"
            val reset = intent.getStringExtra("reset") ?: "--"
            val src = intent.getStringExtra("src") ?: "Hook"

            val incoming = QuotaHudLayout.Snapshot(five, week, reset, src)
            saveSnapshot(context, incoming, pkg = pkg, proc = proc, bridge = bridge)
            if (incoming.hasLiveData) {
                OverlayHudController.updateSnapshot(context, incoming)
            }
            uiListenerRef.get().get()?.invoke()
        } catch (_: Throwable) {
        }
    }
}
