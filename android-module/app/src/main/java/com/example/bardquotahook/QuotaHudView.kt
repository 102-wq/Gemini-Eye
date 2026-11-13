package com.example.bardquotahook

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Exact Single-Line Capsule HUD View (`QuotaHudView`).
 *
 * Layout Spec:
 * [ 5h [====---] 15% (1h52m)  |  每周/Weekly [===----] 29% (23h52m)  |  (↻)  (↗) ]
 *
 * Features:
 * - 100% programmatic View + Canvas vector rendering (zero R.drawable host lookup crashes).
 * - Dual i18n (`Lang.ZH`: "5h" / "每周" vs `Lang.EN`: "5h" / "Weekly").
 * - Dual Theme Engine (`ThemeMode.MIUIX` frosted glass pill vs `ThemeMode.MD3` Material You dynamic color).
 * - Touch-slop disambiguated capsule drag vs crisp action button clicks.
 */
@SuppressLint("ViewConstructor")
class QuotaHudView(
    context: Context,
    var onRefreshClick: (() -> Unit)? = null,
    var onOpenUsageClick: (() -> Unit)? = null,
    var onWindowDrag: ((dx: Int, dy: Int) -> Unit)? = null,
    private val draggableInFrame: Boolean = true
) : LinearLayout(context) {

    enum class Lang(val code: String) {
        ZH("ZH"),
        EN("EN");

        companion object {
            fun fromCode(raw: String?): Lang {
                return if (raw.equals("EN", ignoreCase = true)) EN else ZH
            }
        }
    }

    enum class ThemeMode(val code: String) {
        MIUIX("MIUIX"),
        MD3("MD3");

        companion object {
            fun fromCode(raw: String?): ThemeMode {
                return if (raw.equals("MD3", ignoreCase = true)) MD3 else MIUIX
            }
        }
    }

    data class Snapshot(
        val fiveHourQuota: String = "--",
        val fiveHourReset: String = "--",
        val weeklyLimit: String = "--",
        val weeklyReset: String = "--",
        val source: String = "init"
    ) {
        fun sanitized(): Snapshot = QuotaTextParser.sanitizeSnapshot(this)

        val hasLiveData: Boolean
            get() {
                val c = sanitized()
                return c.fiveHourQuota != "--" ||
                    c.weeklyLimit != "--" ||
                    c.fiveHourReset != "--" ||
                    c.weeklyReset != "--"
            }

        val fiveHourProgress: Float
            get() = QuotaTextParser.parseProgressFraction(fiveHourQuota)

        val weeklyProgress: Float
            get() = QuotaTextParser.parseProgressFraction(weeklyLimit)

        fun formatFiveHourDisplay(): String {
            val c = sanitized()
            if (c.fiveHourQuota == "--") {
                return if (c.fiveHourReset != "--") "-- (${c.fiveHourReset})" else "--"
            }
            return if (c.fiveHourReset != "--") "${c.fiveHourQuota} (${c.fiveHourReset})" else c.fiveHourQuota
        }

        fun formatWeeklyDisplay(lang: Lang = Lang.ZH): String {
            val c = sanitized()
            val resetDisplay = QuotaTextParser.formatWeeklyResetForDisplay(c.weeklyReset, lang == Lang.ZH)
            if (c.weeklyLimit == "--") {
                return if (resetDisplay != "--") "-- ($resetDisplay)" else "--"
            }
            return if (resetDisplay != "--") "${c.weeklyLimit} ($resetDisplay)" else c.weeklyLimit
        }
    }

    private data class LocalizedHudLabels(
        val fiveHour: String,
        val weekly: String,
        val refreshDesc: String,
        val openUsageDesc: String
    )

    private data class Palette(
        val bgTop: Int,
        val bgBottom: Int,
        val border: Int,
        val cornerPx: Float,
        val primaryText: Int,
        val labelText: Int,
        val divider: Int,
        val trackColor: Int,
        val barNormal: Int,
        val barWarn: Int,
        val barDanger: Int,
        val iconColor: Int,
        val iconPressedBg: Int,
        val shadowSpot: Int
    )

    private var currentSnapshot: Snapshot = Snapshot()
    var currentLang: Lang = QuotaSyncProvider.defaultLangForDevice()
        private set
    var currentTheme: ThemeMode = ThemeMode.MIUIX
        private set

    private val fiveHourLabelView: TextView
    private val fiveHourBarView: MiniProgressBarView
    private val fiveHourValueView: TextView
    private val dividerOneView: View
    private val weeklyLabelView: TextView
    private val weeklyBarView: MiniProgressBarView
    private val weeklyValueView: TextView
    private val dividerTwoView: View
    private val refreshIconBtn: HudActionIconView
    private val externalLinkBtn: HudActionIconView

    private val touchSlopPx: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var startRightMargin = 0
    private var startTopMargin = 0
    private var isDraggingCapsule = false

    init {
        tag = HUD_VIEW_TAG
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        clipToPadding = false

        val padStart = dp(context, 10f)
        val padEnd = dp(context, 7f)
        val padV = dp(context, 5.5f)
        setPadding(padStart, padV, padEnd, padV)
        elevation = dp(context, 9f).toFloat()

        val labelTypeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val valueTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        // 1. Left Section: "5h" + Mini Bar + "15% (1h52m)"
        fiveHourLabelView = createSingleLineText(
            tagSuffix = "5h_lbl",
            textSp = 10.5f,
            tf = labelTypeface,
            tabular = false
        ).apply {
            text = "5h"
        }

        fiveHourBarView = MiniProgressBarView(context).apply {
            tag = "bard_quota_hud_5h_bar"
            layoutParams = LayoutParams(dp(context, 25f), dp(context, 5f)).apply {
                marginStart = dp(context, 4f)
                marginEnd = dp(context, 4.5f)
            }
        }

        fiveHourValueView = createSingleLineText(
            tagSuffix = "5h_val",
            textSp = 10.5f,
            tf = valueTypeface,
            tabular = true
        )

        dividerOneView = View(context).apply {
            tag = "bard_quota_hud_div1"
            layoutParams = LayoutParams(maxOf(1, dp(context, 1f)), dp(context, 12f)).apply {
                marginStart = dp(context, 6.5f)
                marginEnd = dp(context, 6.5f)
            }
        }

        // 2. Middle Section: "每周" / "Weekly" + Mini Bar + "29% (23h52m)"
        weeklyLabelView = createSingleLineText(
            tagSuffix = "wk_lbl",
            textSp = 10.5f,
            tf = labelTypeface,
            tabular = false
        )

        weeklyBarView = MiniProgressBarView(context).apply {
            tag = "bard_quota_hud_wk_bar"
            layoutParams = LayoutParams(dp(context, 25f), dp(context, 5f)).apply {
                marginStart = dp(context, 4f)
                marginEnd = dp(context, 4.5f)
            }
        }

        weeklyValueView = createSingleLineText(
            tagSuffix = "wk_val",
            textSp = 10.5f,
            tf = valueTypeface,
            tabular = true
        )

        dividerTwoView = View(context).apply {
            tag = "bard_quota_hud_div2"
            layoutParams = LayoutParams(maxOf(1, dp(context, 1f)), dp(context, 12f)).apply {
                marginStart = dp(context, 6f)
                marginEnd = dp(context, 3.5f)
            }
        }

        // 3. Right Section: Refresh Icon Button + External Link Icon Button
        refreshIconBtn = HudActionIconView(context, HudActionIconView.IconKind.REFRESH).apply {
            tag = "bard_quota_hud_btn_refresh"
            contentDescription = "Refresh Quota"
            layoutParams = LayoutParams(dp(context, 22f), dp(context, 22f))
            setOnClickListener {
                spinOnce()
                onRefreshClick?.invoke()
            }
        }

        externalLinkBtn = HudActionIconView(context, HudActionIconView.IconKind.EXTERNAL_LINK).apply {
            tag = "bard_quota_hud_btn_ext"
            contentDescription = "Open Usage Page"
            layoutParams = LayoutParams(dp(context, 22f), dp(context, 22f)).apply {
                marginStart = dp(context, 1.5f)
            }
            setOnClickListener {
                val custom = onOpenUsageClick
                if (custom != null) {
                    custom.invoke()
                } else {
                    openModuleSettings(context)
                }
            }
        }

        addView(fiveHourLabelView)
        addView(fiveHourBarView)
        addView(fiveHourValueView)
        addView(dividerOneView)
        addView(weeklyLabelView)
        addView(weeklyBarView)
        addView(weeklyValueView)
        addView(dividerTwoView)
        addView(refreshIconBtn)
        addView(externalLinkBtn)

        renderAll()
    }

    private fun createSingleLineText(
        tagSuffix: String,
        textSp: Float,
        tf: Typeface,
        tabular: Boolean
    ): TextView {
        return TextView(context).apply {
            tag = "bard_quota_hud_$tagSuffix"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            typeface = tf
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            if (tabular) {
                fontFeatureSettings = "tnum"
            }
        }
    }

    fun updateSnapshot(snapshot: Snapshot) {
        currentSnapshot = snapshot.sanitized()
        renderAll()
    }

    fun applyPreferences(lang: Lang, themeMode: ThemeMode) {
        currentLang = lang
        currentTheme = themeMode
        renderAll()
    }

    fun triggerRefreshSpin() {
        refreshIconBtn.spinOnce()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        renderAll()
    }

    private fun renderAll() {
        val palette = resolvePalette(context, currentTheme)
        val labels = resolveLocalizedHudLabels(context, currentLang)
        val safeSnap = currentSnapshot.sanitized()

        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(palette.bgTop, palette.bgBottom)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = palette.cornerPx
            setStroke(maxOf(1, dp(context, 0.9f)), palette.border)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineSpotShadowColor = palette.shadowSpot
            outlineAmbientShadowColor = palette.shadowSpot
        }

        fiveHourLabelView.text = labels.fiveHour
        fiveHourLabelView.setTextColor(palette.labelText)

        weeklyLabelView.text = labels.weekly
        weeklyLabelView.setTextColor(palette.labelText)

        fiveHourValueView.text = safeSnap.formatFiveHourDisplay()
        fiveHourValueView.setTextColor(palette.primaryText)

        weeklyValueView.text = safeSnap.formatWeeklyDisplay(currentLang)
        weeklyValueView.setTextColor(palette.primaryText)

        val p5 = safeSnap.fiveHourProgress
        val pW = safeSnap.weeklyProgress

        fiveHourBarView.update(
            fraction = p5,
            trackColor = palette.trackColor,
            fillColor = pickBarColor(p5, palette)
        )
        weeklyBarView.update(
            fraction = pW,
            trackColor = palette.trackColor,
            fillColor = pickBarColor(pW, palette)
        )

        dividerOneView.setBackgroundColor(palette.divider)
        dividerTwoView.setBackgroundColor(palette.divider)

        refreshIconBtn.contentDescription = labels.refreshDesc
        externalLinkBtn.contentDescription = labels.openUsageDesc
        refreshIconBtn.updateColors(palette.iconColor, palette.iconPressedBg)
        externalLinkBtn.updateColors(palette.iconColor, palette.iconPressedBg)
    }

    private fun pickBarColor(fraction: Float, palette: Palette): Int {
        return when {
            fraction >= 0.90f -> palette.barDanger
            fraction >= 0.75f -> palette.barWarn
            else -> palette.barNormal
        }
    }

    // =========================================================================
    // Capsule Drag vs Child Action Button Click Disambiguation
    // =========================================================================

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!draggableInFrame && onWindowDrag == null) {
            return super.onInterceptTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                isDraggingCapsule = false
                val lp = layoutParams as? FrameLayout.LayoutParams
                if (lp != null) {
                    startRightMargin = lp.rightMargin
                    startTopMargin = lp.topMargin
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx) {
                    isDraggingCapsule = true
                    lastRawX = ev.rawX
                    lastRawY = ev.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDraggingCapsule = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!draggableInFrame && onWindowDrag == null) {
            return super.onTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                isDraggingCapsule = false
                val lp = layoutParams as? FrameLayout.LayoutParams
                if (lp != null) {
                    startRightMargin = lp.rightMargin
                    startTopMargin = lp.topMargin
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val totalDx = ev.rawX - downRawX
                val totalDy = ev.rawY - downRawY
                if (!isDraggingCapsule && (abs(totalDx) > touchSlopPx || abs(totalDy) > touchSlopPx)) {
                    isDraggingCapsule = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (isDraggingCapsule) {
                    val winDrag = onWindowDrag
                    if (winDrag != null) {
                        val stepDx = (ev.rawX - lastRawX).toInt()
                        val stepDy = (ev.rawY - lastRawY).toInt()
                        lastRawX = ev.rawX
                        lastRawY = ev.rawY
                        winDrag(stepDx, stepDy)
                    } else if (draggableInFrame) {
                        val lp = layoutParams as? FrameLayout.LayoutParams
                        if (lp != null) {
                            lp.rightMargin = maxOf(dp(context, 4f), startRightMargin - totalDx.toInt())
                            lp.topMargin = maxOf(dp(context, 24f), startTopMargin + totalDy.toInt())
                            layoutParams = lp
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDraggingCapsule = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    // =========================================================================
    // Programmatic Subviews: Mini Horizontal Progress Bar & Vector Action Icons
    // =========================================================================

    private class MiniProgressBarView(context: Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val rect = RectF()
        private var fraction: Float = 0f

        fun update(fraction: Float, trackColor: Int, fillColor: Int) {
            this.fraction = fraction.coerceIn(0f, 1f)
            trackPaint.color = trackColor
            fillPaint.color = fillColor
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = h / 2f

            // Rounded capsule track
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, r, r, trackPaint)

            // Rounded capsule progress fill
            if (fraction > 0.005f) {
                val fillW = maxOf(h, w * fraction).coerceAtMost(w)
                rect.set(0f, 0f, fillW, h)
                canvas.drawRoundRect(rect, r, r, fillPaint)
            }
        }
    }

    private class HudActionIconView(
        context: Context,
        private val kind: IconKind
    ) : View(context) {

        enum class IconKind { REFRESH, EXTERNAL_LINK }

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = dpF(context, 1.45f)
        }
        private val pressBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val arcRect = RectF()
        private val path = Path()
        private var touchPressed = false

        init {
            isClickable = true
            isFocusable = true
        }

        fun updateColors(iconColor: Int, pressedBgColor: Int) {
            strokePaint.color = iconColor
            pressBgPaint.color = pressedBgColor
            invalidate()
        }

        fun spinOnce() {
            animate().cancel()
            rotation = 0f
            animate()
                .rotation(360f)
                .setDuration(520L)
                .start()
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            if (touchPressed != pressed) {
                touchPressed = pressed
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f
            val cy = h / 2f

            if (touchPressed) {
                canvas.drawCircle(cx, cy, minOf(w, h) * 0.46f, pressBgPaint)
            }

            when (kind) {
                IconKind.REFRESH -> drawRefreshIcon(canvas, cx, cy)
                IconKind.EXTERNAL_LINK -> drawExternalLinkIcon(canvas, cx, cy)
            }
        }

        private fun drawRefreshIcon(canvas: Canvas, cx: Float, cy: Float) {
            val r = dpF(context, 4.9f)
            arcRect.set(cx - r, cy - r, cx + r, cy + r)
            // Open circular arrow arc (285 degrees)
            val startDeg = -55f
            val sweepDeg = 285f
            canvas.drawArc(arcRect, startDeg, sweepDeg, false, strokePaint)

            // Arrowhead at the leading tip (-55 deg clockwise from 3 o'clock -> actually draw tip at startDeg + sweepDeg = 230 deg or clockwise tip)
            // Let's place a crisp clockwise arrow tip at +30 deg while arc runs from +75 deg -> +360(+30) deg:
            // Wait: above arc runs from -55 deg clockwise by +285 deg -> ends at +230 deg.
            // Even cleaner: draw clockwise refresh arrow where tip is at top-right (-35 deg):
            // Arc from +25 deg clockwise by 295 deg -> ends at 320 deg (-40 deg)!
            path.reset()
            val tipRad = Math.toRadians(230.0)
            val tipX = cx + (r * cos(tipRad)).toFloat()
            val tipY = cy + (r * sin(tipRad)).toFloat()
            val wing = dpF(context, 2.5f)
            // Tangent direction at 230 deg clockwise is (230 + 90) = 320 deg; wings point back along 140 deg +/- 38 deg
            val w1 = Math.toRadians(102.0)
            val w2 = Math.toRadians(182.0)
            path.moveTo(tipX + (wing * cos(w1)).toFloat(), tipY + (wing * sin(w1)).toFloat())
            path.lineTo(tipX, tipY)
            path.lineTo(tipX + (wing * cos(w2)).toFloat(), tipY + (wing * sin(w2)).toFloat())
            canvas.drawPath(path, strokePaint)
        }

        private fun drawExternalLinkIcon(canvas: Canvas, cx: Float, cy: Float) {
            val half = dpF(context, 4.9f)
            val left = cx - half
            val top = cy - half
            val right = cx + half
            val bottom = cy + half
            val corner = dpF(context, 1.6f)

            // Open rounded square container (top-left -> bottom-left -> bottom-right -> mid-right)
            path.reset()
            path.moveTo(cx - half * 0.12f, top)
            path.lineTo(left + corner, top)
            path.quadTo(left, top, left, top + corner)
            path.lineTo(left, bottom - corner)
            path.quadTo(left, bottom, left + corner, bottom)
            path.lineTo(right - corner, bottom)
            path.quadTo(right, bottom, right, bottom - corner)
            path.lineTo(right, cy + half * 0.12f)
            canvas.drawPath(path, strokePaint)

            // Diagonal outbound arrow to top-right
            path.reset()
            path.moveTo(cx - half * 0.15f, cy + half * 0.15f)
            path.lineTo(right, top)
            val wing = dpF(context, 3.2f)
            path.moveTo(right - wing, top)
            path.lineTo(right, top)
            path.lineTo(right, top + wing)
            canvas.drawPath(path, strokePaint)
        }
    }

    companion object {
        const val HUD_VIEW_TAG = "bard_quota_hud_capsule_v19"
        const val MODULE_PACKAGE = "com.example.bardquotahook"

        private fun resolveLocalizedHudLabels(context: Context, lang: Lang): LocalizedHudLabels {
            val isZh = lang == Lang.ZH
            return LocalizedHudLabels(
                fiveHour = "5h",
                weekly = if (isZh) "每周" else "Weekly",
                refreshDesc = if (isZh) "刷新当前前台额度" else "Refresh foreground quota",
                openUsageDesc = if (isZh) "打开模块设置" else "Open HUD settings"
            )
        }

        fun dp(context: Context, value: Float): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.resources.displayMetrics
            ).toInt()
        }

        fun dpF(context: Context, value: Float): Float {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.resources.displayMetrics
            )
        }

        fun isHostDark(context: Context): Boolean {
            return try {
                val nightBits = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightBits == Configuration.UI_MODE_NIGHT_YES
            } catch (_: Throwable) {
                true
            }
        }

        fun openModuleSettings(context: Context) {
            try {
                val intent = Intent().apply {
                    setClassName(MODULE_PACKAGE, "$MODULE_PACKAGE.MainActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
            } catch (_: Throwable) {
            }
        }

        private fun withAlpha(color: Int, alpha: Int): Int {
            return (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
        }

        private fun resolvePalette(context: Context, mode: ThemeMode): Palette {
            val dark = isHostDark(context)
            if (mode == ThemeMode.MD3) {
                return resolveMd3Palette(context, dark)
            }
            return resolveMiuixPalette(context, dark)
        }

        private fun resolveMiuixPalette(context: Context, dark: Boolean): Palette {
            val pillRadius = dpF(context, 999f)
            return if (dark) {
                Palette(
                    bgTop = Color.parseColor("#D8262830"),
                    bgBottom = Color.parseColor("#CC181A20"),
                    border = Color.parseColor("#33FFFFFF"),
                    cornerPx = pillRadius,
                    primaryText = Color.parseColor("#F8FAFC"),
                    labelText = Color.parseColor("#94A3B8"),
                    divider = Color.parseColor("#28FFFFFF"),
                    trackColor = Color.parseColor("#2EFFFFFF"),
                    barNormal = Color.parseColor("#0D84FF"),
                    barWarn = Color.parseColor("#FF9F0A"),
                    barDanger = Color.parseColor("#FF453A"),
                    iconColor = Color.parseColor("#CBD5E1"),
                    iconPressedBg = Color.parseColor("#2EFFFFFF"),
                    shadowSpot = Color.parseColor("#88000000")
                )
            } else {
                Palette(
                    bgTop = Color.parseColor("#EBFFFFFF"),
                    bgBottom = Color.parseColor("#DCF1F5F9"),
                    border = Color.parseColor("#1C0F172A"),
                    cornerPx = pillRadius,
                    primaryText = Color.parseColor("#0F172A"),
                    labelText = Color.parseColor("#64748B"),
                    divider = Color.parseColor("#1A0F172A"),
                    trackColor = Color.parseColor("#1F0F172A"),
                    barNormal = Color.parseColor("#007AFF"),
                    barWarn = Color.parseColor("#F59E0B"),
                    barDanger = Color.parseColor("#EF4444"),
                    iconColor = Color.parseColor("#475569"),
                    iconPressedBg = Color.parseColor("#180F172A"),
                    shadowSpot = Color.parseColor("#360F172A")
                )
            }
        }

        private fun resolveMd3Palette(context: Context, dark: Boolean): Palette {
            val md3Radius = dpF(context, 999f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    return if (dark) {
                        val surf = context.getColor(android.R.color.system_neutral1_800)
                        val surfLow = context.getColor(android.R.color.system_neutral1_900)
                        val outline = context.getColor(android.R.color.system_neutral2_600)
                        val onSurf = context.getColor(android.R.color.system_neutral1_50)
                        val onSurfVar = context.getColor(android.R.color.system_neutral2_200)
                        val primary = context.getColor(android.R.color.system_accent1_200)
                        val track = context.getColor(android.R.color.system_accent2_700)
                        Palette(
                            bgTop = withAlpha(surf, 0xF4),
                            bgBottom = withAlpha(surfLow, 0xF0),
                            border = withAlpha(outline, 0x70),
                            cornerPx = md3Radius,
                            primaryText = onSurf,
                            labelText = onSurfVar,
                            divider = withAlpha(outline, 0x55),
                            trackColor = withAlpha(track, 0x99),
                            barNormal = primary,
                            barWarn = Color.parseColor("#FBBF24"),
                            barDanger = Color.parseColor("#F87171"),
                            iconColor = primary,
                            iconPressedBg = withAlpha(primary, 0x26),
                            shadowSpot = Color.parseColor("#7A000000")
                        )
                    } else {
                        val surf = context.getColor(android.R.color.system_neutral1_50)
                        val surfLow = context.getColor(android.R.color.system_neutral1_100)
                        val outline = context.getColor(android.R.color.system_neutral2_300)
                        val onSurf = context.getColor(android.R.color.system_neutral1_900)
                        val onSurfVar = context.getColor(android.R.color.system_neutral2_700)
                        val primary = context.getColor(android.R.color.system_accent1_600)
                        val track = context.getColor(android.R.color.system_accent2_100)
                        Palette(
                            bgTop = withAlpha(surf, 0xF7),
                            bgBottom = withAlpha(surfLow, 0xF2),
                            border = withAlpha(outline, 0x88),
                            cornerPx = md3Radius,
                            primaryText = onSurf,
                            labelText = onSurfVar,
                            divider = withAlpha(outline, 0x66),
                            trackColor = withAlpha(track, 0xDD),
                            barNormal = primary,
                            barWarn = Color.parseColor("#D97706"),
                            barDanger = Color.parseColor("#DC2626"),
                            iconColor = primary,
                            iconPressedBg = withAlpha(primary, 0x1F),
                            shadowSpot = Color.parseColor("#330F172A")
                        )
                    }
                } catch (_: Throwable) {
                }
            }

            // Harmonious Material 3 Tonal Fallback (Android 8.0 - 11)
            return if (dark) {
                Palette(
                    bgTop = Color.parseColor("#F22B2930"),
                    bgBottom = Color.parseColor("#EE211F26"),
                    border = Color.parseColor("#55938F99"),
                    cornerPx = md3Radius,
                    primaryText = Color.parseColor("#E6E1E5"),
                    labelText = Color.parseColor("#CAC4D0"),
                    divider = Color.parseColor("#44938F99"),
                    trackColor = Color.parseColor("#884A4458"),
                    barNormal = Color.parseColor("#D0BCFF"),
                    barWarn = Color.parseColor("#FBBF24"),
                    barDanger = Color.parseColor("#F2B8B5"),
                    iconColor = Color.parseColor("#D0BCFF"),
                    iconPressedBg = Color.parseColor("#2BD0BCFF"),
                    shadowSpot = Color.parseColor("#80000000")
                )
            } else {
                Palette(
                    bgTop = Color.parseColor("#F7FEF7FF"),
                    bgBottom = Color.parseColor("#F2F3EDF7"),
                    border = Color.parseColor("#66CAC4D0"),
                    cornerPx = md3Radius,
                    primaryText = Color.parseColor("#1D1B20"),
                    labelText = Color.parseColor("#49454F"),
                    divider = Color.parseColor("#55CAC4D0"),
                    trackColor = Color.parseColor("#DDE8DEF8"),
                    barNormal = Color.parseColor("#6750A4"),
                    barWarn = Color.parseColor("#D97706"),
                    barDanger = Color.parseColor("#B3261E"),
                    iconColor = Color.parseColor("#6750A4"),
                    iconPressedBg = Color.parseColor("#1F6750A4"),
                    shadowSpot = Color.parseColor("#301D1B20")
                )
            }
        }
    }
}
