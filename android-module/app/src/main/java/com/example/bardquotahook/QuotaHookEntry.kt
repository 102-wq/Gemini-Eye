package com.example.bardquotahook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.BoringLayout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

class QuotaHookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {

    companion object {
        private const val TAG = "BardQuota"
        private const val OVERLAY_TAG = "bard_quota_hud_container"
        private const val TAG_DOT = "bard_quota_hud_dot"
        private const val TAG_METRICS = "bard_quota_hud_metrics"
        private const val TAG_DIVIDER = "bard_quota_hud_divider"
        private const val TAG_LANG = "bard_quota_hud_lang"
        private const val ACTION_QUOTA_SYNC = "com.example.bardquotahook.ACTION_QUOTA_SYNC"

        @Volatile private var mainHandler: Handler? = null

        private val inHookGuard = object : ThreadLocal<Boolean>() {
            override fun initialValue(): Boolean = false
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

        private fun postToMain(delayMs: Long = 0L, action: () -> Unit) {
            try {
                val looper = Looper.getMainLooper() ?: return
                val handler = mainHandler ?: Handler(looper).also { mainHandler = it }
                val safeRunnable = Runnable {
                    try {
                        action()
                    } catch (t: Throwable) {
                        logDebug("UI post err: ${t.message}")
                    }
                }
                if (delayMs > 0L) handler.postDelayed(safeRunnable, delayMs) else handler.post(safeRunnable)
            } catch (_: Throwable) {
            }
        }

        private val activeActivityRef = AtomicReference<WeakReference<Activity>>(WeakReference(null))
        private val overlayContainerRef = AtomicReference<WeakReference<LinearLayout>>(WeakReference(null))
        private val activeWebViewRef = AtomicReference<WeakReference<WebView>>(WeakReference(null))
        private val receiverRef = AtomicReference<WeakReference<BroadcastReceiver>>(WeakReference(null))
        private val unhookRefs = CopyOnWriteArrayList<WeakReference<XC_MethodHook.Unhook>>()
        private val hookedDynamicClasses: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

        @Volatile private var currentLang: Lang = Lang.ZH
        @Volatile private var isCollapsed: Boolean = false
        @Volatile private var savedOffsetX: Float = 0f
        @Volatile private var savedOffsetY: Float = 0f

        private val latestSnapshot = AtomicReference(QuotaSnapshot())

        private val keyRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """["']?([a-zA-Z0-9_]*(?:quota|limit|reset|remaining|cap|usage|window)[a-zA-Z0-9_]*)["']?\s*[:=]\s*["']?([^"',}\]\n]{1,40})["']?""",
                RegexOption.IGNORE_CASE
            )
        }

        private val ratioRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex("""(\d+\s*/\s*\d+|\d+%)""")
        }

        private val numberWithUnitRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex("""(\d+\s*(?:次|条|left|remaining|prompts?|messages?|msgs?))""", RegexOption.IGNORE_CASE)
        }

        private val compactTimeRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """(\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AP]M)?|\d+\s*(?:h|hr|hrs|小时|时)\s*\d*\s*(?:m|min|mins|分钟|分)?|\d+\s*(?:m|min|mins|分钟|分))""",
                RegexOption.IGNORE_CASE
            )
        }

        private val fiveHourLineRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(5\s*(-|\s)?(hour|hr|h|小时)|5h|remaining\s*prompts?|剩余\s*\d+|额度.*\d+\s*/\s*\d+).*""",
                RegexOption.IGNORE_CASE
            )
        }

        private val weeklyLineRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(weekly|week|每周|周限额|周额度|7\s*(-|\s)?(day|天)).*""",
                RegexOption.IGNORE_CASE
            )
        }

        private val resetLineRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(resets?\b|重置|恢复|available\s+(in|at)|try\s+again\s+(in|after|at)|后重置).*""",
                RegexOption.IGNORE_CASE
            )
        }

        private fun logInfo(msg: String) {
            try {
                XposedBridge.log("[$TAG] $msg")
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
    }

    enum class Lang { ZH, EN }
    private enum class MetricKind { FIVE_HOUR, WEEKLY, RESET }

    data class QuotaSnapshot(
        val fiveHourQuota: String = "--",
        val weeklyLimit: String = "--",
        val resetTimer: String = "--",
        val source: String = "init"
    ) {
        val hasLiveData: Boolean
            get() = fiveHourQuota != "--" || weeklyLimit != "--" || resetTimer != "--"
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        safeRun("initZygote") {
            logInfo("initZygote -> ${startupParam.modulePath}")
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        safeRun("handleLoadPackage") {
            val pkg = lpparam.packageName ?: return@safeRun
            if (pkg == "android" || pkg == "com.example.bardquotahook") return@safeRun
            logInfo("Loaded pkg=$pkg proc=${lpparam.processName}")

            val cl = lpparam.classLoader

            // 1. Safe Network & Serialization Interceptors (OkHttp, Retrofit, Okio, Cronet, JSON, Gson)
            safeRun("hookJsonParsers") { hookJsonParsers(cl) }
            safeRun("hookOkHttpAndRetrofit") { hookOkHttpAndRetrofit(cl) }
            safeRun("hookOkioBuffer") { hookOkioBuffer(cl) }
            safeRun("hookCronetCallbacks") { hookCronetCallbacks(cl) }

            // 2. Safe Framework UI / Compose / WebView Fallbacks + Dynamic HUD Lifecycle
            safeRun("hookActivityLifecycle") { hookActivityLifecycle() }
            safeRun("hookComposeAndViewText") { hookComposeAndViewText() }
            safeRun("hookWebViewDomSniffer") { hookWebViewDomSniffer() }
        }
    }

    // =========================================================================
    // Crash-Safe Reflection & Hook Wrappers (catches all Throwables / Errors)
    // =========================================================================

    private inline fun safeRun(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logDebug("$label skipped: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun findClassSafe(className: String, classLoader: ClassLoader?): Class<*>? {
        return try {
            XposedHelpers.findClassIfExists(className, classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    private fun trackUnhook(unhook: XC_MethodHook.Unhook?) {
        if (unhook != null) {
            unhookRefs.add(WeakReference(unhook))
        }
    }

    private fun safeHookMethod(clazz: Class<*>?, methodName: String, vararg parameterTypesAndCallback: Any) {
        if (clazz == null) return
        try {
            val unhook = XposedHelpers.findAndHookMethod(clazz, methodName, *parameterTypesAndCallback)
            trackUnhook(unhook)
        } catch (t: Throwable) {
            logDebug("safeHookMethod ${clazz.name}.$methodName failed: ${t.javaClass.simpleName}")
        }
    }

    private fun safeHookAllMethods(clazz: Class<*>?, methodName: String, callback: XC_MethodHook) {
        if (clazz == null) return
        try {
            val unhooks = XposedBridge.hookAllMethods(clazz, methodName, callback)
            unhooks?.forEach { trackUnhook(it) }
        } catch (t: Throwable) {
            logDebug("safeHookAllMethods ${clazz.name}.$methodName failed: ${t.javaClass.simpleName}")
        }
    }

    private fun safeHookConstructor(clazz: Class<*>?, vararg parameterTypesAndCallback: Any) {
        if (clazz == null) return
        try {
            val unhook = XposedHelpers.findAndHookConstructor(clazz, *parameterTypesAndCallback)
            trackUnhook(unhook)
        } catch (t: Throwable) {
            logDebug("safeHookConstructor ${clazz.name} failed: ${t.javaClass.simpleName}")
        }
    }

    // =========================================================================
    // Activity Lifecycle, Dynamic Theme Change & Android 14-Safe IPC Sync
    // =========================================================================

    private fun hookActivityLifecycle() {
        safeHookMethod(
            Activity::class.java,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val activity = param.thisObject as? Activity ?: return@withReentrancyGuard
                        if (activity.isFinishing || activity.isDestroyed) return@withReentrancyGuard
                        activeActivityRef.set(WeakReference(activity))
                        registerCrossProcessReceiver(activity)
                        attachOrUpdateOverlay()
                        scheduleWebViewPoll()
                    }
                }
            }
        )

        safeHookMethod(
            Activity::class.java,
            "onConfigurationChanged",
            Configuration::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val activity = param.thisObject as? Activity ?: return@withReentrancyGuard
                        if (activeActivityRef.get().get() === activity) {
                            attachOrUpdateOverlay()
                        }
                    }
                }
            }
        )

        safeHookMethod(
            Activity::class.java,
            "onDestroy",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val destroyed = param.thisObject as? Activity ?: return@withReentrancyGuard
                        if (activeActivityRef.get().get() === destroyed) {
                            unregisterCrossProcessReceiver(destroyed)
                            activeActivityRef.set(WeakReference(null))
                            overlayContainerRef.set(WeakReference(null))
                        }
                        unhookRefs.removeIf { it.get() == null }
                    }
                }
            }
        )
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerCrossProcessReceiver(activity: Activity) {
        if (receiverRef.get().get() != null) return
        safeRun("registerReceiver") {
            val appCtx = activity.applicationContext ?: return@safeRun
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    safeRun("onReceiveQuotaSync") {
                        if (intent?.action != ACTION_QUOTA_SYNC) return@safeRun
                        val prev = latestSnapshot.get()
                        val five = intent.getStringExtra("five") ?: prev.fiveHourQuota
                        val week = intent.getStringExtra("week") ?: prev.weeklyLimit
                        val reset = intent.getStringExtra("reset") ?: prev.resetTimer
                        val src = intent.getStringExtra("src") ?: "IPC"
                        applyLocalSnapshot(five, week, reset, src)
                    }
                }
            }
            val filter = IntentFilter(ACTION_QUOTA_SYNC)
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appCtx.registerReceiver(receiver, filter)
            }
            receiverRef.set(WeakReference(receiver))
        }
    }

    private fun unregisterCrossProcessReceiver(activity: Activity) {
        val receiver = receiverRef.get().get() ?: return
        safeRun("unregisterReceiver") {
            activity.applicationContext?.unregisterReceiver(receiver)
        }
        receiverRef.set(WeakReference(null))
    }

    // =========================================================================
    // Fallback Network Interception: OkHttp3, Retrofit2, Okio, Cronet, JSON
    // =========================================================================

    private fun hookOkHttpAndRetrofit(classLoader: ClassLoader?) {
        // 1. OkHttp3 ResponseBody.string()
        val responseBodyCls = findClassSafe("okhttp3.ResponseBody", classLoader)
        safeHookMethod(
            responseBodyCls,
            "string",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val bodyStr = param.result as? String ?: return@withReentrancyGuard
                        if (bodyStr.length in 8..262_144 && containsTargetKeyword(bodyStr)) {
                            parseRawPayload(bodyStr, "OkHttp")
                        }
                    }
                }
            }
        )

        // 2. OkHttp3 RealCall.execute() & enqueue(Callback) using non-destructive peekBody()
        val realCallClasses = listOfNotNull(
            findClassSafe("okhttp3.internal.connection.RealCall", classLoader),
            findClassSafe("okhttp3.RealCall", classLoader)
        )
        for (callCls in realCallClasses) {
            safeHookAllMethods(
                callCls,
                "execute",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        withReentrancyGuard {
                            inspectOkHttpResponse(param.result, "OkHttpExec")
                        }
                    }
                }
            )

            safeHookAllMethods(
                callCls,
                "enqueue",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        withReentrancyGuard {
                            val callbackObj = param.args.firstOrNull() ?: return@withReentrancyGuard
                            hookOkHttpAsyncCallbackClass(callbackObj.javaClass)
                        }
                    }
                }
            )
        }

        // 3. Retrofit2 Response.success(...)
        val retrofitResponseCls = findClassSafe("retrofit2.Response", classLoader)
        safeHookAllMethods(
            retrofitResponseCls,
            "success",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val bodyArg = param.args.firstOrNull()
                        if (bodyArg != null) {
                            val str = bodyArg.toString()
                            if (str.length in 8..65_536 && containsTargetKeyword(str)) {
                                parseRawPayload(str, "Retrofit")
                            }
                        }
                        val rawResp = param.args.lastOrNull()
                        if (rawResp != null && rawResp !== bodyArg) {
                            inspectOkHttpResponse(rawResp, "RetrofitRaw")
                        }
                    }
                }
            }
        )
    }

    private fun hookOkHttpAsyncCallbackClass(callbackClass: Class<*>) {
        val name = callbackClass.name
        if (hookedDynamicClasses.size > 64 || !hookedDynamicClasses.add(name)) return
        safeHookAllMethods(
            callbackClass,
            "onResponse",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val responseObj = param.args.getOrNull(1) ?: return@withReentrancyGuard
                        inspectOkHttpResponse(responseObj, "OkHttpAsync")
                    }
                }
            }
        )
    }

    /**
     * Uses OkHttp's non-destructive Response.peekBody(byteCount) so the underlying
     * BufferedSource stream is NEVER consumed or closed for the host application.
     */
    private fun inspectOkHttpResponse(responseObj: Any?, source: String) {
        if (responseObj == null) return
        try {
            val peekedBody = XposedHelpers.callMethod(responseObj, "peekBody", 131_072L) ?: return
            val content = XposedHelpers.callMethod(peekedBody, "string") as? String ?: return
            if (content.length in 8..262_144 && containsTargetKeyword(content)) {
                parseRawPayload(content, source)
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookOkioBuffer(classLoader: ClassLoader?) {
        val bufferCls = findClassSafe("okio.Buffer", classLoader) ?: return
        safeHookMethod(
            bufferCls,
            "readUtf8",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val str = param.result as? String ?: return@withReentrancyGuard
                        if (str.length in 12..262_144 && containsTargetKeyword(str)) {
                            parseRawPayload(str, "Okio")
                        }
                    }
                }
            }
        )
    }

    /**
     * Safely hooks concrete Cronet UrlRequest.Callback subclasses without touching global ByteBuffer.flip().
     */
    private fun hookCronetCallbacks(classLoader: ClassLoader?) {
        val cronetEngineCls = findClassSafe("org.chromium.net.CronetEngine", classLoader)
            ?: findClassSafe("org.chromium.net.ExperimentalCronetEngine", classLoader)
            ?: return

        safeHookAllMethods(
            cronetEngineCls,
            "newUrlRequestBuilder",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val callbackObj = param.args.getOrNull(1) ?: return@withReentrancyGuard
                        val cbClass = callbackObj.javaClass
                        if (hookedDynamicClasses.size <= 64 && hookedDynamicClasses.add(cbClass.name)) {
                            safeHookAllMethods(
                                cbClass,
                                "onReadCompleted",
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(cbParam: MethodHookParam) {
                                        withReentrancyGuard {
                                            val buf = cbParam.args.getOrNull(2) as? ByteBuffer ?: return@withReentrancyGuard
                                            inspectCronetBufferSlice(buf)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        )
    }

    private fun inspectCronetBufferSlice(buf: ByteBuffer) {
        try {
            val dup = buf.duplicate()
            if (dup.position() > 0) dup.flip()
            val rem = dup.remaining()
            if (rem !in 16..131_072) return
            val bytes = ByteArray(rem)
            dup.get(bytes)
            val text = String(bytes, StandardCharsets.UTF_8)
            if (containsTargetKeyword(text)) {
                parseRawPayload(text, "Cronet")
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookJsonParsers(classLoader: ClassLoader?) {
        safeHookConstructor(
            JSONObject::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val raw = param.args.firstOrNull() as? String ?: return@withReentrancyGuard
                        if (raw.length in 8..262_144 && containsTargetKeyword(raw)) {
                            val json = param.thisObject as? JSONObject ?: return@withReentrancyGuard
                            inspectJsonObject(json, "JSON")
                        }
                    }
                }
            }
        )

        safeHookConstructor(
            JSONTokener::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val raw = param.args.firstOrNull() as? String ?: return@withReentrancyGuard
                        if (raw.length in 8..262_144 && containsTargetKeyword(raw)) {
                            parseRawPayload(raw, "Tokener")
                        }
                    }
                }
            }
        )

        val gsonClass = findClassSafe("com.google.gson.Gson", classLoader)
        safeHookAllMethods(
            gsonClass,
            "fromJson",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val input = param.args.firstOrNull() as? String ?: return@withReentrancyGuard
                        if (input.length in 8..262_144 && containsTargetKeyword(input)) {
                            parseRawPayload(input, "Gson")
                        }
                    }
                }
            }
        )
    }

    // =========================================================================
    // Framework Compose / View / WebView UI Fallbacks
    // =========================================================================

    private fun hookComposeAndViewText() {
        safeHookAllMethods(
            BoringLayout::class.java,
            "isBoring",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val cs = param.args.firstOrNull() as? CharSequence ?: return@withReentrancyGuard
                        if (cs.length in 3..180) inspectUiText(cs.toString(), "Compose")
                    }
                }
            }
        )

        val staticBuilderCls = findClassSafe("android.text.StaticLayout\$Builder", null)
        safeHookMethod(
            staticBuilderCls,
            "obtain",
            CharSequence::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            TextPaint::class.java,
            Int::class.javaPrimitiveType!!,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val cs = param.args.firstOrNull() as? CharSequence ?: return@withReentrancyGuard
                        if (cs.length in 3..260) inspectUiText(cs.toString(), "Compose")
                    }
                }
            }
        )

        safeHookMethod(
            TextView::class.java,
            "setText",
            CharSequence::class.java,
            TextView.BufferType::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val tv = param.thisObject as? TextView ?: return@withReentrancyGuard
                        val tagStr = tv.tag as? String
                        if (tagStr != null && tagStr.startsWith("bard_quota_hud")) return@withReentrancyGuard
                        val cs = param.args.firstOrNull() as? CharSequence ?: return@withReentrancyGuard
                        if (cs.length in 3..200) inspectUiText(cs.toString(), "View")
                    }
                }
            }
        )
    }

    private fun hookWebViewDomSniffer() {
        safeHookMethod(
            View::class.java,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val wv = param.thisObject as? WebView ?: return@withReentrancyGuard
                        activeWebViewRef.set(WeakReference(wv))
                        scheduleWebViewPoll()
                    }
                }
            }
        )
    }

    private fun scheduleWebViewPoll() {
        postToMain(delayMs = 1100L) {
            val wv = activeWebViewRef.get().get() ?: return@postToMain
            if (!wv.isShown) return@postToMain
            safeRun("evaluateJavascript") {
                wv.evaluateJavascript(
                    "(function(){return document.body ? document.body.innerText : '';})()"
                ) { rawJsString ->
                    safeRun("webViewCallback") {
                        if (!rawJsString.isNullOrBlank() && rawJsString != "null") {
                            val unescaped = rawJsString.trim('"').replace("\\n", "\n")
                            inspectUiText(unescaped, "WebView")
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Quota Payload Parsing & Compact Metric Normalization
    // =========================================================================

    private fun containsTargetKeyword(text: String): Boolean {
        return text.contains("quota", ignoreCase = true) ||
            text.contains("limit", ignoreCase = true) ||
            text.contains("reset", ignoreCase = true) ||
            text.contains("remaining", ignoreCase = true) ||
            text.contains("小时") ||
            text.contains("限额") ||
            text.contains("额度") ||
            text.contains("重置")
    }

    private fun compactMetricValue(raw: String, kind: MetricKind): String {
        val singleLine = raw.replace(Regex("\\s+"), " ").trim()
        if (singleLine.isEmpty()) return "--"

        when (kind) {
            MetricKind.FIVE_HOUR, MetricKind.WEEKLY -> {
                ratioRegex.find(singleLine)?.value?.replace(" ", "")?.let { return it }
                numberWithUnitRegex.find(singleLine)?.value?.let { return it.take(14) }
            }
            MetricKind.RESET -> {
                compactTimeRegex.find(singleLine)?.value?.trim()?.let { return it }
            }
        }

        val cleaned = singleLine
            .replace(Regex("(?i)^(5[-\\s]?(hour|hr|h)|weekly|reset\\s*(timer|in|at)?|5\\s*小时额度|每周限额|重置时间)\\s*[:：]?\\s*"), "")
            .trim()
        val finalStr = if (cleaned.isNotEmpty()) cleaned else singleLine
        return if (finalStr.length > 16) finalStr.take(15) + "…" else finalStr
    }

    private fun inspectUiText(block: String, source: String) {
        if (block.contains("5h:") && (block.contains("· 周:") || block.contains("· Wk:"))) return
        val prev = latestSnapshot.get()
        var fiveHour = prev.fiveHourQuota
        var weekly = prev.weeklyLimit
        var reset = prev.resetTimer
        var hit = false

        for (rawLine in block.lineSequence()) {
            val line = rawLine.trim()
            if (line.length !in 3..140) continue
            if (fiveHourLineRegex.matches(line)) {
                fiveHour = compactMetricValue(line, MetricKind.FIVE_HOUR)
                hit = true
            }
            if (weeklyLineRegex.matches(line)) {
                weekly = compactMetricValue(line, MetricKind.WEEKLY)
                hit = true
            }
            if (resetLineRegex.matches(line)) {
                reset = compactMetricValue(line, MetricKind.RESET)
                hit = true
            }
        }

        if (hit) publishSnapshotAcrossProcesses(fiveHour, weekly, reset, source)
    }

    private fun parseRawPayload(payload: String, source: String) {
        val cleaned = payload
            .removePrefix(")]}'\n")
            .removePrefix(")]}'")
            .trim()

        // Try direct JSON Object or Google batchexecute JSON Array (e.g. [["wrb.fr", ...]])
        val firstBracket = cleaned.indexOfFirst { it == '{' || it == '[' }
        if (firstBracket in 0..32) {
            val jsonCandidate = cleaned.substring(firstBracket)
            try {
                when (val parsed = JSONTokener(jsonCandidate).nextValue()) {
                    is JSONObject -> inspectJsonObject(parsed, source)
                    is JSONArray -> {
                        val extracted = mutableMapOf<String, String>()
                        collectFromJson(parsed, extracted, 0)
                        if (extracted.isNotEmpty()) mergeExtractedFields(extracted, source)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        val unescaped = cleaned.replace("\\\"", "\"")
        val extracted = mutableMapOf<String, String>()
        keyRegex.findAll(unescaped).take(40).forEach { match ->
            extracted[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        if (extracted.isNotEmpty()) mergeExtractedFields(extracted, source)
        inspectUiText(unescaped, source)
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
                    if (containsTargetKeyword(key) && value != null && value !is JSONObject && value !is JSONArray) {
                        out[key.lowercase()] = value.toString()
                    } else {
                        collectFromJson(value, out, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until minOf(node.length(), 40)) {
                    val elem = node.opt(i)
                    if (elem is String && elem.length in 2..65_536) {
                        val trimmed = elem.trim()
                        if ((trimmed.startsWith("{") || trimmed.startsWith("[")) && containsTargetKeyword(trimmed)) {
                            try {
                                collectFromJson(JSONTokener(trimmed).nextValue(), out, depth + 1)
                            } catch (_: Throwable) {
                            }
                        } else if (containsTargetKeyword(trimmed)) {
                            inspectUiText(trimmed, "BatchRPC")
                        }
                    } else {
                        collectFromJson(elem, out, depth + 1)
                    }
                }
            }
        }
    }

    private fun mergeExtractedFields(fields: Map<String, String>, source: String) {
        val prev = latestSnapshot.get()
        var fiveHour = prev.fiveHourQuota
        var weekly = prev.weeklyLimit
        var reset = prev.resetTimer

        for ((k, v) in fields) {
            when {
                k.contains("5h") || k.contains("five") || k.contains("window") || k.contains("quota") || k.contains("remaining") ->
                    fiveHour = compactMetricValue(v, MetricKind.FIVE_HOUR)
                k.contains("week") || k.contains("limit") || k.contains("cap") ->
                    weekly = compactMetricValue(v, MetricKind.WEEKLY)
                k.contains("reset") || k.contains("expire") ->
                    reset = compactMetricValue(v, MetricKind.RESET)
            }
        }
        publishSnapshotAcrossProcesses(fiveHour, weekly, reset, source)
    }

    private fun publishSnapshotAcrossProcesses(fiveHour: String, weekly: String, reset: String, source: String) {
        val changed = applyLocalSnapshot(fiveHour, weekly, reset, source)
        if (!changed) return

        safeRun("sendQuotaBroadcast") {
            val appCtx = AndroidAppHelper.currentApplication()
                ?: activeActivityRef.get().get()?.applicationContext
                ?: return@safeRun
            val intent = Intent(ACTION_QUOTA_SYNC).apply {
                setPackage(appCtx.packageName)
                putExtra("five", fiveHour)
                putExtra("week", weekly)
                putExtra("reset", reset)
                putExtra("src", source)
            }
            appCtx.sendBroadcast(intent)
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

    // =========================================================================
    // Refactored HUD UI: Translucent Rounded Pill, Light/Dark Adaptive, Single-Line
    // =========================================================================

    private data class HudPalette(
        val backgroundFill: Int,
        val borderStroke: Int,
        val primaryText: Int,
        val mutedLabelText: Int,
        val accentLangText: Int,
        val dividerColor: Int,
        val liveDotColor: Int,
        val idleDotColor: Int
    )

    private fun resolveDynamicPalette(context: Context): HudPalette {
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightMask == Configuration.UI_MODE_NIGHT_YES
        return if (isDark) {
            HudPalette(
                backgroundFill = Color.argb(218, 24, 24, 27),   // Translucent zinc-900 (~85%)
                borderStroke = Color.argb(64, 255, 255, 255),   // Crisp light border
                primaryText = Color.parseColor("#F8FAFC"),      // High-contrast slate-50
                mutedLabelText = Color.parseColor("#94A3B8"),   // Slate-400 labels
                accentLangText = Color.parseColor("#38BDF8"),   // Sky-400 interactive tag
                dividerColor = Color.argb(56, 255, 255, 255),
                liveDotColor = Color.parseColor("#34D399"),     // Emerald-400
                idleDotColor = Color.parseColor("#FBBF24")      // Amber-400
            )
        } else {
            HudPalette(
                backgroundFill = Color.argb(230, 255, 255, 255), // Translucent frosted white (~90%)
                borderStroke = Color.argb(42, 15, 23, 42),       // Subtle slate-900 hairline
                primaryText = Color.parseColor("#0F172A"),       // High-contrast slate-900
                mutedLabelText = Color.parseColor("#475569"),    // Slate-600 labels
                accentLangText = Color.parseColor("#0284C7"),    // Sky-600 interactive tag
                dividerColor = Color.argb(38, 15, 23, 42),
                liveDotColor = Color.parseColor("#059669"),      // Emerald-600
                idleDotColor = Color.parseColor("#D97706")       // Amber-600
            )
        }
    }

    private fun dp(context: Context, value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        ).roundToInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createHudContainer(activity: Activity): LinearLayout {
        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        val screenWidth = activity.resources.displayMetrics.widthPixels

        val container = LinearLayout(activity).apply {
            tag = OVERLAY_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = dp(activity, 11f)
            val padV = dp(activity, 6f)
            setPadding(padH, padV, padH, padV)
            elevation = dp(activity, 6f).toFloat()
            translationX = savedOffsetX
            translationY = savedOffsetY
        }

        val statusDot = View(activity).apply {
            tag = TAG_DOT
            val size = dp(activity, 6f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(activity, 7f)
            }
        }

        val metricsText = TextView(activity).apply {
            tag = TAG_METRICS
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            maxWidth = (screenWidth * 0.76f).roundToInt()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
        }

        val divider = View(activity).apply {
            tag = TAG_DIVIDER
            layoutParams = LinearLayout.LayoutParams(dp(activity, 1f), dp(activity, 12f)).apply {
                marginStart = dp(activity, 8f)
                marginEnd = dp(activity, 7f)
            }
        }

        val langBadge = TextView(activity).apply {
            tag = TAG_LANG
            isSingleLine = true
            maxLines = 1
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        container.addView(statusDot)
        container.addView(metricsText)
        container.addView(divider)
        container.addView(langBadge)

        var downRawX = 0f
        var downRawY = 0f
        var startTransX = 0f
        var startTransY = 0f
        var downTime = 0L
        var dragging = false

        container.setOnTouchListener { v, event ->
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
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
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
                        bindHudContent(activity, container as LinearLayout)
                        scheduleWebViewPoll()
                    }
                    true
                }
                else -> false
            }
        }

        return container
    }

    private fun bindHudContent(activity: Activity, container: LinearLayout) {
        val palette = resolveDynamicPalette(activity)
        val snap = latestSnapshot.get()

        container.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(activity, 999f).toFloat() // Pill / rounded capsule
            setColor(palette.backgroundFill)
            setStroke(maxOf(1, dp(activity, 1f)), palette.borderStroke)
        }

        val dot = container.findViewWithTag<View>(TAG_DOT)
        dot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (snap.hasLiveData) palette.liveDotColor else palette.idleDotColor)
        }

        val metricsTv = container.findViewWithTag<TextView>(TAG_METRICS)
        val divider = container.findViewWithTag<View>(TAG_DIVIDER)
        val langTv = container.findViewWithTag<TextView>(TAG_LANG)

        metricsTv?.text = buildSingleLineSpannable(snap, currentLang, isCollapsed, palette)

        if (isCollapsed) {
            divider?.visibility = View.GONE
            langTv?.visibility = View.GONE
        } else {
            divider?.visibility = View.VISIBLE
            divider?.setBackgroundColor(palette.dividerColor)
            langTv?.visibility = View.VISIBLE
            langTv?.setTextColor(palette.accentLangText)
            langTv?.text = if (currentLang == Lang.ZH) "ZH" else "EN"
        }
    }

    private fun buildSingleLineSpannable(
        s: QuotaSnapshot,
        lang: Lang,
        collapsed: Boolean,
        palette: HudPalette
    ): CharSequence {
        val sb = SpannableStringBuilder()

        fun appendSegment(label: String, value: String, addSep: Boolean) {
            val lStart = sb.length
            sb.append(label)
            sb.setSpan(
                ForegroundColorSpan(palette.mutedLabelText),
                lStart,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            val vStart = sb.length
            sb.append(value)
            sb.setSpan(
                ForegroundColorSpan(palette.primaryText),
                vStart,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.setSpan(
                StyleSpan(Typeface.BOLD),
                vStart,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            if (addSep) {
                val sepStart = sb.length
                sb.append(" · ")
                sb.setSpan(
                    ForegroundColorSpan(palette.mutedLabelText),
                    sepStart,
                    sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        if (collapsed) {
            appendSegment("5h ", s.fiveHourQuota, false)
            return sb
        }

        if (lang == Lang.ZH) {
            appendSegment("5h ", s.fiveHourQuota, true)
            appendSegment("周 ", s.weeklyLimit, true)
            appendSegment("重置 ", s.resetTimer, false)
        } else {
            appendSegment("5h ", s.fiveHourQuota, true)
            appendSegment("Wk ", s.weeklyLimit, true)
            appendSegment("Rst ", s.resetTimer, false)
        }
        return sb
    }

    private fun attachOrUpdateOverlay() {
        postToMain {
            val activity = activeActivityRef.get().get() ?: return@postToMain
            if (activity.isFinishing || activity.isDestroyed) return@postToMain

            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@postToMain
            var container = root.findViewWithTag<LinearLayout>(OVERLAY_TAG)
                ?: overlayContainerRef.get().get()

            if (container == null || container.parent == null) {
                container = createHudContainer(activity)
                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    setMargins(dp(activity, 12f), dp(activity, 54f), dp(activity, 12f), 0)
                }
                root.addView(container, params)
                overlayContainerRef.set(WeakReference(container))
            }

            withReentrancyGuard {
                bindHudContent(activity, container)
                container.bringToFront()
            }
        }
    }
}
