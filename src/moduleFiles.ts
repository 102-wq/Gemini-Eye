export interface ProjectFile {
  path: string;
  label: string;
  language: string;
  descriptionZh: string;
  descriptionEn: string;
  content: string;
}

export const MAIN_ACTIVITY_KT = `package com.example.bardquotahook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

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

    private fun launchGeminiWithFallback() {
        val candidates = listOf(
            packageManager.getLaunchIntentForPackage("com.google.android.apps.bard"),
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(
                    "com.google.android.apps.bard",
                    "com.google.android.apps.bard.shellapp.BardEntryPointActivity"
                )
            },
            packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox"),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/app"))
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bardInstalled = isPkgInstalled("com.google.android.apps.bard")
        val gsaInstalled = isPkgInstalled("com.google.android.googlequicksearchbox")

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 96, 56, 96)
        }

        val titleView = TextView(this).apply {
            text = "Gemini-Eye 用量监控 (v1.5.0)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val statusView = TextView(this).apply {
            text = "检测状态 · Gemini 壳应用: \${if (bardInstalled) "已安装" else "未安装(走Google主程序)"} | Google 主程序: \${if (gsaInstalled) "已安装" else "未检测到"}"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, 16, 0, 36)
        }

        val guideCard = TextView(this).apply {
            text = """
                多进程增强版 (v1.5.0) 配置说明：
                
                1. 在 LSPosed 作用域页面点击右上角「⋮」->「隐藏」-> 取消勾选「系统应用」。
                2. 同时勾选「Gemini」与「Google (com.google.android.googlequicksearchbox)」。
                3. 在系统设置中「强行停止」Google 应用，再点击下方按钮启动 Gemini。
                4. 进入 Gemini 内的额度/订阅/限制页面后，后台进程与 UI 进程会自动跨进程同步用量数据到右上角悬浮窗。
            """.trimIndent()
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(10f, 1.15f)
            setPadding(40, 40, 40, 40)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#334155"))
            }
        }

        val launchBtn = Button(this).apply {
            text = "启动 Gemini / Launch Gemini"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = 24f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 48
            }
            setPadding(32, 32, 32, 32)
            setOnClickListener { launchGeminiWithFallback() }
        }

        container.addView(titleView)
        container.addView(statusView)
        container.addView(guideCard)
        container.addView(launchBtn)
        scroll.addView(container)
        setContentView(scroll)
    }
}
`;

export const QUOTA_HOOK_KT = `package com.example.bardquotahook

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
                """["']?([a-zA-Z0-9_]*(?:quota|limit|reset|remaining|cap|usage)[a-zA-Z0-9_]*)["']?\\s*[:=]\\s*["']?([^"',}\\]\\n]{1,48})["']?""",
                RegexOption.IGNORE_CASE
            )
        }

        private val fiveHourRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(5\\s*(-|\\s)?(hour|hr|h|小时)|5h|(\\d+\\s*/\\s*\\d+)|remaining|剩余\\s*\\d+|已用\\s*\\d+).*""",
                RegexOption.IGNORE_CASE
            )
        }

        private val weeklyRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(weekly|week|每周|周限额|daily|每日|7\\s*(-|\\s)?(day|天)).*""",
                RegexOption.IGNORE_CASE
            )
        }

        private val resetRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(resets?\\b|重置|恢复|available\\s+(in|at)|try\\s+again\\s+(in|after|at)|until\\s+\\d|后重置|\\d+\\s*(h|hr|小时)\\s*\\d*\\s*(m|min|分钟)?).*""",
                RegexOption.IGNORE_CASE
            )
        }

        private fun logInfo(msg: String) {
            XposedBridge.log("[\$TAG] \$msg")
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
        logInfo("initZygote -> \${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // Match ALL processes (:search, :interactor, main UI, remote background services)
        if (lpparam.packageName == "android" || lpparam.packageName == "com.example.bardquotahook") return
        logInfo("Loaded pkg=\${lpparam.packageName} proc=\${lpparam.processName}")

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
     * via StaticLayout\$Builder.obtain(). Both live in Android framework (immune to R8 obfuscation).
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

        val builderClass = XposedHelpers.findClassIfExists("android.text.StaticLayout\\$Builder", null) ?: return
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
     * On Android ART, \`new String(byte[], ...)\` compiles to \`java.lang.StringFactory.newStringFromBytes\`.
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
        val callbackCls = XposedHelpers.findClassIfExists("org.chromium.net.UrlRequest\\$Callback", classLoader)
        if (callbackCls != null) {
            logInfo("Found Cronet UrlRequest.Callback in \$classLoader")
        }
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
                        val unescaped = rawJsString.trim('"').replace("\\\\n", "\\n")
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
        val cleaned = payload.removePrefix(")]}'\\n").replace("\\\\\\"", "\\"").trim()
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
        logInfo("[\$source] 5h=\$fiveHour | week=\$weekly | reset=\$reset")
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
            return if (lang == Lang.ZH) "👁 额度: \${s.fiveHourQuota}" else "👁 \${s.fiveHourQuota}"
        }
        return when (lang) {
            Lang.ZH -> "用量监控 [\${s.source} | 点按切EN | 长按收起]\\n5小时额度: \${s.fiveHourQuota}\\n每周限额: \${s.weeklyLimit}\\n重置时间: \${s.resetTimer}"
            Lang.EN -> "Usage Limits [\${s.source} | Tap->中文 | Hold->Min]\\n5h Quota: \${s.fiveHourQuota}\\nWeekly Limit: \${s.weeklyLimit}\\nReset Timer: \${s.resetTimer}"
        }
    }
}
`;

export const PROJECT_FILES: ProjectFile[] = [
  {
    path: '.github/workflows/android.yml',
    label: 'android.yml (GitHub Actions)',
    language: 'yaml',
    descriptionZh: '基于 ubuntu-24.04 + JDK 17 + Gradle 8.9 自动产出单 DEX 未压缩 Release APK。',
    descriptionEn: 'GitHub Actions workflow on ubuntu-24.04 using JDK 17 + Gradle 8.9 to build a single uncompressed DEX APK.',
    content: `name: Build LSPosed APK

on:
  push:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-24.04
    steps:
      - name: Checkout repository
        uses: actions/checkout@v5

      - name: Set up JDK 17
        uses: actions/setup-java@v5
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Accept Android SDK Licenses
        run: yes | "\$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true

      - name: Setup Gradle 8.9
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'

      - name: Build Signed Single-DEX Release APK
        run: gradle assembleRelease --no-daemon

      - name: Upload Compiled APK
        uses: actions/upload-artifact@v4
        with:
          name: bard-quota-hook-release
          path: app/build/outputs/apk/release/*.apk
`,
  },
  {
    path: 'settings.gradle.kts',
    label: 'settings.gradle.kts',
    language: 'kotlin',
    descriptionZh: '声明 Google、MavenCentral 与 Xposed 官方 Maven 源 (api.xposed.info)。',
    descriptionEn: 'Configures Google, MavenCentral, and the Xposed API repository (api.xposed.info).',
    content: `pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "BardQuotaHook"
include(":app")
`,
  },
  {
    path: 'build.gradle.kts',
    label: 'build.gradle.kts (Root)',
    language: 'kotlin',
    descriptionZh: '根工程配置，对齐 Android Gradle Plugin 8.5.2 与 Kotlin 1.9.24。',
    descriptionEn: 'Root Gradle build file targeting AGP 8.5.2 and Kotlin 1.9.24.',
    content: `plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
`,
  },
  {
    path: 'gradle.properties',
    label: 'gradle.properties',
    language: 'properties',
    descriptionZh: 'JVM 内存参数与 AndroidX 开关。',
    descriptionEn: 'JVM heap arguments and standard Android build flags.',
    content: `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
`,
  },
  {
    path: 'app/build.gradle.kts',
    label: 'app/build.gradle.kts',
    language: 'kotlin',
    descriptionZh: '应用模块构建脚本 (v1.5.0)：minSdk=24 产出 dex035，单 DEX 未压缩打包，compileOnly Xposed API 82。',
    descriptionEn: 'App module script (v1.5.0) emitting uncompressed single-DEX dex035 with compileOnly Xposed API 82.',
    content: `plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.bardquotahook"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.bardquotahook"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "1.5.0"
    }

    signingConfigs {
        create("fixedRelease") {
            val ksFile = file("signing.p12")
            if (ksFile.exists()) {
                storeFile = ksFile
                storeType = "PKCS12"
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    packaging {
        dex {
            useLegacyPackaging = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            val ksFile = file("signing.p12")
            signingConfig = if (ksFile.exists()) {
                signingConfigs.getByName("fixedRelease")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
`,
  },
  {
    path: 'app/proguard-rules.pro',
    label: 'app/proguard-rules.pro',
    language: 'pro',
    descriptionZh: '保留 QuotaHookEntry 与 MainActivity 入口类及无参构造函数，防止 R8 混淆移除。',
    descriptionEn: 'Preserves QuotaHookEntry and MainActivity during R8 single-DEX merge.',
    content: `-keep public class com.example.bardquotahook.QuotaHookEntry {
    public <init>();
    *;
}
-keep public class com.example.bardquotahook.MainActivity {
    public <init>();
    *;
}
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage {
    public <init>();
    *;
}
-keepattributes *Annotation*,InnerClasses,Signature
`,
  },
  {
    path: 'app/src/main/AndroidManifest.xml',
    label: 'AndroidManifest.xml',
    language: 'xml',
    descriptionZh: '包含 Android 11+ <queries> 包可见性声明、MainActivity 启动页与 LSPosed 元数据。',
    descriptionEn: 'Android 11+ <queries> package visibility declarations, MainActivity launcher, and LSPosed metadata.',
    content: `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Android 11+ (API 30+) Package Visibility declarations -->
    <queries>
        <package android:name="com.google.android.apps.bard" />
        <package android:name="com.google.android.googlequicksearchbox" />
        <package android:name="com.android.chrome" />
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>

    <application
        android:label="Gemini 用量监控 Hook"
        android:hasCode="true">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="de.robv.android.xposed.category.MODULE_SETTINGS" />
            </intent-filter>
        </activity>

        <meta-data
            android:name="xposedmodule"
            android:value="true" />
        <meta-data
            android:name="xposeddescription"
            android:value="Gemini Usage Limits Sniffer &amp; Bilingual HUD (ZH/EN)" />
        <meta-data
            android:name="xposedminversion"
            android:value="82" />
        <meta-data
            android:name="xposedscope"
            android:resource="@array/xposed_scope" />
    </application>
</manifest>
`,
  },
  {
    path: 'app/src/main/res/values/arrays.xml',
    label: 'res/values/arrays.xml',
    language: 'xml',
    descriptionZh: '同时勾选 Gemini 壳应用与实际承载界面的 Google 主程序 (googlequicksearchbox)。',
    descriptionEn: 'Scopes both the Bard launcher stub and Google App (googlequicksearchbox) where Gemini runs.',
    content: `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="xposed_scope">
        <item>com.google.android.apps.bard</item>
        <item>com.google.android.googlequicksearchbox</item>
    </string-array>
</resources>
`,
  },
  {
    path: 'app/src/main/assets/xposed_init',
    label: 'assets/xposed_init',
    language: 'text',
    descriptionZh: 'Xposed 入口类完整限定名。',
    descriptionEn: 'Fully qualified Xposed hook entry class name.',
    content: `com.example.bardquotahook.QuotaHookEntry
`,
  },
  {
    path: 'app/src/main/java/com/example/bardquotahook/MainActivity.kt',
    label: 'MainActivity.kt',
    language: 'kotlin',
    descriptionZh: '模块设置引导页：兼容 Android 11+/13+ 包可见性检测与四级回退启动 Gemini。',
    descriptionEn: 'Module launcher & status UI with Android 11+/13+ package visibility check and 4-tier Gemini launch fallback.',
    content: MAIN_ACTIVITY_KT,
  },
  {
    path: 'app/src/main/java/com/example/bardquotahook/QuotaHookEntry.kt',
    label: 'QuotaHookEntry.kt',
    language: 'kotlin',
    descriptionZh: '核心多进程 Hook 实现：跨进程 Broadcast 同步 + ART StringFactory + BoringLayout/StaticLayout + Cronet ByteBuffer + 可拖拽/折叠中英悬浮窗。',
    descriptionEn: 'Multi-process Xposed hook: cross-process Broadcast IPC, ART StringFactory, BoringLayout/StaticLayout, Cronet ByteBuffer, and draggable/collapsible ZH/EN HUD.',
    content: QUOTA_HOOK_KT,
  },
];
