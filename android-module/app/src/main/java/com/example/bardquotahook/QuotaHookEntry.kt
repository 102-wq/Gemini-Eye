package com.example.bardquotahook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.BoringLayout
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class QuotaHookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {

    companion object {
        private const val TAG = "BardQuota"
        private const val OVERLAY_TAG = "bard_quota_overlay_view"
        private const val ACTION_QUOTA_SYNC = "com.example.bardquotahook.ACTION_QUOTA_SYNC"

        @Volatile private var mainHandler: Handler? = null

        private fun postToMain(delayMs: Long = 0L, action: () -> Unit) {
            val looper = Looper.getMainLooper() ?: return
            val handler = mainHandler ?: Handler(looper).also { mainHandler = it }
            if (delayMs > 0L) handler.postDelayed(action, delayMs) else handler.post(action)
        }

        private val activeActivityRef = AtomicReference<WeakReference<Activity>>(WeakReference(null))
        private val overlayViewRef = AtomicReference<WeakReference<TextView>>(WeakReference(null))
        private val activeWebViewRef = AtomicReference<WeakReference<WebView>>(WeakReference(null))
        private val receiverRef = AtomicReference<WeakReference<BroadcastReceiver>>(WeakReference(null))
        private val unhookRefs = CopyOnWriteArrayList<WeakReference<XC_MethodHook.Unhook>>()

        @Volatile private var currentLang: Lang = Lang.ZH
        @Volatile private var isCollapsed: Boolean = false
        @Volatile private var savedOffsetX: Float = 0f
        @Volatile private var savedOffsetY: Float = 0f

        private val latestSnapshot = AtomicReference(QuotaSnapshot())

        private val keyRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """["']?([a-zA-Z0-9_]*(?:quota|limit|reset|remaining|cap|usage)[a-zA-Z0-9_]*)["']?\s*[:=]\s*["']?([^"',}\]\n]{1,48})["']?""",
                RegexOption.IGNORE_CASE
            )
        }

        private val fiveHourRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(5\s*(-|\s)?(hour|hr|h|小时)|5h|(\d+\s*/\s*\d+)|remaining|剩余\s*\d+|已用\s*\d+).*""",
                RegexOption.IGNORE_CASE
            )
        }

        private val weeklyRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(weekly|week|每周|周限额|daily|每日|7\s*(-|\s)?(day|天)).*""",
                RegexOption.IGNORE_CASE
            )
        }

        private val resetRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(resets?\b|重置|恢复|available\s+(in|at)|try\s+again\s+(in|after|at)|until\s+\d|后重置|\d+\s*(h|hr|小时)\s*\d*\s*(m|min|分钟)?).*""",
                RegexOption.IGNORE_CASE
            )
        }

        private fun logInfo(msg: String) {
            XposedBridge.log("[$TAG] $msg")
            Log.i(TAG, msg)
        }
    }

    enum class Lang { ZH, EN }

    data class QuotaSnapshot(
        val fiveHourQuota: String = "--",
        val weeklyLimit: String = "--",
        val resetTimer: String = "--",
        val source: String = "init"
    )

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        logInfo("initZygote -> ${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // Match ALL processes (:search, :interactor, main UI, remote background services)
        if (lpparam.packageName == "android" || lpparam.packageName == "com.example.bardquotahook") return
        logInfo("Loaded pkg=${lpparam.packageName} proc=${lpparam.processName}")

        // 1. Network / Data-flow hooks (active in BOTH background service processes & UI process)
        runCatching { hookOrgJson() }
        runCatching { hookAndroidStringFactory() } // ART redirects new String(byte[]) here!
        runCatching { hookCronetByteBuffers(lpparam.classLoader) }
        runCatching { hookGsonIfPresent(lpparam.classLoader) }

        // 2. Obfuscation-proof Framework UI / Compose / Accessibility / WebView hooks
        runCatching { hookActivityLifecycle() }
        runCatching { hookComposeBoringAndStaticLayout() }
        runCatching { hookAccessibilitySemantics() }
        runCatching { hookWebViewDomSniffer() }
    }

    private fun trackUnhook(unhook: XC_MethodHook.Unhook?) {
        if (unhook != null) unhookRefs.add(WeakReference(unhook))
    }

    private fun hookActivityLifecycle() {
        trackUnhook(
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.isFinishing) return
                        activeActivityRef.set(WeakReference(activity))
                        registerCrossProcessReceiver(activity)
                        attachOrUpdateOverlay()
                        scheduleWebViewPoll()
                    }
                }
            )
        )

        trackUnhook(
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onDestroy",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val destroyed = param.thisObject as? Activity ?: return
                        if (activeActivityRef.get().get() === destroyed) {
                            unregisterCrossProcessReceiver(destroyed)
                            activeActivityRef.set(WeakReference(null))
                            overlayViewRef.set(WeakReference(null))
                        }
                        unhookRefs.removeIf { it.get() == null }
                    }
                }
            )
        )
    }

    /**
     * Bridges multi-process data: background network processes (:interactor / :search)
     * broadcast extracted quota fields to the foreground UI Activity process.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerCrossProcessReceiver(activity: Activity) {
        if (receiverRef.get().get() != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_QUOTA_SYNC) return
                val prev = latestSnapshot.get()
                val five = intent.getStringExtra("five") ?: prev.fiveHourQuota
                val week = intent.getStringExtra("week") ?: prev.weeklyLimit
                val reset = intent.getStringExtra("reset") ?: prev.resetTimer
                val src = intent.getStringExtra("src") ?: "IPC"
                applyLocalSnapshot(five, week, reset, src)
            }
        }
        runCatching {
            val filter = IntentFilter(ACTION_QUOTA_SYNC)
            if (Build.VERSION.SDK_INT >= 33) {
                activity.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                activity.applicationContext.registerReceiver(receiver, filter)
            }
            receiverRef.set(WeakReference(receiver))
        }
    }

    private fun unregisterCrossProcessReceiver(activity: Activity) {
        val receiver = receiverRef.get().get() ?: return
        runCatching { activity.applicationContext.unregisterReceiver(receiver) }
        receiverRef.set(WeakReference(null))
    }

    /**
     * Jetpack Compose measures single-line Text via BoringLayout.isBoring() and multi-line Text
     * via StaticLayout$Builder.obtain(). Both live in Android framework (immune to R8 obfuscation).
     */
    private fun hookComposeBoringAndStaticLayout() {
        XposedBridge.hookAllMethods(
            BoringLayout::class.java,
            "isBoring",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val cs = param.args.firstOrNull() as? CharSequence ?: return
                    if (cs.length in 3..240) inspectUiText(cs.toString(), "Compose1L")
                }
            }
        ).forEach { trackUnhook(it) }

        val builderClass = XposedHelpers.findClassIfExists("android.text.StaticLayout\$Builder", null) ?: return
        trackUnhook(
            XposedHelpers.findAndHookMethod(
                builderClass,
                "obtain",
                CharSequence::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                TextPaint::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cs = param.args[0] as? CharSequence ?: return
                        if (cs.length in 3..300) inspectUiText(cs.toString(), "ComposeML")
                    }
                }
            )
        )
    }

    /**
     * Catches Compose Semantics & native Accessibility text / contentDescriptions.
     */
    private fun hookAccessibilitySemantics() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cs = param.args.firstOrNull() as? CharSequence ?: return
                if (cs.length in 3..240) inspectUiText(cs.toString(), "Semantics")
            }
        }
        XposedBridge.hookAllMethods(AccessibilityNodeInfo::class.java, "setText", hook)
            .forEach { trackUnhook(it) }
        XposedBridge.hookAllMethods(AccessibilityNodeInfo::class.java, "setContentDescription", hook)
            .forEach { trackUnhook(it) }
    }

    /**
     * On Android ART, `new String(byte[], ...)` compiles to `java.lang.StringFactory.newStringFromBytes`.
     * Hooking StringFactory intercepts Protobuf-Lite UTF-8 strings, batchexecute JSON, and gRPC payloads!
     */
    private fun hookAndroidStringFactory() {
        val factoryClass = XposedHelpers.findClassIfExists("java.lang.StringFactory", null) ?: return
        XposedBridge.hookAllMethods(
            factoryClass,
            "newStringFromBytes",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val str = param.result as? String ?: return
                    if (str.length in 6..262_144 && containsTargetKeyword(str)) {
                        parseRawPayload(str, "ARTBytes")
                    }
                }
            }
        ).forEach { trackUnhook(it) }
    }

    /**
     * Intercepts Cronet native network read ByteBuffers across background network processes.
     */
    private fun hookCronetByteBuffers(classLoader: ClassLoader) {
        val callbackCls = XposedHelpers.findClassIfExists("org.chromium.net.UrlRequest\$Callback", classLoader)
        if (callbackCls != null) {
            logInfo("Found Cronet UrlRequest.Callback in $classLoader")
        }
        // Hook ByteBuffer.flip() used right after Cronet fills response buffers
        trackUnhook(
            XposedHelpers.findAndHookMethod(
                ByteBuffer::class.java,
                "flip",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val buf = param.thisObject as? ByteBuffer ?: return
                        val rem = buf.remaining()
                        if (rem !in 24..131_072) return
                        runCatching {
                            val dup = buf.duplicate()
                            val bytes = ByteArray(rem)
                            dup.get(bytes)
                            val text = String(bytes, StandardCharsets.UTF_8)
                            if (containsTargetKeyword(text)) {
                                parseRawPayload(text, "CronetBuf")
                            }
                        }
                    }
                }
            )
        )
    }

    private fun hookWebViewDomSniffer() {
        trackUnhook(
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val wv = param.thisObject as? WebView ?: return
                        activeWebViewRef.set(WeakReference(wv))
                        scheduleWebViewPoll()
                    }
                }
            )
        )
    }

    private fun scheduleWebViewPoll() {
        postToMain(delayMs = 1000L) {
            val wv = activeWebViewRef.get().get() ?: return@postToMain
            if (!wv.isShown) return@postToMain
            runCatching {
                wv.evaluateJavascript(
                    "(function(){return document.body ? document.body.innerText : '';})()"
                ) { rawJsString ->
                    if (!rawJsString.isNullOrBlank() && rawJsString != "null") {
                        val unescaped = rawJsString.trim('"').replace("\\n", "\n")
                        inspectUiText(unescaped, "WebView")
                    }
                }
            }
        }
    }

    private fun inspectUiText(block: String, source: String) {
        if (block.startsWith("用量监控") || block.startsWith("Usage Limits") || block.startsWith("👁")) return
        val prev = latestSnapshot.get()
        var fiveHour = prev.fiveHourQuota
        var weekly = prev.weeklyLimit
        var reset = prev.resetTimer
        var hit = false

        for (rawLine in block.lineSequence()) {
            val line = rawLine.trim()
            if (line.length !in 3..120) continue
            if (fiveHourRegex.matches(line)) {
                fiveHour = line
                hit = true
            }
            if (weeklyRegex.matches(line)) {
                weekly = line
                hit = true
            }
            if (resetRegex.matches(line)) {
                reset = line
                hit = true
            }
        }

        if (hit) publishSnapshotAcrossProcesses(fiveHour, weekly, reset, source)
    }

    private fun hookOrgJson() {
        trackUnhook(
            XposedHelpers.findAndHookConstructor(
                JSONObject::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val raw = param.args.firstOrNull() as? String ?: return
                        if (containsTargetKeyword(raw)) {
                            inspectJsonObject(param.thisObject as? JSONObject ?: return, "JSON")
                        }
                    }
                }
            )
        )
    }

    private fun hookGsonIfPresent(classLoader: ClassLoader) {
        val gsonClass = XposedHelpers.findClassIfExists("com.google.gson.Gson", classLoader) ?: return
        XposedBridge.hookAllMethods(gsonClass, "fromJson", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val input = param.args.firstOrNull() as? String ?: return
                if (containsTargetKeyword(input)) parseRawPayload(input, "Gson")
            }
        }).forEach { trackUnhook(it) }
    }

    private fun containsTargetKeyword(text: String): Boolean {
        return text.contains("quota", ignoreCase = true) ||
            text.contains("limit", ignoreCase = true) ||
            text.contains("reset", ignoreCase = true) ||
            text.contains("remaining", ignoreCase = true) ||
            text.contains("小时") ||
            text.contains("限额") ||
            text.contains("重置")
    }

    private fun inspectJsonObject(json: JSONObject, source: String) {
        val extracted = mutableMapOf<String, String>()
        collectFromJson(json, extracted, 0)
        if (extracted.isNotEmpty()) mergeExtractedFields(extracted, source)
    }

    private fun collectFromJson(node: Any?, out: MutableMap<String, String>, depth: Int) {
        if (depth > 6 || node == null) return
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (containsTargetKeyword(key) && value !is JSONObject && value !is JSONArray) {
                        out[key.lowercase()] = value.toString()
                    } else {
                        collectFromJson(value, out, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until minOf(node.length(), 50)) {
                    collectFromJson(node.opt(i), out, depth + 1)
                }
            }
        }
    }

    private fun parseRawPayload(payload: String, source: String) {
        val cleaned = payload.removePrefix(")]}'\n").replace("\\\"", "\"").trim()
        if (cleaned.startsWith("{")) {
            runCatching { JSONObject(cleaned) }.getOrNull()?.let {
                inspectJsonObject(it, source)
                return
            }
        }
        val extracted = mutableMapOf<String, String>()
        keyRegex.findAll(cleaned).take(40).forEach { match ->
            extracted[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        if (extracted.isNotEmpty()) mergeExtractedFields(extracted, source)
        inspectUiText(cleaned, source)
    }

    private fun mergeExtractedFields(fields: Map<String, String>, source: String) {
        val prev = latestSnapshot.get()
        var fiveHour = prev.fiveHourQuota
        var weekly = prev.weeklyLimit
        var reset = prev.resetTimer

        for ((k, v) in fields) {
            when {
                k.contains("5h") || k.contains("window") || k.contains("quota") || k.contains("remaining") -> fiveHour = v
                k.contains("week") || k.contains("limit") || k.contains("cap") -> weekly = v
                k.contains("reset") || k.contains("expire") -> reset = v
            }
        }
        publishSnapshotAcrossProcesses(fiveHour, weekly, reset, source)
    }

    private fun publishSnapshotAcrossProcesses(fiveHour: String, weekly: String, reset: String, source: String) {
        val changed = applyLocalSnapshot(fiveHour, weekly, reset, source)
        if (!changed) return

        // Broadcast to UI process in case this hook fired inside :interactor or :search process
        runCatching {
            val appCtx = AndroidAppHelper.currentApplication() ?: activeActivityRef.get().get()?.applicationContext
            appCtx?.sendBroadcast(
                Intent(ACTION_QUOTA_SYNC).apply {
                    putExtra("five", fiveHour)
                    putExtra("week", weekly)
                    putExtra("reset", reset)
                    putExtra("src", source)
                }
            )
        }
    }

    private fun applyLocalSnapshot(fiveHour: String, weekly: String, reset: String, source: String): Boolean {
        val prev = latestSnapshot.get()
        if (prev.fiveHourQuota == fiveHour && prev.weeklyLimit == weekly && prev.resetTimer == reset) return false
        logInfo("[$source] 5h=$fiveHour | week=$weekly | reset=$reset")
        latestSnapshot.set(QuotaSnapshot(fiveHour, weekly, reset, source))
        attachOrUpdateOverlay()
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachOrUpdateOverlay() {
        postToMain {
            val activity = activeActivityRef.get().get() ?: return@postToMain
            if (activity.isFinishing || activity.isDestroyed) return@postToMain

            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@postToMain
            var badge = root.findViewWithTag<TextView>(OVERLAY_TAG) ?: overlayViewRef.get().get()

            if (badge == null || badge.parent == null) {
                val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
                badge = TextView(activity).apply {
                    tag = OVERLAY_TAG
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setPadding(24, 14, 24, 14)
                    background = GradientDrawable().apply {
                        setColor(0xDD1E1F22.toInt())
                        setStroke(2, 0x66FFFFFF)
                        cornerRadius = 18f
                    }
                    elevation = 100f
                    translationX = savedOffsetX
                    translationY = savedOffsetY

                    var downRawX = 0f
                    var downRawY = 0f
                    var startTransX = 0f
                    var startTransY = 0f
                    var downTime = 0L
                    var dragging = false

                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downRawX = event.rawX
                                downRawY = event.rawY
                                startTransX = v.translationX
                                startTransY = v.translationY
                                downTime = System.currentTimeMillis()
                                dragging = false
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.rawX - downRawX
                                val dy = event.rawY - downRawY
                                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                                if (dragging) {
                                    v.translationX = startTransX + dx
                                    v.translationY = startTransY + dy
                                    savedOffsetX = v.translationX
                                    savedOffsetY = v.translationY
                                }
                                true
                            }
                            MotionEvent.ACTION_UP -> {
                                if (!dragging) {
                                    val heldMs = System.currentTimeMillis() - downTime
                                    if (heldMs >= 450L) {
                                        isCollapsed = !isCollapsed
                                    } else if (isCollapsed) {
                                        isCollapsed = false
                                    } else {
                                        currentLang = if (currentLang == Lang.ZH) Lang.EN else Lang.ZH
                                    }
                                    (v as TextView).text = formatOverlayText(latestSnapshot.get(), currentLang, isCollapsed)
                                    scheduleWebViewPoll()
                                }
                                true
                            }
                            else -> false
                        }
                    }
                }

                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    setMargins(24, 220, 24, 0)
                }
                root.addView(badge, params)
                overlayViewRef.set(WeakReference(badge))
            }

            badge.bringToFront()
            badge.text = formatOverlayText(latestSnapshot.get(), currentLang, isCollapsed)
        }
    }

    private fun formatOverlayText(s: QuotaSnapshot, lang: Lang, collapsed: Boolean): String {
        if (collapsed) {
            return if (lang == Lang.ZH) "👁 额度: ${s.fiveHourQuota}" else "👁 ${s.fiveHourQuota}"
        }
        return when (lang) {
            Lang.ZH -> "用量监控 [${s.source} | 点按切EN | 长按收起]\n5小时额度: ${s.fiveHourQuota}\n每周限额: ${s.weeklyLimit}\n重置时间: ${s.resetTimer}"
            Lang.EN -> "Usage Limits [${s.source} | Tap->中文 | Hold->Min]\n5h Quota: ${s.fiveHourQuota}\nWeekly Limit: ${s.weeklyLimit}\nReset Timer: ${s.resetTimer}"
        }
    }
}
