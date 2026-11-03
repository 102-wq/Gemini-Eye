package com.example.bardquotahook

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class QuotaHookEntry : IXposedHookLoadPackage {

    companion object {
        private val TARGET_PKGS = setOf(
            "com.google.android.apps.bard",
            "com.google.android.googlequicksearchbox"
        )
        private const val OVERLAY_TAG = "bard_quota_overlay_view"

        private val mainHandler = Handler(Looper.getMainLooper())
        private val activeActivityRef = AtomicReference<WeakReference<Activity>>(WeakReference(null))
        private val overlayViewRef = AtomicReference<WeakReference<TextView>>(WeakReference(null))
        private val activeWebViewRef = AtomicReference<WeakReference<WebView>>(WeakReference(null))
        private val unhookRefs = CopyOnWriteArrayList<WeakReference<XC_MethodHook.Unhook>>()

        @Volatile private var currentLang: Lang = Lang.ZH
        @Volatile private var savedOffsetX: Float = 0f
        @Volatile private var savedOffsetY: Float = 0f

        private val latestSnapshot = AtomicReference(QuotaSnapshot())

        private val KEY_REGEX = Regex(
            """["']?([a-zA-Z0-9_]*(?:quota|limit|reset|remaining)[a-zA-Z0-9_]*)["']?\s*[:=]\s*["']?([^"',}\]\n]+)["']?""",
            RegexOption.IGNORE_CASE
        )

        private val FIVE_HOUR_REGEX = Regex(""".*(5\s*(-|\s)?(hour|hr|h|小时)|5h).*""", RegexOption.IGNORE_CASE)
        private val WEEKLY_REGEX = Regex(""".*(weekly|week|每周|周限额|7\s*(-|\s)?(day|天)).*""", RegexOption.IGNORE_CASE)
        private val RESET_REGEX = Regex(""".*(resets?\b|重置|恢复|available\s+(in|at)|后重置).*""", RegexOption.IGNORE_CASE)
    }

    enum class Lang { ZH, EN }

    data class QuotaSnapshot(
        val fiveHourQuota: String = "--",
        val weeklyLimit: String = "--",
        val resetTimer: String = "--",
        val source: String = "init"
    )

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName !in TARGET_PKGS) return
        XposedBridge.log("[BardQuota] Loaded in pkg=${lpparam.packageName} proc=${lpparam.processName}")

        hookActivityLifecycle(lpparam.packageName)
        hookFrameworkStaticLayout()
        hookWebViewDomSniffer()
        hookOrgJson()
        hookGsonIfPresent(lpparam.classLoader)
        hookRawJsonStrings()
    }

    private fun trackUnhook(unhook: XC_MethodHook.Unhook?) {
        if (unhook != null) unhookRefs.add(WeakReference(unhook))
    }

    private fun isGeminiActivity(pkg: String, activity: Activity): Boolean {
        if (pkg == "com.google.android.apps.bard") return true
        val clsName = activity.javaClass.name.lowercase()
        val intentStr = activity.intent?.toString()?.lowercase().orEmpty()
        return clsName.contains("robin") ||
            clsName.contains("bard") ||
            clsName.contains("gemini") ||
            clsName.contains("webview") ||
            intentStr.contains("robin") ||
            intentStr.contains("bard") ||
            intentStr.contains("gemini")
    }

    private fun hookActivityLifecycle(pkg: String) {
        trackUnhook(
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (isGeminiActivity(pkg, activity)) {
                            activeActivityRef.set(WeakReference(activity))
                            attachOrUpdateOverlay()
                            scheduleWebViewPoll()
                        }
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
                            activeActivityRef.set(WeakReference(null))
                            overlayViewRef.set(WeakReference(null))
                        }
                        unhookRefs.removeIf { it.get() == null }
                    }
                }
            )
        )
    }

    private fun hookFrameworkStaticLayout() {
        val builderClass = XposedHelpers.findClassIfExists(
            "android.text.StaticLayout\$Builder",
            null
        ) ?: return

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
                        if (cs.length !in 3..300) return
                        val text = cs.toString()
                        if (text.startsWith("用量监控") || text.startsWith("Usage Limits")) return
                        scanRenderedLines(text, "Compose")
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
        mainHandler.postDelayed({
            val wv = activeWebViewRef.get().get() ?: return@postDelayed
            if (!wv.isShown) return@postDelayed
            runCatching {
                wv.evaluateJavascript(
                    "(function(){return document.body ? document.body.innerText : '';})()"
                ) { rawJsString ->
                    if (!rawJsString.isNullOrBlank() && rawJsString != "null") {
                        val unescaped = rawJsString.trim('"').replace("\\n", "\n")
                        scanRenderedLines(unescaped, "WebView")
                    }
                }
            }
        }, 1200L)
    }

    private fun scanRenderedLines(block: String, source: String) {
        val prev = latestSnapshot.get()
        var fiveHour = prev.fiveHourQuota
        var weekly = prev.weeklyLimit
        var reset = prev.resetTimer
        var hit = false

        for (rawLine in block.lineSequence()) {
            val line = rawLine.trim()
            if (line.length !in 3..120) continue
            if (FIVE_HOUR_REGEX.matches(line)) {
                fiveHour = line
                hit = true
            }
            if (WEEKLY_REGEX.matches(line)) {
                weekly = line
                hit = true
            }
            if (RESET_REGEX.matches(line)) {
                reset = line
                hit = true
            }
        }

        if (hit) updateSnapshot(fiveHour, weekly, reset, source)
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
                when (val input = param.args.firstOrNull()) {
                    is String -> if (containsTargetKeyword(input)) parseRawPayload(input, "Gson")
                    is Reader -> Unit
                }
            }
        }).forEach { trackUnhook(it) }
    }

    private fun hookRawJsonStrings() {
        trackUnhook(
            XposedHelpers.findAndHookConstructor(
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                java.nio.charset.Charset::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val str = param.thisObject as? String ?: return
                        if (str.length in 16..262_144 && containsTargetKeyword(str)) {
                            parseRawPayload(str, "UTF8")
                        }
                    }
                }
            )
        )
    }

    private fun containsTargetKeyword(text: String): Boolean {
        return text.contains("quota", ignoreCase = true) ||
            text.contains("limit", ignoreCase = true) ||
            text.contains("reset", ignoreCase = true)
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
        KEY_REGEX.findAll(cleaned).take(40).forEach { match ->
            extracted[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        if (extracted.isNotEmpty()) mergeExtractedFields(extracted, source)
    }

    private fun mergeExtractedFields(fields: Map<String, String>, source: String) {
        val prev = latestSnapshot.get()
        var fiveHour = prev.fiveHourQuota
        var weekly = prev.weeklyLimit
        var reset = prev.resetTimer

        for ((k, v) in fields) {
            when {
                k.contains("5h") || k.contains("window") || k.contains("quota") -> fiveHour = v
                k.contains("week") || k.contains("limit") -> weekly = v
                k.contains("reset") || k.contains("expire") -> reset = v
            }
        }
        updateSnapshot(fiveHour, weekly, reset, source)
    }

    private fun updateSnapshot(fiveHour: String, weekly: String, reset: String, source: String) {
        val prev = latestSnapshot.get()
        if (prev.fiveHourQuota == fiveHour && prev.weeklyLimit == weekly && prev.resetTimer == reset) return
        XposedBridge.log("[BardQuota][$source] 5h=$fiveHour | week=$weekly | reset=$reset")
        latestSnapshot.set(QuotaSnapshot(fiveHour, weekly, reset, source))
        attachOrUpdateOverlay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachOrUpdateOverlay() {
        mainHandler.post {
            val activity = activeActivityRef.get().get() ?: return@post
            if (activity.isFinishing || activity.isDestroyed) return@post

            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@post
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
                    var dragging = false

                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downRawX = event.rawX
                                downRawY = event.rawY
                                startTransX = v.translationX
                                startTransY = v.translationY
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
                                    currentLang = if (currentLang == Lang.ZH) Lang.EN else Lang.ZH
                                    (v as TextView).text = formatOverlayText(latestSnapshot.get(), currentLang)
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
            badge.text = formatOverlayText(latestSnapshot.get(), currentLang)
        }
    }

    private fun formatOverlayText(s: QuotaSnapshot, lang: Lang): String {
        return when (lang) {
            Lang.ZH -> "用量监控 [${s.source} | 点按切EN]\n5小时额度: ${s.fiveHourQuota}\n每周限额: ${s.weeklyLimit}\n重置时间: ${s.resetTimer}"
            Lang.EN -> "Usage Limits [${s.source} | Tap->中文]\n5h Quota: ${s.fiveHourQuota}\nWeekly Limit: ${s.weeklyLimit}\nReset Timer: ${s.resetTimer}"
        }
    }
}
