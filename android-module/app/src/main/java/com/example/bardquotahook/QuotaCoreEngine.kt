package com.example.bardquotahook

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.BoringLayout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Framework-agnostic Quota Engine shared by both:
 * - `ModernQuotaModule` (`io.github.libxposed.api.XposedModule`, API 102)
 * - `QuotaHookEntry` (`de.robv.android.xposed.IXposedHookLoadPackage`, API 82)
 *
 * Mounts the single in-app `QuotaHudView` capsule inside Gemini, enforces Single-HUD Exclusion
 * via `ACTION_HOOK_HEARTBEAT`, and synchronizes quota + i18n (`ZH`/`EN`) + Theme (`MIUIX`/`MD3`)
 * with `SharedPreferences` across both HUD and Settings app.
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
    private val lifecycleRegistered = AtomicBoolean(false)
    private val latestSnapshot = AtomicReference(QuotaHudView.Snapshot())
    private val currentLang = AtomicReference(QuotaHudView.Lang.ZH)
    private val currentTheme = AtomicReference(QuotaHudView.ThemeMode.MIUIX)

    private val activeHudRef = AtomicReference<WeakReference<QuotaHudView>>(WeakReference(null))
    private val activeActivityRef = AtomicReference<WeakReference<Activity>>(WeakReference(null))
    private val activeWebViewRef = AtomicReference<WeakReference<WebView>>(WeakReference(null))

    @Volatile private var appContext: Context? = null
    @Volatile private var mainHandler: Handler? = null
    @Volatile private var hostPackageName: String = ""
    @Volatile private var hostProcessName: String = ""
    @Volatile private var activeBridgeName: String = ""
    @Volatile private var isModernApi102Bridge: Boolean = false
    @Volatile private var lastHeartbeatMs: Long = 0L
    @Volatile private var remotePrefsRef: SharedPreferences? = null

    private val remotePrefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        readFromSharedPrefs(prefs, persistToHostLocal = true)
        refreshHudOnUiThread()
    }

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

        // Mutual exclusion: ensure only one engine installs per process
        if (!installedGuard.compareAndSet(false, true)) return

        hostPackageName = packageName
        hostProcessName = processName
        activeBridgeName = bridge.bridgeName
        isModernApi102Bridge = bridge.isModernApi102

        safeLog(bridge, "Installing [${bridge.bridgeName}] into pkg=$packageName proc=$processName")

        // 0. Bind LibXposed API 102 Remote SharedPreferences if supported by framework
        try {
            val rPrefs = bridge.openRemotePreferences(QuotaTextParser.PREFS_NAME)
            if (rPrefs != null) {
                remotePrefsRef = rPrefs
                readFromSharedPrefs(rPrefs, persistToHostLocal = false)
                rPrefs.registerOnSharedPreferenceChangeListener(remotePrefListener)
            }
        } catch (_: Throwable) {
        }

        // 1. Context, IPC Broadcast & Activity Lifecycle HUD Hooks
        hookApplicationAndActivities(bridge)

        // 2. UI Text & Compose Layout Sniffing
        hookComposeAndViewLayouts(bridge, classLoader)

        // 3. Embedded WebView DOM Inspection (Usage Limits page)
        hookWebViewDom(bridge)

        // 4. ART-safe JSON & Stream Decoder Hooks
        hookJsonPayloads(bridge)
        hookStreamDecoders(bridge)
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

    private fun hookAllMethodsByName(
        bridge: HookBridge,
        clazz: Class<*>?,
        name: String,
        before: ((thisObj: Any?, args: Array<Any?>) -> Unit)? = null,
        after: ((thisObj: Any?, args: Array<Any?>, result: Any?) -> Unit)? = null
    ) {
        if (clazz == null) return
        try {
            for (method in clazz.declaredMethods) {
                if (method.name == name) {
                    try {
                        method.isAccessible = true
                        bridge.hookExecutable(method, before, after)
                    } catch (_: Throwable) {
                    }
                }
            }
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
    // 1. Application, Broadcasts & Activity HUD Injection
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
                onContextCaptured(activity.applicationContext ?: activity)
                if (shouldDisplayHudInActivity(activity)) {
                    activeActivityRef.set(WeakReference(activity))
                    attachOrUpdateOverlay(activity)
                    scheduleScreenInspection()
                    sendModuleHeartbeat(activity, force = false)
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
                        scheduleScreenInspection()
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
            loadHostLocalMirror(appContext)
        }
        val targetCtx = appContext ?: ctx
        registerReceiversOnce(targetCtx)
        sendModuleHeartbeat(targetCtx, force = false)
    }

    private fun loadHostLocalMirror(ctx: Context?) {
        if (ctx == null) return
        try {
            val prefs = ctx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
            readFromSharedPrefs(prefs, persistToHostLocal = false)
        } catch (_: Throwable) {
        }
    }

    private fun saveHostLocalMirror(
        snapshot: QuotaHudView.Snapshot? = null,
        lang: QuotaHudView.Lang? = null,
        theme: QuotaHudView.ThemeMode? = null
    ) {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences(QuotaTextParser.PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (snapshot != null && snapshot.hasLiveData) {
                editor.putString("five", snapshot.fiveHourQuota)
                editor.putString("five_reset", snapshot.fiveHourReset)
                editor.putString("week", snapshot.weeklyLimit)
                editor.putString("week_reset", snapshot.weeklyReset)
                editor.putString("src", snapshot.source)
            }
            if (lang != null) {
                editor.putString("ui_lang", lang.code)
            }
            if (theme != null) {
                editor.putString("ui_theme", theme.code)
            }
            editor.apply()
        } catch (_: Throwable) {
        }
    }

    private fun readFromSharedPrefs(prefs: SharedPreferences, persistToHostLocal: Boolean) {
        try {
            val langCode = prefs.getString("ui_lang", null)
            val themeCode = prefs.getString("ui_theme", null)
            val newLang = if (langCode != null) QuotaHudView.Lang.fromCode(langCode) else null
            val newTheme = if (themeCode != null) QuotaHudView.ThemeMode.fromCode(themeCode) else null
            if (newLang != null) currentLang.set(newLang)
            if (newTheme != null) currentTheme.set(newTheme)

            val five = prefs.getString("five", "--") ?: "--"
            val fiveReset = prefs.getString("five_reset", null)
                ?: prefs.getString("reset", "--")
                ?: "--"
            val week = prefs.getString("week", "--") ?: "--"
            val weekReset = prefs.getString("week_reset", "--") ?: "--"
            val src = prefs.getString("src", "SharedPrefs") ?: "SharedPrefs"

            val candidate = QuotaHudView.Snapshot(
                fiveHourQuota = five,
                fiveHourReset = fiveReset,
                weeklyLimit = week,
                weeklyReset = weekReset,
                source = src
            )
            if (candidate.hasLiveData) {
                latestSnapshot.set(candidate)
            }
            if (persistToHostLocal) {
                saveHostLocalMirror(
                    snapshot = if (candidate.hasLiveData) candidate else null,
                    lang = newLang,
                    theme = newTheme
                )
            }
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
                        sendModuleHeartbeat(context, force = true)
                    }

                    val five = intent.getStringExtra("five")
                    val fiveReset = intent.getStringExtra("five_reset")
                        ?: intent.getStringExtra("reset")
                    val week = intent.getStringExtra("week")
                    val weekReset = intent.getStringExtra("week_reset")
                    val src = intent.getStringExtra("src")

                    val prev = latestSnapshot.get()
                    val merged = QuotaHudView.Snapshot(
                        fiveHourQuota = if (!five.isNullOrBlank() && five != "--") five else prev.fiveHourQuota,
                        fiveHourReset = if (!fiveReset.isNullOrBlank() && fiveReset != "--") fiveReset else prev.fiveHourReset,
                        weeklyLimit = if (!week.isNullOrBlank() && week != "--") week else prev.weeklyLimit,
                        weeklyReset = if (!weekReset.isNullOrBlank() && weekReset != "--") weekReset else prev.weeklyReset,
                        source = src ?: prev.source
                    )

                    if (merged.hasLiveData && merged != prev) {
                        latestSnapshot.set(merged)
                        uiUpdated = true
                    }

                    if (uiUpdated) {
                        saveHostLocalMirror(
                            snapshot = latestSnapshot.get(),
                            lang = currentLang.get(),
                            theme = currentTheme.get()
                        )
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

    /**
     * Notifies `com.example.bardquotahook.HookTelemetryReceiver` that Xposed is actively bound
     * to Gemini (triggering Single-HUD Exclusion to auto-disable App System Overlay) and shares
     * live quota back to `SharedPreferences` so HUD and Settings app match identically.
     */
    private fun sendModuleHeartbeat(ctx: Context, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastHeartbeatMs < 8_000L) return
        lastHeartbeatMs = now

        try {
            val snap = latestSnapshot.get()
            val hb = Intent(QuotaTextParser.ACTION_HOOK_HEARTBEAT).apply {
                setClassName(
                    "com.example.bardquotahook",
                    "com.example.bardquotahook.HookTelemetryReceiver"
                )
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("pkg", hostPackageName)
                putExtra("proc", hostProcessName)
                putExtra("bridge", activeBridgeName)
                putExtra("api_102", isModernApi102Bridge)
                putExtra("five", snap.fiveHourQuota)
                putExtra("five_reset", snap.fiveHourReset)
                putExtra("week", snap.weeklyLimit)
                putExtra("week_reset", snap.weeklyReset)
                putExtra("src", snap.source)
            }
            ctx.sendBroadcast(hb)
        } catch (_: Throwable) {
        }
    }

    /**
     * Triggered when user clicks the Refresh icon button on the in-app `QuotaHudView`.
     * Scans local Views/WebViews AND triggers background `gemini.google.com/usage` API sync
     * in `com.example.bardquotahook`.
     */
    private fun triggerBackgroundQuotaRefresh(ctx: Context) {
        scheduleScreenInspection()
        scheduleWebViewPoll(120L)
        try {
            val req = Intent(QuotaTextParser.ACTION_REQUEST_QUOTA_REFRESH).apply {
                setClassName(
                    "com.example.bardquotahook",
                    "com.example.bardquotahook.HookTelemetryReceiver"
                )
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("requester_pkg", hostPackageName)
            }
            ctx.sendBroadcast(req)
        } catch (_: Throwable) {
        }
    }

    // =========================================================================
    // 2. UI Layout & Compose Canvas Text Sniffing
    // =========================================================================

    private fun hookComposeAndViewLayouts(bridge: HookBridge, classLoader: ClassLoader?) {
        hookAllMethodsByName(
            bridge = bridge,
            clazz = BoringLayout::class.java,
            name = "isBoring",
            before = cb@{ _, args ->
                val cs = args.firstOrNull() as? CharSequence ?: return@cb
                if (cs.length !in 2..200) return@cb
                val str = cs.toString()
                if (!QuotaTextParser.fastHasQuotaKeyword(str)) return@cb
                withReentrancyGuard {
                    val snap = QuotaTextParser.parseUiBlock(str, latestSnapshot.get(), "Compose")
                    if (snap != null) publishSnapshot(snap)
                }
            }
        )

        val staticBuilderCls = try {
            Class.forName(
                "android.text.StaticLayout\$Builder",
                false,
                classLoader ?: Activity::class.java.classLoader
            )
        } catch (_: Throwable) {
            null
        }
        hookAllMethodsByName(
            bridge = bridge,
            clazz = staticBuilderCls,
            name = "obtain",
            before = cb@{ _, args ->
                val cs = args.firstOrNull() as? CharSequence ?: return@cb
                if (cs.length !in 2..280) return@cb
                val str = cs.toString()
                if (!QuotaTextParser.fastHasQuotaKeyword(str)) return@cb
                withReentrancyGuard {
                    val snap = QuotaTextParser.parseUiBlock(str, latestSnapshot.get(), "Compose")
                    if (snap != null) publishSnapshot(snap)
                }
            }
        )

        hookMethodSafe(
            bridge = bridge,
            clazz = StaticLayout::class.java,
            name = "generate",
            paramTypes = emptyArray()
        )

        // Hook TextPaint.measureText fallback for single-line Compose canvas text nodes
        hookMethodSafe(
            bridge = bridge,
            clazz = TextPaint::class.java,
            name = "getTextRunAdvances",
            paramTypes = arrayOf(
                CharArray::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                FloatArray::class.java,
                Int::class.javaPrimitiveType!!
            ),
            before = cb@{ _, args ->
                val chars = args.getOrNull(0) as? CharArray ?: return@cb
                val index = args.getOrNull(1) as? Int ?: 0
                val count = args.getOrNull(2) as? Int ?: return@cb
                if (count !in 3..140 || index < 0 || index + count > chars.size) return@cb
                val str = String(chars, index, count)
                if (!QuotaTextParser.fastHasQuotaKeyword(str)) return@cb
                withReentrancyGuard {
                    val snap = QuotaTextParser.parseUiBlock(str, latestSnapshot.get(), "Canvas")
                    if (snap != null) publishSnapshot(snap)
                }
            }
        )

        hookMethodSafe(
            bridge = bridge,
            clazz = TextView::class.java,
            name = "setText",
            paramTypes = arrayOf(CharSequence::class.java, TextView.BufferType::class.java),
            before = cb@{ thisObj, args ->
                val tv = thisObj as? TextView ?: return@cb
                val tagStr = tv.tag as? String
                if (tagStr != null && tagStr.startsWith("bard_quota_hud")) return@cb
                val cs = args.firstOrNull() as? CharSequence ?: return@cb
                if (cs.length !in 2..240) return@cb
                val str = cs.toString()
                if (!QuotaTextParser.fastHasQuotaKeyword(str)) return@cb
                withReentrancyGuard {
                    val snap = QuotaTextParser.parseUiBlock(str, latestSnapshot.get(), "View")
                    if (snap != null) publishSnapshot(snap)
                }
            }
        )

        hookMethodSafe(
            bridge = bridge,
            clazz = View::class.java,
            name = "setContentDescription",
            paramTypes = arrayOf(CharSequence::class.java),
            before = cb@{ _, args ->
                val cs = args.firstOrNull() as? CharSequence ?: return@cb
                if (cs.length !in 3..240) return@cb
                val str = cs.toString()
                if (!QuotaTextParser.fastHasQuotaKeyword(str)) return@cb
                withReentrancyGuard {
                    val snap = QuotaTextParser.parseUiBlock(str, latestSnapshot.get(), "A11y")
                    if (snap != null) publishSnapshot(snap)
                }
            }
        )
    }

    private fun hookWebViewDom(bridge: HookBridge) {
        hookMethodSafe(
            bridge = bridge,
            clazz = View::class.java,
            name = "onAttachedToWindow",
            paramTypes = emptyArray(),
            after = cb@{ thisObj, _, _ ->
                val wv = thisObj as? WebView ?: return@cb
                activeWebViewRef.set(WeakReference(wv))
                scheduleWebViewPoll(900L)
                scheduleWebViewPoll(2400L)
            }
        )

        hookMethodSafe(
            bridge = bridge,
            clazz = WebView::class.java,
            name = "loadUrl",
            paramTypes = arrayOf(String::class.java),
            after = cb@{ thisObj, _, _ ->
                val wv = thisObj as? WebView ?: return@cb
                activeWebViewRef.set(WeakReference(wv))
                scheduleWebViewPoll(1500L)
                scheduleWebViewPoll(3200L)
            }
        )
    }

    // =========================================================================
    // 3. ART-Safe JSON & Stream Decoding Hooks
    // =========================================================================

    private fun hookJsonPayloads(bridge: HookBridge) {
        hookConstructorSafe(
            bridge = bridge,
            clazz = JSONObject::class.java,
            paramTypes = arrayOf(String::class.java)
        ) { thisObj, args ->
            val raw = args.firstOrNull() as? String ?: return@hookConstructorSafe
            if (!QuotaTextParser.fastHasQuotaKeyword(raw)) return@hookConstructorSafe
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

        hookConstructorSafe(
            bridge = bridge,
            clazz = JSONArray::class.java,
            paramTypes = arrayOf(String::class.java)
        ) { thisObj, args ->
            val raw = args.firstOrNull() as? String ?: return@hookConstructorSafe
            if (!QuotaTextParser.fastHasQuotaKeyword(raw)) return@hookConstructorSafe
            withReentrancyGuard {
                val arr = thisObj as? JSONArray ?: return@withReentrancyGuard
                val snap = QuotaTextParser.inspectJsonArray(arr, latestSnapshot.get(), "JSONArr")
                if (snap != null) publishSnapshot(snap)
            }
        }
    }

    private fun hookStreamDecoders(bridge: HookBridge) {
        hookMethodSafe(
            bridge = bridge,
            clazz = CharsetDecoder::class.java,
            name = "decode",
            paramTypes = arrayOf(ByteBuffer::class.java),
            after = cb@{ _, _, result ->
                val cb = result as? CharBuffer ?: return@cb
                val rem = cb.remaining()
                if (rem !in 6..262_144) return@cb
                val str = cb.toString()
                if (!QuotaTextParser.fastHasQuotaKeyword(str)) return@cb
                withReentrancyGuard {
                    val snap = QuotaTextParser.parseRawPayload(str, latestSnapshot.get(), "Decoder")
                    if (snap != null) publishSnapshot(snap)
                }
            }
        )

        hookMethodSafe(
            bridge = bridge,
            clazz = BufferedReader::class.java,
            name = "readLine",
            paramTypes = emptyArray(),
            after = cb@{ _, _, result ->
                val line = result as? String ?: return@cb
                if (!QuotaTextParser.fastHasQuotaKeyword(line)) return@cb
                withReentrancyGuard {
                    val snap = QuotaTextParser.parseRawPayload(line, latestSnapshot.get(), "Stream")
                    if (snap != null) publishSnapshot(snap)
                }
            }
        )
    }

    // =========================================================================
    // View Tree & Embedded WebView Inspection
    // =========================================================================

    private fun scheduleScreenInspection() {
        postToMain(delayMs = 450L) { scanActiveDecorView() }
        postToMain(delayMs = 1600L) { scanActiveDecorView() }
        scheduleWebViewPoll(1100L)
    }

    private fun scanActiveDecorView() {
        val activity = activeActivityRef.get().get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window?.decorView ?: return
        val sb = StringBuilder()
        collectViewTexts(decor, sb, 0)
        if (sb.isNotEmpty()) {
            val text = sb.toString()
            if (QuotaTextParser.fastHasQuotaKeyword(text)) {
                withReentrancyGuard {
                    val snap = QuotaTextParser.parseUiBlock(text, latestSnapshot.get(), "Screen")
                    if (snap != null) publishSnapshot(snap)
                }
            }
        }
    }

    private fun collectViewTexts(v: View, out: StringBuilder, depth: Int) {
        if (depth > 18 || out.length > 8192) return
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
        val desc = v.contentDescription
        if (!desc.isNullOrBlank() && desc.length <= 200) {
            out.append(desc).append('\n')
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
                                        unquoted,
                                        latestSnapshot.get(),
                                        "WebView"
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
    // Publishing & Single-Line Capsule HUD Rendering (`QuotaHudView`)
    // =========================================================================

    private fun publishSnapshot(snapshot: QuotaHudView.Snapshot) {
        val current = latestSnapshot.get()
        if (snapshot == current) return
        latestSnapshot.set(snapshot)
        saveHostLocalMirror(snapshot = snapshot)

        // Sync across multi-process host (:search / :interactor -> main UI) & Gemini-Eye app
        try {
            val ctx = appContext
            if (ctx != null) {
                val sync = Intent(QuotaTextParser.ACTION_QUOTA_INTERNAL_SYNC).apply {
                    if (hostPackageName.isNotEmpty()) setPackage(hostPackageName)
                    putExtra("five", snapshot.fiveHourQuota)
                    putExtra("five_reset", snapshot.fiveHourReset)
                    putExtra("week", snapshot.weeklyLimit)
                    putExtra("week_reset", snapshot.weeklyReset)
                    putExtra("src", snapshot.source)
                }
                ctx.sendBroadcast(sync)
                sendModuleHeartbeat(ctx, force = true)
            }
        } catch (_: Throwable) {
        }

        refreshHudOnUiThread()
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
                hud.updateSnapshot(latestSnapshot.get())
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
                        triggerBackgroundQuotaRefresh(activity)
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
            hud.updateSnapshot(latestSnapshot.get())
            hud.bringToFront()
            activeHudRef.set(WeakReference(hud))
        }
    }
}
