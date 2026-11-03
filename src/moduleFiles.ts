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
            text = "Gemini-Eye 用量监控 (v1.7.0)"
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
                抗 R8 混淆版 (v1.7.0 · Xposed API 102) 配置说明：
                
                1. 在 LSPosed 作用域页面点击右上角「⋮」->「隐藏」-> 取消勾选「系统应用」。
                2. 同时勾选「Gemini」与「Google (com.google.android.googlequicksearchbox)」。
                3. 在系统设置中「强行停止」Google 应用，再点击下方按钮启动 Gemini。
                4. 进入 Gemini「设置 -> 用量限制 (Usage Limits)」后，底层 JSONObject / String 构造器与界面层会自动捕获 quota / remaining 并实时刷新单行悬浮胶囊。
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

export const QUOTA_HUD_LAYOUT_KT = `package com.example.bardquotahook

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

object QuotaHudLayout {

    const val OVERLAY_TAG = "bard_quota_hud_container"
    private const val TAG_DOT = "bard_quota_hud_dot"
    private const val TAG_METRICS = "bard_quota_hud_metrics"
    private const val TAG_DIVIDER = "bard_quota_hud_divider"
    private const val TAG_LANG = "bard_quota_hud_lang"

    enum class Lang { ZH, EN }

    data class Snapshot(
        val fiveHourQuota: String = "--",
        val weeklyLimit: String = "--",
        val resetTimer: String = "--",
        val source: String = "init"
    ) {
        val hasLiveData: Boolean
            get() = fiveHourQuota != "--" || weeklyLimit != "--" || resetTimer != "--"
    }

    private data class Palette(
        val backgroundFill: Int,
        val borderStroke: Int,
        val primaryText: Int,
        val mutedLabelText: Int,
        val accentLangText: Int,
        val dividerColor: Int,
        val liveDotColor: Int,
        val idleDotColor: Int
    )

    @Volatile var currentLang: Lang = Lang.ZH
    @Volatile var isCollapsed: Boolean = false
    @Volatile var savedOffsetX: Float = 0f
    @Volatile var savedOffsetY: Float = 0f

    private fun resolvePalette(context: Context): Palette {
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightMask == Configuration.UI_MODE_NIGHT_YES
        return if (isDark) {
            Palette(
                backgroundFill = Color.argb(214, 24, 24, 27),    // Translucent dark (#18181B @ 84%)
                borderStroke = Color.argb(64, 255, 255, 255),    // Subtle light hairline
                primaryText = Color.parseColor("#F8FAFC"),       // High-contrast light text
                mutedLabelText = Color.parseColor("#94A3B8"),    // Muted slate-400 label
                accentLangText = Color.parseColor("#38BDF8"),    // Sky-400 badge
                dividerColor = Color.argb(56, 255, 255, 255),
                liveDotColor = Color.parseColor("#34D399"),
                idleDotColor = Color.parseColor("#FBBF24")
            )
        } else {
            Palette(
                backgroundFill = Color.argb(226, 255, 255, 255), // Translucent frosted white (~89%)
                borderStroke = Color.argb(42, 15, 23, 42),       // Subtle dark hairline
                primaryText = Color.parseColor("#0F172A"),       // High-contrast dark text
                mutedLabelText = Color.parseColor("#475569"),    // Muted slate-600 label
                accentLangText = Color.parseColor("#0284C7"),    // Sky-600 badge
                dividerColor = Color.argb(38, 15, 23, 42),
                liveDotColor = Color.parseColor("#059669"),
                idleDotColor = Color.parseColor("#D97706")
            )
        }
    }

    fun dp(context: Context, value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        ).roundToInt()
    }

    fun defaultLayoutParams(context: Context): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            setMargins(dp(context, 12f), dp(context, 52f), dp(context, 12f), 0)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun createContainer(
        activity: Activity,
        snapshotProvider: () -> Snapshot,
        onTapRefresh: () -> Unit
    ): LinearLayout {
        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        val maxMetricsWidth = (activity.resources.displayMetrics.widthPixels * 0.78f).roundToInt()

        val container = LinearLayout(activity).apply {
            tag = OVERLAY_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 11f), dp(activity, 6f), dp(activity, 11f), dp(activity, 6f))
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
            setSingleLine(true)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            maxWidth = maxMetricsWidth
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
            setSingleLine(true)
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
                        bind(activity, v as LinearLayout, snapshotProvider())
                        onTapRefresh()
                    }
                    true
                }
                else -> false
            }
        }

        return container
    }

    fun bind(context: Context, container: LinearLayout, snapshot: Snapshot) {
        val palette = resolvePalette(context)

        container.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 20f).toFloat()
            setColor(palette.backgroundFill)
            setStroke(maxOf(1, dp(context, 1f)), palette.borderStroke)
        }

        container.findViewWithTag<View>(TAG_DOT)?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (snapshot.hasLiveData) palette.liveDotColor else palette.idleDotColor)
        }

        val metricsTv = container.findViewWithTag<TextView>(TAG_METRICS)
        val divider = container.findViewWithTag<View>(TAG_DIVIDER)
        val langTv = container.findViewWithTag<TextView>(TAG_LANG)

        metricsTv?.text = buildSingleLineSpannable(snapshot, currentLang, isCollapsed, palette)

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
        s: Snapshot,
        lang: Lang,
        collapsed: Boolean,
        palette: Palette
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
                logDebug("Hook callback err: \${t.javaClass.simpleName}: \${t.message}")
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
                        logDebug("Main runnable err: \${t.message}")
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
                """["']?([a-zA-Z0-9_]*(?:quota|remaining|limit|reset|cap|usage|window)[a-zA-Z0-9_]*)["']?\\s*[:=]\\s*["']?([^"',}\\]\\n]{1,40})["']?""",
                RegexOption.IGNORE_CASE
            )
        }

        private val ratioOrPctRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex("""(\\d+\\s*/\\s*\\d+|\\d+(?:\\.\\d+)?%)""")
        }

        private val countMetricRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex("""(\\d+\\s*(?:次|条|left|remaining|used|prompts?|messages?|queries))""", RegexOption.IGNORE_CASE)
        }

        private val standaloneMetricRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex("""^(?:已用\\s*|剩余\\s*)?(\\d+\\s*/\\s*\\d+|\\d+(?:\\.\\d+)?%)(?:\\s*(?:used|remaining|left|已用|剩余))?\$""", RegexOption.IGNORE_CASE)
        }

        private val compactTimeRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """(\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AP]M)?|\\d+\\s*(?:h|hr|hrs|小时|时)\\s*\\d*\\s*(?:m|min|mins|分钟|分)?|\\d+\\s*(?:m|min|mins|分钟|分))""",
                RegexOption.IGNORE_CASE
            )
        }

        private val fiveHourLabelRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(5\\s*(-|\\s)?(hour|hr|h|小时)|5h|current\\s+(?:session|window|cycle)|当前周期|短周期|remaining\\s*prompts?|剩余\\s*\\d+).*""",
                RegexOption.IGNORE_CASE
            )
        }

        private val weeklyLabelRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(weekly|week|每周|周限额|周上限|周额度|7\\s*(-|\\s)?(day|天)).*""",
                RegexOption.IGNORE_CASE
            )
        }

        private val resetLabelRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Regex(
                """.*(resets?\\b|重置|恢复|refreshes?\\s+in|available\\s+(in|at)|try\\s+again\\s+(in|after|at)|后重置).*""",
                RegexOption.IGNORE_CASE
            )
        }

        private fun logInfo(msg: String) {
            try {
                XposedBridge.log("[\$TAG] \$msg")
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
            logInfo("initZygote -> \${startupParam.modulePath}")
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        safeTry("handleLoadPackage") {
            val pkg = lpparam.packageName ?: return@safeTry
            if (pkg == "android" || pkg == "com.example.bardquotahook") return@safeTry
            logInfo("Loaded pkg=\$pkg proc=\${lpparam.processName}")

            safeTry("hookJsonConstructors") { hookJsonConstructors() }
            safeTry("hookStringConstructors") { hookStringConstructors() }
            safeTry("hookCharsetDecoder") { hookCharsetDecoder() }

            safeTry("hookActivityLifecycle") { hookActivityLifecycle() }
            safeTry("hookUiTextLayouts") { hookUiTextLayouts() }
            safeTry("hookWebViewDom") { hookWebViewDom() }
        }
    }

    private inline fun safeTry(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logDebug("\$tag failed safely: \${t.javaClass.simpleName}: \${t.message}")
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
            logDebug("Hook method \${clazz.name}.\$method skipped: \${t.javaClass.simpleName}")
        }
    }

    private fun safeHookAllMethods(clazz: Class<*>?, method: String, callback: XC_MethodHook) {
        if (clazz == null) return
        try {
            XposedBridge.hookAllMethods(clazz, method, callback)?.forEach { trackUnhook(it) }
        } catch (t: Throwable) {
            logDebug("HookAllMethods \${clazz.name}.\$method skipped: \${t.javaClass.simpleName}")
        }
    }

    private fun safeHookConstructor(clazz: Class<*>?, vararg paramsAndCallback: Any) {
        if (clazz == null) return
        try {
            trackUnhook(XposedHelpers.findAndHookConstructor(clazz, *paramsAndCallback))
        } catch (t: Throwable) {
            logDebug("Hook ctor \${clazz.name} skipped: \${t.javaClass.simpleName}")
        }
    }

    private fun hookJsonConstructors() {
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
        safeHookConstructor(
            String::class.java,
            CharArray::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            stringHook
        )
    }

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

        val staticBuilderCls = findClassSafe("android.text.StaticLayout\\$Builder", null)
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
                            val unescaped = rawJsString.trim('"').replace("\\\\n", "\\n")
                            inspectUiText(unescaped, "WebView")
                        }
                    }
                }
            }
        }
    }

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
        val singleLine = raw.replace(Regex("\\\\s+"), " ").trim()
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
            .removePrefix(")]}'\\n")
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

        val unescaped = cleaned.replace("\\\\\\"", "\\"")
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
            fiveHour = "\$remainingVal/\$totalLimitVal"
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
        logInfo("[\$source] 5h=\$fiveHour | week=\$weekly | reset=\$reset")
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
    descriptionZh: '声明 Google、MavenCentral (LibXposed API 102) 与 Xposed 官方源 (api.xposed.info)。',
    descriptionEn: 'Configures Google, MavenCentral (for LibXposed API 102), and api.xposed.info.',
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
    descriptionZh: '应用模块构建脚本 (v1.7.0)：升级 Xposed API 102 (io.github.libxposed:api:102.0.0) 并兼容 Bridge API 82。',
    descriptionEn: 'App module script (v1.7.0) upgraded to Xposed API 102 (io.github.libxposed:api:102.0.0) + Bridge API 82.',
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
        versionCode = 8
        versionName = "1.7.0"
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

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    // Modern Xposed / LibXposed API 102 (Maven Central)
    compileOnly("io.github.libxposed:api:102.0.0")
    // Legacy Xposed Bridge API (82 is the latest version in https://api.xposed.info/)
    compileOnly("de.robv.android.xposed:api:82")
}
`,
  },
  {
    path: 'app/proguard-rules.pro',
    label: 'app/proguard-rules.pro',
    language: 'pro',
    descriptionZh: '保留 QuotaHookEntry、QuotaHudLayout 与 MainActivity，防止 R8 混淆移除。',
    descriptionEn: 'Preserves QuotaHookEntry, QuotaHudLayout, and MainActivity during R8 single-DEX merge.',
    content: `-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.**
-dontwarn io.github.libxposed.annotation.**

-keep public class com.example.bardquotahook.QuotaHookEntry {
    public <init>();
    *;
}
-keep public class com.example.bardquotahook.MainActivity {
    public <init>();
    *;
}
-keep class com.example.bardquotahook.** {
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
            android:value="Gemini Usage Limits Sniffer &amp; Adaptive Single-Line HUD (ZH/EN)" />
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
    descriptionZh: '模块设置引导页 (v1.7.0)：兼容 Android 11+/13+ 包可见性检测与四级回退启动 Gemini。',
    descriptionEn: 'Module launcher & status UI (v1.7.0) with Android 11+/13+ package visibility check and 4-tier Gemini launch fallback.',
    content: MAIN_ACTIVITY_KT,
  },
  {
    path: 'app/src/main/java/com/example/bardquotahook/QuotaHudLayout.kt',
    label: 'QuotaHudLayout.kt',
    language: 'kotlin',
    descriptionZh: '单行横向自适应 HUD 布局代码：半透明圆角背景、深浅色高对比度动态主题、拖拽与点按中英切换。',
    descriptionEn: 'Single-line horizontal HUD layout: translucent rounded background, dynamic Light/Dark colors, drag & ZH/EN toggle.',
    content: QUOTA_HUD_LAYOUT_KT,
  },
  {
    path: 'app/src/main/java/com/example/bardquotahook/QuotaHookEntry.kt',
    label: 'QuotaHookEntry.kt',
    language: 'kotlin',
    descriptionZh: '抗 R8 混淆核心 Hook (v1.7.0)：JSONObject + String(byte[]/char[]) 构造器拦截 + 状态化标签配对 + 全反射 Throwable 防崩保护。',
    descriptionEn: 'R8-proof Xposed hook (v1.7.0): JSONObject + String(byte[]/char[]) constructors, stateful label pairing, and Throwable guards.',
    content: QUOTA_HOOK_KT,
  },
];
