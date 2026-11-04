package com.example.bardquotahook

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Framework-agnostic Quota Engine shared by both:
 * - `ModernQuotaModule` (`io.github.libxposed.api.XposedModule`, API 102)
 * - `QuotaHookEntry` (`de.robv.android.xposed.IXposedHookLoadPackage`, API 82)
 *
 * Connected directly to the single authoritative `QuotaSyncProvider` (`content://com.example.bardquotahook.provider`)
 * so the in-app Gemini `QuotaHudView` and the module's HUD always display 100% identical real-time values.
 */
object QuotaCoreEngine {

    interface HookBridge {
        val bridgeName: String
        val isModernApi102: Boolean get() = false
        fun log(msg: String)
        fun openRemotePreferences(group: String): SharedPreferences? = null
        fun hookExecutable(
            executable: Executable,
            before: ((thisObj: Any?, args: Array<Any?>) -> Unit)? = null,
            after: ((thisObj: Any?, args: Array<Any?>, result: Any?) -> Unit)? = null
        ): (() -> Unit)?
    }

    private const val LOG_TAG = "BardQuotaHook"

    private val TARGET_PACKAGES = setOf(
        "com.google.android.apps.bard",
        "com.google.android.googlequicksearchbox"
    )

    private val installedGuard = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)
    private val observerRegistered = AtomicBoolean(false)
    private val lifecycleRegistered = AtomicBoolean(false)

    private val latestSnapshot = AtomicReference(QuotaHudView.Snapshot())
    private val currentLang = AtomicReference(QuotaHudView.Lang.ZH)
    private val currentTheme = AtomicReference(QuotaHudView.ThemeMode.MIUIX)

    private val activeHudRef = AtomicReference<WeakReference<QuotaHudView>>(WeakReference(null))
    private val activeActivityRef = AtomicReference<WeakReference<Activity>>(WeakReference(null))
    private val activeWebViewRef = AtomicReference<WeakReference<WebView>>(WeakReference(null))

    private val ipcExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bard-quota-sync").apply { isDaemon = true }
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var mainHandler: Handler? = null
    @Volatile private var providerObserver: ContentObserver? = null
    @Volatile private var hostPackageName: String = ""
    @Volatile private var hostProcessName: String = ""
    @Volatile private var activeBridgeName: String = ""
    @Volatile private var isModernApi102Bridge: Boolean = false
    @Volatile private var lastHeartbeatMs: Long = 0L

    private val inHookGuard = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private inline fun withReentrancyGuard(block: () -> Unit) {
        if (inHookGuard.get() == true) return
        inHookGuard.set(true)
        try {
            block()
        } catch (_: Throwable) {
        } finally {
            inHookGuard.set(false)
        }
    }

    private fun safeLog(bridge: HookBridge?, msg: String) {
        try {
            bridge?.log("[BardQuota] $msg")
        } catch (_: Throwable) {
        }
        try {
            Log.i(LOG_TAG, msg)
        } catch (_: Throwable) {
        }
    }

    fun install(
        packageName: String,
        processName: String,
        classLoader: ClassLoader?,
        bridge: HookBridge
    ) {
        val isTarget = packageName in TARGET_PACKAGES ||
            packageName.contains("bard", ignoreCase = true) ||
            packageName.contains("gemini", ignoreCase = true)
        if (!isTarget) return

        if (!installedGuard.compareAndSet(false, true)) return

        hostPackageName = packageName
        hostProcessName = processName
        activeBridgeName = bridge.bridgeName
        isModernApi102Bridge = bridge.isModernApi102

        safeLog(bridge, "Installing [${bridge.bridgeName}] into pkg=$packageName proc=$processName")

        // 1. Application, Unified QuotaSyncProvider Observer & Activity HUD Hooks
        hookApplicationAndActivities(bridge)

        // 2. 原生 UI 渲染层截获（直接 Hook TextView.setText 获取 App 内配额）
        hookTextViewSetText(bridge)

        // 3. Targeted Embedded Usage WebView Inspection (gemini.google.com/usage only)
        hookWebViewDom(bridge)

        // 4. Structured JSON Quota Inspection (strict schema keys + sanitized metrics only)
        hookStructuredJsonPayloads(bridge)
    }

    // =========================================================================
    // Reflection & Hook Safety Wrappers
    // =========================================================================

    private fun hookMethodSafe(
        bridge: HookBridge,
        clazz: Class<*>?,
        name: String,
        paramTypes: Array<Class<*>>,
        before: ((thisObj: Any?, args: Array<Any?>) -> Unit)? = null,
        after: ((thisObj: Any?, args: Array<Any?>, result: Any?) -> Unit)? = null
    ) {
        if (clazz == null) return
        try {
            val method = clazz.getDeclaredMethod(name, *paramTypes)
            method.isAccessible = true
            bridge.hookExecutable(method, before, after)
        } catch (_: Throwable) {
        }
    }

    private fun hookConstructorSafe(
        bridge: HookBridge,
        clazz: Class<*>?,
        paramTypes: Array<Class<*>>,
        after: (thisObj: Any?, args: Array<Any?>) -> Unit
    ) {
        if (clazz == null) return
        try {
            val ctor = clazz.getDeclaredConstructor(*paramTypes)
            ctor.isAccessible = true
            bridge.hookExecutable(
                executable = ctor,
                before = null,
                after = { thisObj, args, _ -> after(thisObj, args) }
            )
        } catch (_: Throwable) {
        }
    }

    // =========================================================================
    // Native TextView Hook Implementation (105% 编译通过安全版)
    // =========================================================================

    private fun hookTextViewSetText(bridge: HookBridge) {
        hookMethodSafe(
            bridge = bridge,
            clazz = TextView::class.java,
            name = "setText",
            paramTypes = arrayOf(CharSequence::class.java),
            after = { _, args, _ ->
                val text = args.firstOrNull() as? CharSequence ?: return@after
                val str = text.toString()

                // 检查是否包含额度/配额相关的中英文关键字
                if (str.contains("剩余") || str.contains("额度") || str.contains("Quota") || str.contains("Reset") || str.contains("remaining")) {
                    withReentrancyGuard {
                        val current = latestSnapshot.get()
                        val newSnap = QuotaHudView.Snapshot(
                            fiveHourQuota = str,
                            fiveHourReset = current.fiveHourReset,
                            weeklyLimit = current.weeklyLimit,
                            weeklyReset = current.weeklyReset,
                            source = "NativeTextView"
                        )
                        publishSnapshot(newSnap)
                    }
                }
            }
        )
    }

    // =========================================================================
    // 1. Unified Provider Sync, Application & Activity HUD Injection
    // =========================================================================

    private fun hookApplicationAndActivities(bridge: HookBridge) {
        hookMethodSafe(
            bridge = bridge,
            clazz = Application::class.java,
            name = "onCreate",
            paramTypes = emptyArray(),
            after = cb@{ thisObj, _, _ ->
                val app = thisObj as? Application ?: return@cb
                onContextCaptured(app)
                registerActivityCallbacksOnce(app)
            }
        )

        hookMethodSafe(
            bridge = bridge,
            clazz = Activity::class.java,
            name = "onResume",
            paramTypes = emptyArray(),
            after = cb@{ thisObj, _, _ ->
                val activity = thisObj as? Activity ?: return@cb
                val ctx = activity.applicationContext ?: activity
                onContextCaptured(ctx)
                if (shouldDisplayHudInActivity(activity)) {
                    activeActivityRef.set(WeakReference(activity))
                    attachOrUpdateOverlay(activity)
                    syncHeartbeatWithProviderAsync(ctx, force = true)
                    scheduleScreenBannerCheck()
                }
            }
        )
    }

    private fun registerActivityCallbacksOnce(app: Application) {
        if (!lifecycleRegistered.compareAndSet(false, true)) return
        try {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {
                    if (shouldDisplayHudInActivity(activity)) {
                        activeActivityRef.set(WeakReference(activity))
                        attachOrUpdateOverlay(activity)
                        syncHeartbeatWithProviderAsync(activity.applicationContext ?: activity, force = true)
                        scheduleScreenBannerCheck()
                    }
                }
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        } catch (_: Throwable) {
        }
    }

    private fun shouldDisplayHudInActivity(activity: Activity): Boolean {
        if (hostPackageName == "com.google.android.apps.bard") return true
        val clsName = activity.javaClass.name.lowercase()
        if (clsName.contains("bard") ||
            clsName.contains("robin") ||
            clsName.contains("assistant") ||
            clsName.contains("gemini") ||
            clsName.contains("webview") ||
            clsName.contains("customtab")
        ) {
            return true
        }
        val intentStr = try {
            activity.intent?.toString()?.lowercase() ?: ""
        } catch (_: Throwable) {
            ""
        }
        return intentStr.contains("bard") ||
            intentStr.contains("gemini") ||
            intentStr.contains("robin")
    }

    private fun onContextCaptured(ctx: Context) {
        if (appContext == null) {
            appContext = ctx.applicationContext ?: ctx
            loadSanitizedHostMirror(appContext)
        }
        val targetCtx = appContext ?: ctx
        registerUnifiedObserverOnce(targetCtx)
        registerReceiversOnce(targetCtx)
        syncHeartbeatWithProviderAsync(targetCtx, force = false)
    }

    private fun loadSanitizedHostMirror(ctx: Context?) {
        if (ctx == null) return
        try {
            val prefs = ctx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
            val langCode = prefs.getString("ui_lang", null)
            val themeCode = prefs.getString("ui_theme", null)
            if (langCode != null) currentLang.set(QuotaHudView.Lang.fromCode(langCode))
            if (themeCode != null) currentTheme.set(QuotaHudView.ThemeMode.fromCode(themeCode))

            val rawFive = prefs.getString("five", "--") ?: "--"
            val rawFiveReset = prefs.getString("five_reset", null)
                ?: prefs.getString("reset", "--")
                ?: "--"
            val rawWeek = prefs.getString("week", "--") ?: "--"
            val rawWeekReset = prefs.getString("week_reset", "--") ?: "--"
            val src = prefs.getString("src", "LocalCache") ?: "LocalCache"

            val clean = QuotaHudView.Snapshot(
                fiveHourQuota = rawFive,
                fiveHourReset = rawFiveReset,
                weeklyLimit = rawWeek,
                weeklyReset = rawWeekReset,
                source = src
            ).sanitized()

            if (clean.fiveHourQuota != rawFive ||
                clean.fiveHourReset != rawFiveReset ||
                clean.weeklyLimit != rawWeek ||
                clean.weeklyReset != rawWeekReset
            ) {
                prefs.edit()
                    .putString("five", clean.fiveHourQuota)
                    .putString("five_reset", clean.fiveHourReset)
                    .putString("week", clean.weeklyLimit)
                    .putString("week_reset", clean.weeklyReset)
                    .remove("reset")
                    .apply()
            }

            if (clean.hasLiveData) {
                latestSnapshot.set(clean)
            }
        } catch (_: Throwable) {
        }
    }

    private fun saveSanitizedHostMirror(
        snapshot: QuotaHudView.Snapshot,
        lang: QuotaHudView.Lang,
        theme: QuotaHudView.ThemeMode
    ) {
        val ctx = appContext ?: return
        val clean = snapshot.sanitized()
        try {
            ctx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("five", clean.fiveHourQuota)
                .putString("five_reset", clean.fiveHourReset)
                .putString("week", clean.weeklyLimit)
                .putString("week_reset", clean.weeklyReset)
                .putString("src", clean.source)
                .putString("ui_lang", lang.code)
                .putString("ui_theme", theme.code)
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun applyUnifiedState(state: QuotaSyncProvider.UnifiedState?) {
        if (state == null) return
        val cleanSnap = state.snapshot.sanitized()
        var changed = false

        if (currentLang.get() != state.lang) {
            currentLang.set(state.lang)
            changed = true
        }
        if (currentTheme.get() != state.theme) {
            currentTheme.set(state.theme)
            changed = true
        }
        if (latestSnapshot.get() != cleanSnap && (cleanSnap.hasLiveData || !latestSnapshot.get().hasLiveData)) {
            latestSnapshot.set(cleanSnap)
            changed = true
        }

        if (changed) {
            saveSanitizedHostMirror(latestSnapshot.get(), currentLang.get(), currentTheme.get())
            refreshHudOnUiThread()
        }
    }

    private fun registerUnifiedObserverOnce(ctx: Context) {
        if (!observerRegistered.compareAndSet(false, true)) return
        providerObserver = QuotaSyncProvider.registerStateObserver(ctx) {
            pullFromUnifiedProviderAsync(ctx)
        }
    }

    private fun pullFromUnifiedProviderAsync(ctx: Context, delayMs: Long = 0L) {
        val runPull = {
            try {
                ipcExecutor.execute {
                    try {
                        val state = QuotaSyncProvider.readUnifiedState(ctx)
                        applyUnifiedState(state)
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
        if (delayMs > 0L) {
            postToMain(delayMs = delayMs) { runPull() }
        } else {
            runPull()
        }
    }

    private fun syncHeartbeatWithProviderAsync(ctx: Context, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastHeartbeatMs < 6_000L) return
        lastHeartbeatMs = now

        try {
            ipcExecutor.execute {
                val localSnap = latestSnapshot.get().sanitized()
                val state = QuotaSyncProvider.sendHeartbeatAndFetchState(
                    context = ctx,
                    pkg = hostPackageName,
                    proc = hostProcessName,
                    bridge = activeBridgeName,
                    isApi102 = isModernApi102Bridge,
                    localSnapshot = localSnap
                )
                if (state != null) {
                    applyUnifiedState(state)
                } else {
                    sendFallbackHeartbeatBroadcast(ctx, localSnap)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun sendFallbackHeartbeatBroadcast(ctx: Context, snap: QuotaHudView.Snapshot) {
        try {
            val clean = snap.sanitized()
            val hb = Intent(QuotaTextParser.ACTION_HOOK_HEARTBEAT).apply {
                setClassName(
                    QuotaSyncProvider.MODULE_PACKAGE,
                    "com.example.bardquotahook.HookTelemetryReceiver"
                )
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("pkg", hostPackageName)
                putExtra("proc", hostProcessName)
                putExtra("bridge", activeBridgeName)
                putExtra("api_102", isModernApi102Bridge)
                putExtra("five", clean.fiveHourQuota)
                putExtra("five_reset", clean.fiveHourReset)
                putExtra("week", clean.weeklyLimit)
                putExtra("week_reset", clean.weeklyReset)
                putExtra("src", clean.source)
            }
            ctx.sendBroadcast(hb)
        } catch (_: Throwable) {
        }
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiversOnce(ctx: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        try {
            val filter = IntentFilter().apply {
                addAction(QuotaTextParser.ACTION_QUOTA_INTERNAL_SYNC)
                addAction(QuotaTextParser.ACTION_QUOTA_EXTERNAL_SYNC)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val act = intent?.action ?: return
                    if (act != QuotaTextParser.ACTION_QUOTA_INTERNAL_SYNC &&
                        act != QuotaTextParser.ACTION_QUOTA_EXTERNAL_SYNC
                    ) {
                        return
                    }

                    var uiUpdated = false

                    val langExtra = intent.getStringExtra("ui_lang")
                    if (!langExtra.isNullOrBlank()) {
                        currentLang.set(QuotaHudView.Lang.fromCode(langExtra))
                        uiUpdated = true
                    }
                    val themeExtra = intent.getStringExtra("ui_theme")
                    if (!themeExtra.isNullOrBlank()) {
                        currentTheme.set(QuotaHudView.ThemeMode.fromCode(themeExtra))
                        uiUpdated = true
                    }

                    if (intent.getBooleanExtra("ping", false) && context != null) {
                        syncHeartbeatWithProviderAsync(context, force = true)
                    }

                    val incoming = QuotaHudView.Snapshot(
                        fiveHourQuota = intent.getStringExtra("five") ?: "--",
                        fiveHourReset = intent.getStringExtra("five_reset")
                            ?: intent.getStringExtra("reset")
                            ?: "--",
                        weeklyLimit = intent.getStringExtra("week") ?: "--",
                        weeklyReset = intent.getStringExtra("week_reset") ?: "--",
                        source = intent.getStringExtra("src") ?: "Sync"
                    ).sanitized()

                    val prev = latestSnapshot.get().sanitized()
                    val merged = QuotaHudView.Snapshot(
                        fiveHourQuota = if (incoming.fiveHourQuota != "--") incoming.fiveHourQuota else prev.fiveHourQuota,
                        fiveHourReset = if (incoming.fiveHourReset != "--") incoming.fiveHourReset else prev.fiveHourReset,
                        weeklyLimit = if (incoming.weeklyLimit != "--") incoming.weeklyLimit else prev.weeklyLimit,
                        weeklyReset = if (incoming.weeklyReset != "--") incoming.weeklyReset else prev.weeklyReset,
                        source = incoming.source
                    ).sanitized()

                    if (merged.hasLiveData && merged != prev) {
                        latestSnapshot.set(merged)
                        uiUpdated = true
                    }

                    if (uiUpdated) {
                        saveSanitizedHostMirror(latestSnapshot.get(), currentLang.get(), currentTheme.get())
                        refreshHudOnUiThread()
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
        } catch (_: Throwable) {
        }
    }

    private fun triggerBackgroundQuotaRefresh(ctx: Context) {
        scheduleScreenBannerCheck()
        scheduleWebViewPoll(150L)

        try {
            ipcExecutor.execute {
                QuotaSyncProvider.requestBackgroundRefresh(ctx, hostPackageName)
            }
        } catch (_: Throwable) {
        }

        pullFromUnifiedProviderAsync(ctx, delayMs = 1600L)
        pullFromUnifiedProviderAsync(ctx, delayMs = 3600L)
    }

    // =========================================================================
    // 2. Targeted Embedded Usage WebView & Structured JSON Inspection
    // =========================================================================

    private fun hookWebViewDom(bridge: HookBridge) {
        hookMethodSafe(
            bridge = bridge,
            clazz = View::class.java,
            name = "onAttachedToWindow",
            paramTypes = emptyArray(),
            after = cb@{ thisObj, _, _ ->
                val wv = thisObj as? WebView ?: return@cb
                activeWebViewRef.set(WeakReference(wv))
                scheduleWebViewPoll(1000L)
                scheduleWebViewPoll(2500L)
            }
        )

        hookMethodSafe(
            bridge = bridge,
            clazz = WebView::class.java,
            name = "loadUrl",
            paramTypes = arrayOf(String::class.java),
            after = cb@{ thisObj, args, _ ->
                val wv = thisObj as? WebView ?: return@cb
                val url = (args.firstOrNull() as? String).orEmpty().lowercase()
                if (url.contains("gemini.google.com/usage")) {
                    activeWebViewRef.set(WeakReference(wv))
                    scheduleWebViewPoll(1500L)
                    scheduleWebViewPoll(3200L)
                }
            }
        )
    }

    private fun hookStructuredJsonPayloads(bridge: HookBridge) {
        hookConstructorSafe(
            bridge = bridge,
            clazz = JSONObject::class.java,
            paramTypes = arrayOf(String::class.java)
        ) ctor@{ thisObj, args ->
            val raw = args.firstOrNull() as? String ?: return@ctor
            if (raw.length > 65_536 || !QuotaTextParser.fastHasQuotaKeyword(raw)) return@ctor
            withReentrancyGuard {
                val obj = thisObj as? JSONObject ?: return@withReentrancyGuard
                val snap = QuotaTextParser.inspectJsonObject(obj, latestSnapshot.get(), "JSONObj")
                if (snap != null) publishSnapshot(snap)
            }
        }

        hookConstructorSafe(
            bridge = bridge,
            clazz = JSONObject::class.java,
            paramTypes = arrayOf(JSONTokener::class.java)
        ) { thisObj, _ ->
            withReentrancyGuard {
                val obj = thisObj as? JSONObject ?: return@withReentrancyGuard
                val snap = QuotaTextParser.inspectJsonObject(obj, latestSnapshot.get(), "JSONTok")
                if (snap != null) publishSnapshot(snap)
            }
        }
    }

    // =========================================================================
    // Rate-Limit Banner & Usage WebView Inspection
    // =========================================================================

    private fun scheduleScreenBannerCheck() {
        postToMain(delayMs = 500L) { scanForExhaustedBannerOnly() }
        scheduleWebViewPoll(1100L)
    }

    private fun scanForExhaustedBannerOnly() {
        val activity = activeActivityRef.get().get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window?.decorView ?: return
        val sb = StringBuilder()
        collectViewTexts(decor, sb, 0)
        if (sb.isNotEmpty()) {
            val text = sb.toString()
            if (QuotaTextParser.fastHasQuotaKeyword(text)) {
                withReentrancyGuard {
                    val snap = QuotaTextParser.parseExhaustedBannerOnly(text, latestSnapshot.get(), "Banner")
                    if (snap != null) publishSnapshot(snap)
                }
            }
        }
    }

    private fun collectViewTexts(v: View, out: StringBuilder, depth: Int) {
        if (depth > 16 || out.length > 6144) return
        if (v.tag == QuotaHudView.HUD_VIEW_TAG) return
        if (v.visibility != View.VISIBLE) return

        if (v is WebView) {
            activeWebViewRef.set(WeakReference(v))
        }
        if (v is TextView) {
            val tagStr = v.tag as? String
            if (tagStr == null || !tagStr.startsWith("bard_quota_hud")) {
                val t = v.text
                if (!t.isNullOrBlank() && t.length <= 200) {
                    out.append(t).append('\n')
                }
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i) ?: continue
                collectViewTexts(child, out, depth + 1)
            }
        }
    }

    private fun scheduleWebViewPoll(delayMs: Long) {
        postToMain(delayMs = delayMs) {
            val wv = activeWebViewRef.get().get() ?: return@postToMain
            if (!wv.isAttachedToWindow) return@postToMain
            try {
                val currentUrl = wv.url.orEmpty().lowercase()
                if (!currentUrl.contains("gemini.google.com/usage")) return@postToMain
                wv.evaluateJavascript(
                    "(function(){return document.body ? document.body.innerText : '';})()"
                ) { raw ->
                    try {
                        if (!raw.isNullOrBlank() && raw != "null" && raw.length > 6) {
                            val unquoted = raw.trim('"')
                                .replace("\\n", "\n")
                                .replace("\\\"", "\"")
                            if (QuotaTextParser.fastHasQuotaKeyword(unquoted)) {
                                withReentrancyGuard {
                                    val snap = QuotaTextParser.parseUiBlock(
                                        block = unquoted,
                                        prev = latestSnapshot.get(),
                                        source = "WebView",
                                        requireUsageStructure = true
                                    )
                                    if (snap != null) publishSnapshot(snap)
                                }
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    // =========================================================================
    // Publishing & HUD Rendering (`QuotaHudView`)
    // =========================================================================

    private fun publishSnapshot(snapshot: QuotaHudView.Snapshot) {
        val clean = snapshot.sanitized()
        if (!clean.hasLiveData) return
        val current = latestSnapshot.get().sanitized()
        if (clean == current) return

        latestSnapshot.set(clean)
        saveSanitizedHostMirror(clean, currentLang.get(), currentTheme.get())
        refreshHudOnUiThread()

        val ctx = appContext
        if (ctx != null) {
            try {
                val sync = Intent(QuotaTextParser.ACTION_QUOTA_INTERNAL_SYNC).apply {
                    if (hostPackageName.isNotEmpty()) setPackage(hostPackageName)
                    putExtra("five", clean.fiveHourQuota)
                    putExtra("five_reset", clean.fiveHourReset)
                    putExtra("week", clean.weeklyLimit)
                    putExtra("week_reset", clean.weeklyReset)
                    putExtra("src", clean.source)
                }
                ctx.sendBroadcast(sync)
            } catch (_: Throwable) {
            }

            try {
                ipcExecutor.execute {
                    val merged = QuotaSyncProvider.publishQuota(ctx, clean)
                    if (merged != null) {
                        applyUnifiedState(merged)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun postToMain(delayMs: Long = 0L, action: () -> Unit) {
        try {
            val looper = Looper.getMainLooper() ?: return
            val handler = mainHandler ?: Handler(looper).also { mainHandler = it }
            val runnable = Runnable {
                try {
                    action()
                } catch (_: Throwable) {
                }
            }
            if (delayMs > 0L) {
                handler.postDelayed(runnable, delayMs)
            } else if (Looper.myLooper() == looper) {
                runnable.run()
            } else {
                handler.post(runnable)
            }
        } catch (_: Throwable) {
        }
    }

    private fun refreshHudOnUiThread() {
        postToMain {
            val hud = activeHudRef.get().get()
            if (hud != null && hud.isAttachedToWindow) {
                hud.applyPreferences(currentLang.get(), currentTheme.get())
                hud.updateSnapshot(latestSnapshot.get().sanitized())
            } else {
                val act = activeActivityRef.get().get()
                if (act != null && !act.isFinishing && !act.isDestroyed) {
                    attachOrUpdateOverlay(act)
                }
            }
        }
    }

    private fun attachOrUpdateOverlay(activity: Activity) {
        postToMain {
            if (activity.isFinishing || activity.isDestroyed) return@postToMain
            val decorGroup = activity.window?.decorView as? ViewGroup ?: return@postToMain

            var hud = decorGroup.findViewWithTag<View>(QuotaHudView.HUD_VIEW_TAG) as? QuotaHudView
            if (hud == null) {
                hud = QuotaHudView(
                    context = activity,
                    onRefreshClick = {
                        triggerBackgroundQuotaRefresh(activity.applicationContext ?: activity)
                    },
                    onOpenUsageClick = {
                        QuotaHudView.openUsageWebpage(activity)
                    },
                    draggableInFrame = true
                )

                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = QuotaHudView.dp(activity, 52f)
                    rightMargin = QuotaHudView.dp(activity, 10f)
                }
                decorGroup.addView(hud, lp)
            }

            hud.applyPreferences(currentLang.get(), currentTheme.get())
            hud.updateSnapshot(latestSnapshot.get().sanitized())
            hud.bringToFront()
            activeHudRef.set(WeakReference(hud))
        }
    }
}
