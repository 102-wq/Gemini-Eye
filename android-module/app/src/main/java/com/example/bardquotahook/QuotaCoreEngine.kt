package com.example.bardquotahook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.BoringLayout
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object QuotaCoreEngine {

    private const val TAG = "BardQuota"
    private const val MODULE_PKG = "com.example.bardquotahook"
    private const val TELEMETRY_RECEIVER_CLS = "com.example.bardquotahook.HookTelemetryReceiver"

    interface HookBridge {
        val bridgeName: String
        fun log(msg: String)
        fun hookExecutable(
            executable: Executable,
            before: ((thisObj: Any?, args: Array<Any?>) -> Unit)? = null,
            after: ((thisObj: Any?, args: Array<Any?>, result: Any?) -> Unit)? = null
        ): (() -> Unit)?
    }

    private val installed = AtomicBoolean(false)
    @Volatile private var activeBridge: HookBridge? = null
    @Volatile private var hostPackage: String = ""
    @Volatile private var hostProcess: String = ""
    @Volatile private var mainHandler: Handler? = null

    private val inHookGuard = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private val activeActivityRef = AtomicReference<WeakReference<Activity>>(WeakReference(null))
    private val overlayContainerRef = AtomicReference<WeakReference<LinearLayout>>(WeakReference(null))
    private val activeWebViewRef = AtomicReference<WeakReference<WebView>>(WeakReference(null))
    private val receiverRef = AtomicReference<WeakReference<BroadcastReceiver>>(WeakReference(null))
    private val unhookActions = CopyOnWriteArrayList<() -> Unit>()

    private val latestSnapshot = AtomicReference(QuotaHudLayout.Snapshot())

    fun install(
        packageName: String,
        processName: String,
        classLoader: ClassLoader?,
        bridge: HookBridge
    ) {
        if (packageName == "android" || packageName == MODULE_PKG) return
        if (!installed.compareAndSet(false, true)) return

        activeBridge = bridge
        hostPackage = packageName
        hostProcess = processName

        logInfo("Engine installed -> pkg=$packageName proc=$processName bridge=${bridge.bridgeName}")

        // 1. Lifecycle & In-App HUD + Cross-App Sync Receiver
        safeTry("hookActivityLifecycle") { hookActivityLifecycle(bridge) }

        // 2. Compose / View / Accessibility / WebView UI Sniffers
        safeTry("hookUiTextAndDescriptions") { hookUiTextAndDescriptions(classLoader, bridge) }
        safeTry("hookWebViewDom") { hookWebViewDom(bridge) }

        // 3. ART-Safe JSON / Stream Decoders (R8-Immune)
        safeTry("hookJsonPayloads") { hookJsonPayloads(bridge) }
        safeTry("hookStreamDecoders") { hookStreamDecoders(bridge) }

        // Notify Gemini-Eye diagnostics activity if Application context is ready
        postToMain(delayMs = 500L) {
            sendHeartbeatToModuleApp()
        }
    }

    private inline fun withReentrancyGuard(block: () -> Unit) {
        if (inHookGuard.get() == true) return
        inHookGuard.set(true)
        try {
            block()
        } catch (t: Throwable) {
            logDebug("Callback err: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            inHookGuard.set(false)
        }
    }

    private inline fun safeTry(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logDebug("$label skipped: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logInfo(msg: String) {
        try {
            activeBridge?.log("[$TAG] $msg")
            Log.i(TAG, msg)
        } catch (_: Throwable) {
        }
    }

    private fun logDebug(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Throwable) {
        }
    }

    private fun postToMain(delayMs: Long = 0L, action: () -> Unit) {
        try {
            val looper = Looper.getMainLooper() ?: return
            val handler = mainHandler ?: Handler(looper).also { mainHandler = it }
            val runnable = Runnable {
                try {
                    action()
                } catch (t: Throwable) {
                    logDebug("Main task err: ${t.message}")
                }
            }
            if (delayMs > 0L) handler.postDelayed(runnable, delayMs) else handler.post(runnable)
        } catch (_: Throwable) {
        }
    }

    private fun hookMethodSafe(
        bridge: HookBridge,
        clazz: Class<*>?,
        name: String,
        paramTypes: Array<Class<*>>,
        before: ((Any?, Array<Any?>) -> Unit)? = null,
        after: ((Any?, Array<Any?>, Any?) -> Unit)? = null
    ) {
        if (clazz == null) return
        try {
            val method = clazz.getDeclaredMethod(name, *paramTypes)
            method.isAccessible = true
            val unhook = bridge.hookExecutable(method, before, after)
            if (unhook != null) unhookActions.add(unhook)
        } catch (t: Throwable) {
            logDebug("Method ${clazz.name}.$name not hooked: ${t.javaClass.simpleName}")
        }
    }

    private fun hookAllMethodsByName(
        bridge: HookBridge,
        clazz: Class<*>?,
        name: String,
        before: ((Any?, Array<Any?>) -> Unit)? = null,
        after: ((Any?, Array<Any?>, Any?) -> Unit)? = null
    ) {
        if (clazz == null) return
        try {
            for (method in clazz.declaredMethods) {
                if (method.name == name) {
                    try {
                        method.isAccessible = true
                        val unhook = bridge.hookExecutable(method, before, after)
                        if (unhook != null) unhookActions.add(unhook)
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (t: Throwable) {
            logDebug("AllMethods ${clazz.name}.$name skipped: ${t.javaClass.simpleName}")
        }
    }

    private fun hookConstructorSafe(
        bridge: HookBridge,
        clazz: Class<*>?,
        paramTypes: Array<Class<*>>,
        after: (Any?, Array<Any?>) -> Unit
    ) {
        if (clazz == null) return
        try {
            val ctor = clazz.getDeclaredConstructor(*paramTypes)
            ctor.isAccessible = true
            val unhook = bridge.hookExecutable(
                executable = ctor,
                before = null,
                after = { thisObj, args, _ -> after(thisObj, args) }
            )
            if (unhook != null) unhookActions.add(unhook)
        } catch (t: Throwable) {
            logDebug("Ctor ${clazz.name} skipped: ${t.javaClass.simpleName}")
        }
    }

    // =========================================================================
    // 1. Activity Lifecycle, HUD Attachment & Cross-App Sync
    // =========================================================================

    private fun hookActivityLifecycle(bridge: HookBridge) {
        hookMethodSafe(
            bridge = bridge,
            clazz = Activity::class.java,
            name = "onResume",
            paramTypes = emptyArray(),
            after = { thisObj, _, _ ->
                withReentrancyGuard {
                    val activity = thisObj as? Activity ?: return@withReentrancyGuard
                    if (activity.isFinishing || activity.isDestroyed) return@withReentrancyGuard
                    activeActivityRef.set(WeakReference(activity))
                    registerSyncReceiver(activity)
                    attachOrUpdateOverlay()
                    scheduleScreenInspection()
                    sendHeartbeatToModuleApp(activity)
                }
            }
        )

        hookMethodSafe(
            bridge = bridge,
            clazz = Activity::class.java,
            name = "onConfigurationChanged",
            paramTypes = arrayOf(Configuration::class.java),
            after = { thisObj, _, _ ->
                withReentrancyGuard {
                    val activity = thisObj as? Activity ?: return@withReentrancyGuard
                    if (activeActivityRef.get().get() === activity) {
                        attachOrUpdateOverlay()
                    }
                }
            }
        )

        hookMethodSafe(
            bridge = bridge,
            clazz = Activity::class.java,
            name = "onDestroy",
            paramTypes = emptyArray(),
            before = { thisObj, _ ->
                withReentrancyGuard {
                    val destroyed = thisObj as? Activity ?: return@withReentrancyGuard
                    if (activeActivityRef.get().get() === destroyed) {
                        unregisterSyncReceiver(destroyed)
                        activeActivityRef.set(WeakReference(null))
                        overlayContainerRef.set(WeakReference(null))
                    }
                }
            }
        )
    }

    // =========================================================================
    // 2. Compose / TextView / Accessibility ContentDescription / WebView Hooks
    // =========================================================================

    private fun hookUiTextAndDescriptions(classLoader: ClassLoader?, bridge: HookBridge) {
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
            Class.forName("android.text.StaticLayout\$Builder", false, classLoader ?: Activity::class.java.classLoader)
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

        // Captures Accessibility semantics descriptions set on usage meters / progress bars
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
                val snap = QuotaTextParser.parseUiBlock(text, latestSnapshot.get(), "ScreenScan")
                if (snap != null) publishSnapshot(snap)
            }
        }
    }

    private fun collectViewTexts(view: View, out: StringBuilder, depth: Int) {
        if (depth > 18 || out.length > 24_000) return
        val tagStr = view.tag as? String
        if (tagStr != null && tagStr.startsWith("bard_quota_hud")) return

        if (view is WebView) {
            activeWebViewRef.set(WeakReference(view))
        }
        if (view is TextView) {
            val t = view.text
            if (!t.isNullOrBlank()) {
                out.append(t).append('\n')
            }
        }
        val desc = view.contentDescription
        if (!desc.isNullOrBlank()) {
            out.append(desc).append('\n')
        }
        if (view is ViewGroup) {
            val count = view.childCount
            for (i in 0 until count) {
                val child = view.getChildAt(i)
                if (child != null) collectViewTexts(child, out, depth + 1)
            }
        }
    }

    private fun scheduleWebViewPoll(delayMs: Long = 900L) {
        postToMain(delayMs = delayMs) {
            val wv = activeWebViewRef.get().get() ?: return@postToMain
            if (!wv.isShown) return@postToMain
            safeTry("evaluateJavascript") {
                wv.evaluateJavascript(
                    "(function(){return document.body ? document.body.innerText : '';})()"
                ) { rawJsString ->
                    safeTry("webViewDomResult") {
                        if (!rawJsString.isNullOrBlank() && rawJsString != "null") {
                            val unescaped = rawJsString.trim('"')
                                .replace("\\n", "\n")
                                .replace("\\\"", "\"")
                            val snap = QuotaTextParser.parseUiBlock(unescaped, latestSnapshot.get(), "WebView")
                            if (snap != null) publishSnapshot(snap)
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Cross-Process & Cross-App Broadcast Sync + Heartbeat Telemetry
    // =========================================================================

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSyncReceiver(activity: Activity) {
        if (receiverRef.get().get() != null) return
        safeTry("registerSyncReceiver") {
            val appCtx = activity.applicationContext ?: return@safeTry
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    safeTry("onReceiveQuotaSync") {
                        val act = intent?.action ?: return@safeTry
                        if (act != QuotaTextParser.ACTION_QUOTA_INTERNAL_SYNC &&
                            act != QuotaTextParser.ACTION_QUOTA_EXTERNAL_SYNC
                        ) {
                            return@safeTry
                        }
                        if (intent.getBooleanExtra("ping", false)) {
                            sendHeartbeatToModuleApp(activity)
                        }
                        val prev = latestSnapshot.get()
                        val five = intent.getStringExtra("five") ?: prev.fiveHourQuota
                        val week = intent.getStringExtra("week") ?: prev.weeklyLimit
                        val reset = intent.getStringExtra("reset") ?: prev.resetTimer
                        val src = intent.getStringExtra("src") ?: "WebSync"
                        if (five != "--" || week != "--" || reset != "--") {
                            applyLocalSnapshot(QuotaHudLayout.Snapshot(five, week, reset, src))
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(QuotaTextParser.ACTION_QUOTA_INTERNAL_SYNC)
                addAction(QuotaTextParser.ACTION_QUOTA_EXTERNAL_SYNC)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appCtx.registerReceiver(receiver, filter)
            }
            receiverRef.set(WeakReference(receiver))
        }
    }

    private fun unregisterSyncReceiver(activity: Activity) {
        val receiver = receiverRef.get().get() ?: return
        safeTry("unregisterSyncReceiver") {
            activity.applicationContext?.unregisterReceiver(receiver)
        }
        receiverRef.set(WeakReference(null))
    }

    private fun publishSnapshot(snapshot: QuotaHudLayout.Snapshot) {
        val changed = applyLocalSnapshot(snapshot)
        if (!changed) return

        safeTry("broadcastInternalAndTelemetry") {
            val appCtx = AndroidAppHelper.currentApplication()
                ?: activeActivityRef.get().get()?.applicationContext
                ?: return@safeTry

            val internalIntent = Intent(QuotaTextParser.ACTION_QUOTA_INTERNAL_SYNC).apply {
                setPackage(appCtx.packageName)
                putExtra("five", snapshot.fiveHourQuota)
                putExtra("week", snapshot.weeklyLimit)
                putExtra("reset", snapshot.resetTimer)
                putExtra("src", snapshot.source)
            }
            appCtx.sendBroadcast(internalIntent)
            sendHeartbeatToModuleApp(appCtx)
        }
    }

    private fun sendHeartbeatToModuleApp(ctxOverride: Context? = null) {
        safeTry("sendHeartbeatToModuleApp") {
            val ctx = ctxOverride
                ?: AndroidAppHelper.currentApplication()
                ?: activeActivityRef.get().get()?.applicationContext
                ?: return@safeTry
            val snap = latestSnapshot.get()
            val hb = Intent(QuotaTextParser.ACTION_HOOK_HEARTBEAT).apply {
                component = ComponentName(MODULE_PKG, TELEMETRY_RECEIVER_CLS)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("pkg", hostPackage.ifEmpty { ctx.packageName })
                putExtra("proc", hostProcess)
                putExtra("bridge", activeBridge?.bridgeName ?: "Xposed")
                putExtra("five", snap.fiveHourQuota)
                putExtra("week", snap.weeklyLimit)
                putExtra("reset", snap.resetTimer)
                putExtra("src", snap.source)
            }
            ctx.sendBroadcast(hb)
        }
    }

    private fun applyLocalSnapshot(snapshot: QuotaHudLayout.Snapshot): Boolean {
        val prev = latestSnapshot.get()
        if (prev.fiveHourQuota == snapshot.fiveHourQuota &&
            prev.weeklyLimit == snapshot.weeklyLimit &&
            prev.resetTimer == snapshot.resetTimer
        ) {
            return false
        }
        logInfo("[${snapshot.source}] 5h=${snapshot.fiveHourQuota} | week=${snapshot.weeklyLimit} | reset=${snapshot.resetTimer}")
        latestSnapshot.set(snapshot)
        attachOrUpdateOverlay()
        return true
    }

    private fun attachOrUpdateOverlay() {
        postToMain {
            val activity = activeActivityRef.get().get() ?: return@postToMain
            if (activity.isFinishing || activity.isDestroyed) return@postToMain

            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@postToMain
            var container = root.findViewWithTag<LinearLayout>(QuotaHudLayout.OVERLAY_TAG)
                ?: overlayContainerRef.get().get()

            if (container == null || container.parent == null) {
                container = QuotaHudLayout.createContainer(
                    context = activity,
                    snapshotProvider = { latestSnapshot.get() },
                    onTapRefresh = { scheduleScreenInspection() }
                )
                root.addView(container, QuotaHudLayout.defaultLayoutParams(activity))
                overlayContainerRef.set(WeakReference(container))
            }

            withReentrancyGuard {
                QuotaHudLayout.bind(activity, container, latestSnapshot.get())
                container.bringToFront()
            }
        }
    }
}
