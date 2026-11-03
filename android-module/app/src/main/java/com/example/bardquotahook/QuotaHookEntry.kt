package com.example.bardquotahook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.BoringLayout
import android.text.TextPaint
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
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
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class QuotaHookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {

    companion object {
        private const val TAG = "BardQuota"
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
                logDebug("Hook callback err: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                inHookGuard.set(false)
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
                        logDebug("Main runnable err: ${t.message}")
                    }
                }
                if (delayMs > 0L) handler.postDelayed(runnable, delayMs) else handler.post(runnable)
            } catch (_: Throwable) {
            }
        }

        private val activeActivityRef = AtomicReference<WeakReference<Activity>>(WeakReference(null))
        private val overlayContainerRef = AtomicReference<WeakReference<LinearLayout>>(WeakReference(null))
        private val activeWebViewRef = AtomicReference<WeakReference<WebView>>(WeakReference(null))
        private val receiverRef = AtomicReference<WeakReference<BroadcastReceiver>>(WeakReference(null))
        private val unhookRefs = CopyOnWriteArrayList<WeakReference<XC_MethodHook.Unhook>>()

        private val latestSnapshot = AtomicReference(QuotaHudLayout.Snapshot())

        @Volatile private var pendingSlot: MetricKind? = null
        @Volatile private var pendingSlotTimestampMs: Long = 0L

        private val keyRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """["']?([a-zA-Z0-9_]*(?:quota|remaining|limit|reset|cap|usage|window)[a-zA-Z0-9_]*)["']?\s*[:=]\s*["']?([^"',}\]\n]{1,40})["']?""",
                RegexOption.IGNORE_CASE
            )
        }

        private val ratioOrPctRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex("""(\d+\s*/\s*\d+|\d+(?:\.\d+)?%)""")
        }

        private val countMetricRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex("""(\d+\s*(?:次|条|left|remaining|used|prompts?|messages?|queries))""", RegexOption.IGNORE_CASE)
        }

        private val standaloneMetricRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex("""^(?:已用\s*|剩余\s*)?(\d+\s*/\s*\d+|\d+(?:\.\d+)?%)(?:\s*(?:used|remaining|left|已用|剩余))?$""", RegexOption.IGNORE_CASE)
        }

        private val compactTimeRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """(\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AP]M)?|\d+\s*(?:h|hr|hrs|小时|时)\s*\d*\s*(?:m|min|mins|分钟|分)?|\d+\s*(?:m|min|mins|分钟|分))""",
                RegexOption.IGNORE_CASE
            )
        }

        private val fiveHourLabelRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(5\s*(-|\s)?(hour|hr|h|小时)|5h|current\s+(?:session|window|cycle)|当前周期|短周期|remaining\s*prompts?|剩余\s*\d+).*""",
                RegexOption.IGNORE_CASE
            )
        }

        private val weeklyLabelRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(weekly|week|每周|周限额|周上限|周额度|7\s*(-|\s)?(day|天)).*""",
                RegexOption.IGNORE_CASE
            )
        }

        private val resetLabelRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(resets?\b|重置|恢复|refreshes?\s+in|available\s+(in|at)|try\s+again\s+(in|after|at)|后重置).*""",
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

    private enum class MetricKind { FIVE_HOUR, WEEKLY, RESET }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        safeTry("initZygote") {
            logInfo("initZygote -> ${startupParam.modulePath}")
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        safeTry("handleLoadPackage") {
            val pkg = lpparam.packageName ?: return@safeTry
            if (pkg == "android" || pkg == "com.example.bardquotahook") return@safeTry
            logInfo("Loaded pkg=$pkg proc=${lpparam.processName}")

            // 1. R8-Immune Payload Interception: org.json.JSONObject + java.lang.String Constructors + CharsetDecoder
            safeTry("hookJsonConstructors") { hookJsonConstructors() }
            safeTry("hookStringConstructors") { hookStringConstructors() }
            safeTry("hookCharsetDecoder") { hookCharsetDecoder() }

            // 2. Framework UI & Lifecycle Hooks (Compose / TextView / WebView + Dynamic HUD)
            safeTry("hookActivityLifecycle") { hookActivityLifecycle() }
            safeTry("hookUiTextLayouts") { hookUiTextLayouts() }
            safeTry("hookWebViewDom") { hookWebViewDom() }
        }
    }

    // =========================================================================
    // Safe Reflection Wrappers (Every Reflection / Hook Call Catches Throwable)
    // =========================================================================

    private inline fun safeTry(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logDebug("$tag failed safely: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun findClassSafe(name: String, cl: ClassLoader? = null): Class<*>? {
        return try {
            XposedHelpers.findClass(name, cl)
        } catch (_: Throwable) {
            null
        }
    }

    private fun trackUnhook(unhook: XC_MethodHook.Unhook?) {
        if (unhook != null) unhookRefs.add(WeakReference(unhook))
    }

    private fun safeHookMethod(clazz: Class<*>?, method: String, vararg paramsAndCallback: Any) {
        if (clazz == null) return
        try {
            trackUnhook(XposedHelpers.findAndHookMethod(clazz, method, *paramsAndCallback))
        } catch (t: Throwable) {
            logDebug("Hook method ${clazz.name}.$method skipped: ${t.javaClass.simpleName}")
        }
    }

    private fun safeHookAllMethods(clazz: Class<*>?, method: String, callback: XC_MethodHook) {
        if (clazz == null) return
        try {
            XposedBridge.hookAllMethods(clazz, method, callback)?.forEach { trackUnhook(it) }
        } catch (t: Throwable) {
            logDebug("HookAllMethods ${clazz.name}.$method skipped: ${t.javaClass.simpleName}")
        }
    }

    private fun safeHookConstructor(clazz: Class<*>?, vararg paramsAndCallback: Any) {
        if (clazz == null) return
        try {
            trackUnhook(XposedHelpers.findAndHookConstructor(clazz, *paramsAndCallback))
        } catch (t: Throwable) {
            logDebug("Hook ctor ${clazz.name} skipped: ${t.javaClass.simpleName}")
        }
    }

    // =========================================================================
    // 1. org.json.JSONObject & java.lang.String Constructor Interception
    // =========================================================================

    private fun hookJsonConstructors() {
        // JSONObject(String)
        safeHookConstructor(
            JSONObject::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val raw = param.args.firstOrNull() as? String ?: return
                    if (!fastHasQuotaKeyword(raw)) return
                    withReentrancyGuard {
                        val obj = param.thisObject as? JSONObject ?: return@withReentrancyGuard
                        inspectJsonObject(obj, "JSONObj")
                    }
                }
            }
        )

        // JSONObject(JSONTokener)
        safeHookConstructor(
            JSONObject::class.java,
            JSONTokener::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    withReentrancyGuard {
                        val obj = param.thisObject as? JSONObject ?: return@withReentrancyGuard
                        inspectJsonObject(obj, "JSONTok")
                    }
                }
            }
        )

        // JSONArray(String) — catches Google's batchexecute [["wrb.fr", ...]] envelopes
        safeHookConstructor(
            JSONArray::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val raw = param.args.firstOrNull() as? String ?: return
                    if (!fastHasQuotaKeyword(raw)) return
                    withReentrancyGuard {
                        val arr = param.thisObject as? JSONArray ?: return@withReentrancyGuard
                        val extracted = mutableMapOf<String, String>()
                        collectFromJson(arr, extracted, 0)
                        if (extracted.isNotEmpty()) mergeExtractedFields(extracted, "JSONArr")
                    }
                }
            }
        )
    }

    /**
     * Hooks java.lang.String byte[] and char[] constructors safely.
     * Note: Google's R8-obfuscated Protobuf-Lite (`Utf8.decodeUtf8`) and streaming JSON parsers
     * decode UTF-8 buffers into `char[]` and invoke `String(char[], int, int)` or `String(byte[], ...)`.
     */
    private fun hookStringConstructors() {
        val stringHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val str = param.thisObject as? String ?: return
                if (!fastHasQuotaKeyword(str)) return
                withReentrancyGuard {
                    parseRawPayload(str, "StrCtor")
                }
            }
        }

        // Byte-array constructors
        safeHookConstructor(
            String::class.java,
            ByteArray::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Charset::class.java,
            stringHook
        )
        safeHookConstructor(
            String::class.java,
            ByteArray::class.java,
            Charset::class.java,
            stringHook
        )
        safeHookConstructor(
            String::class.java,
            ByteArray::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            String::class.java,
            stringHook
        )
        safeHookConstructor(
            String::class.java,
            ByteArray::class.java,
            String::class.java,
            stringHook
        )
        safeHookConstructor(
            String::class.java,
            ByteArray::class.java,
            stringHook
        )

        // Char-array slice constructor (used by Protobuf Utf8.decodeUtf8 & Gson/JsonReader)
        safeHookConstructor(
            String::class.java,
            CharArray::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            stringHook
        )
    }

    /**
     * Catches Cronet / Okio / InputStreamReader UTF-8 ByteBuffer decoding via java.nio.charset.CharsetDecoder.
     */
    private fun hookCharsetDecoder() {
        safeHookMethod(
            CharsetDecoder::class.java,
            "decode",
            ByteBuffer::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val cb = param.result as? CharBuffer ?: return
                    if (cb.length !in 6..262_144) return
                    val str = cb.toString()
                    if (!fastHasQuotaKeyword(str)) return
                    withReentrancyGuard {
                        parseRawPayload(str, "Decoder")
                    }
                }
            }
        )
    }

    /**
     * Zero-allocation fast pre-filter before entering re-entrancy lock or regex parsing.
     */
    private fun fastHasQuotaKeyword(text: String): Boolean {
        val len = text.length
        if (len < 6 || len > 262_144) return false
        return text.contains("quota", ignoreCase = true) ||
            text.contains("remaining", ignoreCase = true) ||
            text.contains("limit", ignoreCase = true) ||
            text.contains("reset", ignoreCase = true) ||
            text.contains("5-hour", ignoreCase = true) ||
            text.contains("weekly", ignoreCase = true) ||
            text.contains("小时") ||
            text.contains("剩余") ||
            text.contains("限额") ||
            text.contains("额度") ||
            text.contains("重置")
    }

    // =========================================================================
    // 2. Framework UI / Compose / WebView Fallbacks & Activity Lifecycle
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

    private fun hookUiTextLayouts() {
        safeHookAllMethods(
            BoringLayout::class.java,
            "isBoring",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val cs = param.args.firstOrNull() as? CharSequence ?: return
                    if (cs.length !in 2..180) return
                    withReentrancyGuard {
                        inspectUiText(cs.toString(), "Compose")
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
                    val cs = param.args.firstOrNull() as? CharSequence ?: return
                    if (cs.length !in 2..260) return
                    withReentrancyGuard {
                        inspectUiText(cs.toString(), "Compose")
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
                    val tv = param.thisObject as? TextView ?: return
                    val tagStr = tv.tag as? String
                    if (tagStr != null && tagStr.startsWith("bard_quota_hud")) return
                    val cs = param.args.firstOrNull() as? CharSequence ?: return
                    if (cs.length !in 2..200) return
                    withReentrancyGuard {
                        inspectUiText(cs.toString(), "View")
                    }
                }
            }
        )
    }

    private fun hookWebViewDom() {
        safeHookMethod(
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
    }

    private fun scheduleWebViewPoll() {
        postToMain(delayMs = 1000L) {
            val wv = activeWebViewRef.get().get() ?: return@postToMain
            if (!wv.isShown) return@postToMain
            safeTry("evaluateJavascript") {
                wv.evaluateJavascript(
                    "(function(){return document.body ? document.body.innerText : '';})()"
                ) { rawJsString ->
                    safeTry("webViewDomResult") {
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
    // Cross-Process Broadcast Sync (Background RPC Process -> UI Activity)
    // =========================================================================

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerCrossProcessReceiver(activity: Activity) {
        if (receiverRef.get().get() != null) return
        safeTry("registerReceiver") {
            val appCtx = activity.applicationContext ?: return@safeTry
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    safeTry("onReceiveQuotaSync") {
                        if (intent?.action != ACTION_QUOTA_SYNC) return@safeTry
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
        safeTry("unregisterReceiver") {
            activity.applicationContext?.unregisterReceiver(receiver)
        }
        receiverRef.set(WeakReference(null))
    }

    // =========================================================================
    // Payload & Stateful UI Line Extraction
    // =========================================================================

    private fun extractMetricToken(line: String, kind: MetricKind): String? {
        when (kind) {
            MetricKind.FIVE_HOUR, MetricKind.WEEKLY -> {
                ratioOrPctRegex.find(line)?.value?.replace(" ", "")?.let { return it }
                countMetricRegex.find(line)?.value?.trim()?.let { return it.take(14) }
            }
            MetricKind.RESET -> {
                compactTimeRegex.find(line)?.value?.trim()?.let { return it }
            }
        }
        return null
    }

    private fun normalizeValue(raw: String, kind: MetricKind): String {
        val singleLine = raw.replace(Regex("\\s+"), " ").trim()
        if (singleLine.isEmpty()) return "--"
        extractMetricToken(singleLine, kind)?.let { return it }
        return if (singleLine.length > 15) singleLine.take(14) + "…" else singleLine
    }

    private fun inspectUiText(block: String, source: String) {
        if (block.contains("5h ") && (block.contains("· 周 ") || block.contains("· Wk "))) return
        val prev = latestSnapshot.get()
        var fiveHour = prev.fiveHourQuota
        var weekly = prev.weeklyLimit
        var reset = prev.resetTimer
        var hit = false
        val now = System.currentTimeMillis()

        for (rawLine in block.lineSequence()) {
            val line = rawLine.trim()
            if (line.length !in 2..140) continue

            // Pair standalone metric values (e.g. "42%", "38/50") with a preceding Compose label node
            val activeSlot = pendingSlot
            if (activeSlot != null && (now - pendingSlotTimestampMs) <= 450L) {
                if (activeSlot == MetricKind.RESET) {
                    compactTimeRegex.find(line)?.value?.trim()?.let {
                        reset = it
                        hit = true
                        pendingSlot = null
                        continue
                    }
                } else if (standaloneMetricRegex.matches(line)) {
                    val token = extractMetricToken(line, activeSlot) ?: line.take(12)
                    if (activeSlot == MetricKind.FIVE_HOUR) fiveHour = token else weekly = token
                    hit = true
                    pendingSlot = null
                    continue
                }
            }

            if (fiveHourLabelRegex.matches(line)) {
                val token = extractMetricToken(line, MetricKind.FIVE_HOUR)
                if (token != null) {
                    fiveHour = token
                    hit = true
                    pendingSlot = null
                } else {
                    pendingSlot = MetricKind.FIVE_HOUR
                    pendingSlotTimestampMs = now
                }
            } else if (weeklyLabelRegex.matches(line)) {
                val token = extractMetricToken(line, MetricKind.WEEKLY)
                if (token != null) {
                    weekly = token
                    hit = true
                    pendingSlot = null
                } else {
                    pendingSlot = MetricKind.WEEKLY
                    pendingSlotTimestampMs = now
                }
            } else if (resetLabelRegex.matches(line)) {
                val token = extractMetricToken(line, MetricKind.RESET)
                if (token != null) {
                    reset = token
                    hit = true
                    pendingSlot = null
                } else {
                    pendingSlot = MetricKind.RESET
                    pendingSlotTimestampMs = now
                }
            }
        }

        if (hit) publishSnapshot(fiveHour, weekly, reset, source)
    }

    private fun parseRawPayload(payload: String, source: String) {
        val cleaned = payload
            .removePrefix(")]}'\n")
            .removePrefix(")]}'")
            .trim()

        val firstBracket = cleaned.indexOfFirst { it == '{' || it == '[' }
        if (firstBracket in 0..32) {
            val candidate = cleaned.substring(firstBracket)
            try {
                when (val node = JSONTokener(candidate).nextValue()) {
                    is JSONObject -> inspectJsonObject(node, source)
                    is JSONArray -> {
                        val extracted = mutableMapOf<String, String>()
                        collectFromJson(node, extracted, 0)
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
                    if (fastHasQuotaKeyword(key) && value != null && value !is JSONObject && value !is JSONArray) {
                        out[key.lowercase()] = value.toString()
                    } else {
                        collectFromJson(value, out, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until minOf(node.length(), 40)) {
                    val elem = node.opt(i)
                    if (elem is String && fastHasQuotaKeyword(elem)) {
                        val trimmed = elem.trim()
                        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                            try {
                                collectFromJson(JSONTokener(trimmed).nextValue(), out, depth + 1)
                            } catch (_: Throwable) {
                                parseRawPayload(trimmed, "BatchRPC")
                            }
                        } else {
                            parseRawPayload(trimmed, "BatchRPC")
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

        var remainingVal: String? = null
        var totalLimitVal: String? = null

        for ((k, v) in fields) {
            when {
                k == "remaining" || k.endsWith("_remaining") || k.startsWith("remaining_") -> remainingVal = v
                k == "limit" || k == "quota_limit" || k == "max_quota" -> totalLimitVal = v
                k.contains("5h") || k.contains("five") || k.contains("window") || k.contains("quota") ->
                    fiveHour = normalizeValue(v, MetricKind.FIVE_HOUR)
                k.contains("week") || k.contains("cap") ->
                    weekly = normalizeValue(v, MetricKind.WEEKLY)
                k.contains("reset") || k.contains("expire") ->
                    reset = normalizeValue(v, MetricKind.RESET)
            }
        }

        if (remainingVal != null && totalLimitVal != null && remainingVal.all { it.isDigit() } && totalLimitVal.all { it.isDigit() }) {
            fiveHour = "$remainingVal/$totalLimitVal"
        } else if (remainingVal != null && fiveHour == prev.fiveHourQuota) {
            fiveHour = normalizeValue(remainingVal, MetricKind.FIVE_HOUR)
        } else if (totalLimitVal != null && weekly == prev.weeklyLimit) {
            weekly = normalizeValue(totalLimitVal, MetricKind.WEEKLY)
        }

        publishSnapshot(fiveHour, weekly, reset, source)
    }

    private fun publishSnapshot(fiveHour: String, weekly: String, reset: String, source: String) {
        val changed = applyLocalSnapshot(fiveHour, weekly, reset, source)
        if (!changed) return

        safeTry("sendQuotaBroadcast") {
            val appCtx = AndroidAppHelper.currentApplication()
                ?: activeActivityRef.get().get()?.applicationContext
                ?: return@safeTry
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
        latestSnapshot.set(QuotaHudLayout.Snapshot(fiveHour, weekly, reset, source))
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
                    activity = activity,
                    snapshotProvider = { latestSnapshot.get() },
                    onTapRefresh = { scheduleWebViewPoll() }
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
