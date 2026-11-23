package com.example.bardquotahook

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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

    enum class DockEdge {
        NONE,
        LEFT,
        RIGHT
    }

    data class Snapshot(
        val fiveHourQuota: String = "--",
        val fiveHourReset: String = "--",
        val weeklyLimit: String = "--",
        val weeklyReset: String = "--",
        val source: String = "init",
        val isStaleOffline: Boolean = false
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
        val offlineTag: String,
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
        val staleText: Int,
        val staleBg: Int,
        val staleBorder: Int,
        val staleBar: Int,
        val offlineDot: Int,
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
    var currentOpacityPct: Int = QuotaSyncProvider.DEFAULT_HUD_OPACITY
        private set
    private var currentEnergyScore: Double = 0.0

    private var miuixBlurAnimator: ValueAnimator? = null
    private var miuixBlurPhase: Float = 0f
    private var tapRippleAnimator: ValueAnimator? = null
    private var tapRippleProgress: Float = 0f
    private var tapRippleActive: Boolean = false
    private var tapRippleX: Float = 0f
    private var tapRippleY: Float = 0f
    private val tapRipplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pillClipPath = Path()
    private val backdropGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val backdropRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val backdropRect = RectF()
    private var activeBgDrawable: GradientDrawable? = null
    private var activePalette: Palette? = null

    private val offlineDotView: OfflineDotView
    private val fiveHourLabelView: TextView
    private val fiveHourBarView: MiniProgressBarView
    private val fiveHourValueView: TextView
    private val dividerOneView: View
    private val weeklyLabelView: TextView
    private val weeklyBarView: MiniProgressBarView
    private val weeklyValueView: TextView
    private val offlineTagView: TextView
    private val dividerTwoView: View
    private val refreshIconBtn: HudActionIconView
    private val externalLinkBtn: HudActionIconView
    private val floatingOrbView: FloatingOrbView

    private val touchSlopPx: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var startLeftMargin = 0
    private var startTopMargin = 0
    private var lastParentWidth = 0
    private var lastParentHeight = 0
    private var isDraggingCapsule = false
    var currentDockedEdge: DockEdge = DockEdge.NONE
        private set

    init {
        tag = HUD_VIEW_TAG
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        clipToPadding = false
        setWillNotDraw(false)

        val padStart = dp(context, 10f)
        val padEnd = dp(context, 7f)
        val padV = dp(context, 5.5f)
        setPadding(padStart, padV, padEnd, padV)
        elevation = dp(context, 9f).toFloat()

        val labelTypeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val valueTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        // 0. Far-left blinking orange dot shown only when snapshot.isStaleOffline == true
        offlineDotView = OfflineDotView(context).apply {
            tag = "bard_quota_hud_offline_dot"
            val dotSize = dp(context, 6.5f)
            layoutParams = LayoutParams(dotSize, dotSize).apply {
                marginEnd = dp(context, 5.5f)
            }
            visibility = View.GONE
        }

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

        offlineTagView = createSingleLineText(
            tagSuffix = "offline",
            textSp = 9f,
            tf = labelTypeface,
            tabular = false
        ).apply {
            val badgeH = dp(context, 4.5f)
            val badgeV = dp(context, 1.5f)
            setPadding(badgeH, badgeV, badgeH, badgeV)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(context, 5f)
            }
            visibility = View.GONE
        }

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
                startTapRipple(left + width / 2f, top + height / 2f)
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

        floatingOrbView = FloatingOrbView(context).apply {
            tag = "bard_quota_hud_orb"
            val orbSize = dp(context, 36f)
            layoutParams = LayoutParams(orbSize, orbSize)
            visibility = View.GONE
        }

        addView(offlineDotView)
        addView(fiveHourLabelView)
        addView(fiveHourBarView)
        addView(fiveHourValueView)
        addView(dividerOneView)
        addView(weeklyLabelView)
        addView(weeklyBarView)
        addView(weeklyValueView)
        addView(offlineTagView)
        addView(dividerTwoView)
        addView(refreshIconBtn)
        addView(externalLinkBtn)
        addView(floatingOrbView)

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

    fun applyPreferences(lang: Lang, themeMode: ThemeMode, opacityPct: Int = currentOpacityPct) {
        currentLang = lang
        currentTheme = themeMode
        currentOpacityPct = opacityPct.coerceIn(20, 100)
        renderAll()
    }

    fun setHudOpacity(opacityPct: Int) {
        val clamped = opacityPct.coerceIn(20, 100)
        if (currentOpacityPct != clamped) {
            currentOpacityPct = clamped
            renderAll()
            invalidate()
        }
    }

    fun updateEnergyScore(score: Double) {
        val clamped = score.coerceIn(0.0, 100.0)
        if (abs(clamped - currentEnergyScore) >= 0.05 || (clamped >= HIGH_BATTERY_IMPACT_THRESHOLD) != isHighBatteryImpact()) {
            currentEnergyScore = clamped
            renderAll()
        }
    }

    fun isHighBatteryImpact(): Boolean = currentEnergyScore >= HIGH_BATTERY_IMPACT_THRESHOLD

    fun triggerRefreshSpin() {
        refreshIconBtn.spinOnce()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        renderAll()
    }

    override fun onDetachedFromWindow() {
        stopMiuixBlurAnimation()
        stopTapRipple()
        super.onDetachedFromWindow()
    }

    private fun renderAll() {
        val palette = resolvePalette(context, currentTheme)
        val labels = resolveLocalizedHudLabels(context, currentLang)
        val safeSnap = currentSnapshot.sanitized()
        val stale = safeSnap.isStaleOffline

        val bgAlphaTop = (currentOpacityPct.coerceIn(20, 100) * 255 / 100).coerceIn(51, 255)
        val bgAlphaBottom = (bgAlphaTop - 12).coerceIn(45, 255)
        val bgDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                withAlpha(palette.bgTop, bgAlphaTop),
                withAlpha(palette.bgBottom, bgAlphaBottom)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = palette.cornerPx
            setStroke(maxOf(1, dp(context, 0.9f)), if (stale) palette.staleBorder else palette.border)
        }
        activeBgDrawable = bgDrawable
        activePalette = palette
        background = bgDrawable

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineSpotShadowColor = palette.shadowSpot
            outlineAmbientShadowColor = palette.shadowSpot
        }

        if (currentTheme == ThemeMode.MIUIX && isHighBatteryImpact() && isAttachedToWindow) {
            startMiuixBlurAnimation()
        } else {
            stopMiuixBlurAnimation()
        }

        val activeLabelColor = if (stale) palette.staleText else palette.labelText
        val activeValueColor = if (stale) palette.staleText else palette.primaryText

        fiveHourLabelView.text = labels.fiveHour
        fiveHourLabelView.setTextColor(activeLabelColor)

        weeklyLabelView.text = labels.weekly
        weeklyLabelView.setTextColor(activeLabelColor)

        fiveHourValueView.text = safeSnap.formatFiveHourDisplay()
        fiveHourValueView.setTextColor(activeValueColor)

        weeklyValueView.text = safeSnap.formatWeeklyDisplay(currentLang)
        weeklyValueView.setTextColor(activeValueColor)

        if (stale) {
            offlineDotView.contentDescription = labels.offlineTag
            offlineDotView.updateState(active = true, dotColor = palette.offlineDot)
            offlineDotView.visibility = View.VISIBLE

            offlineTagView.text = labels.offlineTag
            offlineTagView.setTextColor(palette.staleText)
            offlineTagView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(context, 4f)
                setColor(palette.staleBg)
                setStroke(maxOf(1, dp(context, 0.7f)), palette.staleBorder)
            }
            offlineTagView.visibility = View.VISIBLE
        } else {
            offlineDotView.updateState(active = false, dotColor = palette.offlineDot)
            offlineDotView.visibility = View.GONE
            offlineTagView.visibility = View.GONE
        }

        val p5 = safeSnap.fiveHourProgress
        val pW = safeSnap.weeklyProgress

        fiveHourBarView.update(
            fraction = p5,
            trackColor = palette.trackColor,
            fillColor = if (stale) palette.staleBar else pickBarColor(p5, palette)
        )
        weeklyBarView.update(
            fraction = pW,
            trackColor = palette.trackColor,
            fillColor = if (stale) palette.staleBar else pickBarColor(pW, palette)
        )

        dividerOneView.setBackgroundColor(palette.divider)
        dividerTwoView.setBackgroundColor(palette.divider)

        refreshIconBtn.contentDescription = labels.refreshDesc
        externalLinkBtn.contentDescription = labels.openUsageDesc
        refreshIconBtn.updateColors(if (stale) palette.staleText else palette.iconColor, palette.iconPressedBg)
        externalLinkBtn.updateColors(if (stale) palette.staleText else palette.iconColor, palette.iconPressedBg)

        val isOrb = currentDockedEdge != DockEdge.NONE
        val capsuleVisibility = if (isOrb) View.GONE else View.VISIBLE
        fiveHourLabelView.visibility = capsuleVisibility
        fiveHourBarView.visibility = capsuleVisibility
        fiveHourValueView.visibility = capsuleVisibility
        dividerOneView.visibility = capsuleVisibility
        weeklyLabelView.visibility = capsuleVisibility
        weeklyBarView.visibility = capsuleVisibility
        weeklyValueView.visibility = capsuleVisibility
        dividerTwoView.visibility = capsuleVisibility
        refreshIconBtn.visibility = capsuleVisibility
        externalLinkBtn.visibility = capsuleVisibility
        if (isOrb) {
            offlineDotView.visibility = View.GONE
            offlineTagView.visibility = View.GONE
            val orbPad = dp(context, 4f)
            setPadding(orbPad, orbPad, orbPad, orbPad)
            val pctText = if (safeSnap.fiveHourQuota == "--") {
                "--"
            } else {
                safeSnap.fiveHourQuota.trim()
            }
            floatingOrbView.update(
                fiveHourFraction = p5,
                weeklyFraction = pW,
                centerText = pctText,
                trackColor = palette.trackColor,
                ringColor = if (stale) palette.staleBar else pickBarColor(p5, palette),
                weeklyAccentColor = if (stale) palette.staleBar else pickBarColor(pW, palette),
                textColor = if (stale) palette.staleText else palette.primaryText,
                edge = currentDockedEdge,
                isStale = stale,
                staleDotColor = palette.offlineDot
            )
            floatingOrbView.visibility = View.VISIBLE
        } else {
            floatingOrbView.visibility = View.GONE
            setPadding(dp(context, 10f), dp(context, 5.5f), dp(context, 7f), dp(context, 5.5f))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (currentTheme == ThemeMode.MIUIX && isHighBatteryImpact()) {
            startMiuixBlurAnimation()
        }
    }

    private fun startMiuixBlurAnimation() {
        if (miuixBlurAnimator?.isRunning == true) return
        miuixBlurAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2800L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val phase = (anim.animatedValue as? Float) ?: 0f
                miuixBlurPhase = phase
                applyMiuixBlurFrame(phase)
                invalidate()
            }
            start()
        }
    }

    private fun stopMiuixBlurAnimation() {
        try {
            miuixBlurAnimator?.cancel()
        } catch (_: Throwable) {
        }
        miuixBlurAnimator = null
        miuixBlurPhase = 0f
    }

    private fun applyMiuixBlurFrame(phase: Float) {
        val pal = activePalette ?: return
        val bg = activeBgDrawable ?: return
        if (currentTheme != ThemeMode.MIUIX || !isHighBatteryImpact()) return
        val dark = isHostDark(context)
        val baseAlpha = (currentOpacityPct.coerceIn(20, 100) * 255 / 100).coerceIn(51, 255)
        val pulseBoost = (phase * 26f).toInt()
        val topAlpha = (baseAlpha + pulseBoost).coerceIn(51, 255)
        val botAlpha = (baseAlpha - 12 + pulseBoost).coerceIn(45, 255)
        bg.colors = intArrayOf(withAlpha(pal.bgTop, topAlpha), withAlpha(pal.bgBottom, botAlpha))
        val rimTint = if (dark) {
            withAlpha(Color.parseColor("#5EEAD4"), (55 + (phase * 90f).toInt()))
        } else {
            withAlpha(Color.parseColor("#0D9488"), (45 + (phase * 75f).toInt()))
        }
        bg.setStroke(maxOf(1, dp(context, 0.9f + phase * 0.45f)), rimTint)
    }

    private fun startTapRipple(localX: Float, localY: Float) {
        stopTapRipple()
        tapRippleX = if (width > 0) localX.coerceIn(0f, width.toFloat()) else localX
        tapRippleY = if (height > 0) localY.coerceIn(0f, height.toFloat()) else localY
        tapRippleProgress = 0f
        tapRippleActive = true
        tapRippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560L
            addUpdateListener { anim ->
                val p = (anim.animatedValue as? Float) ?: 0f
                tapRippleProgress = p
                if (p >= 0.999f) {
                    tapRippleActive = false
                }
                invalidate()
            }
            start()
        }
    }

    private fun stopTapRipple() {
        try {
            tapRippleAnimator?.cancel()
        } catch (_: Throwable) {
        }
        tapRippleAnimator = null
        tapRippleActive = false
        tapRippleProgress = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f

        if (currentTheme == ThemeMode.MIUIX && isHighBatteryImpact()) {
            val dark = isHostDark(context)
            val breathe = miuixBlurPhase
            backdropRect.set(dpF(context, 1.5f), dpF(context, 1.5f), w - dpF(context, 1.5f), h - dpF(context, 1.5f))
            backdropGlowPaint.color = if (dark) {
                withAlpha(Color.parseColor("#2DD4BF"), (10 + (breathe * 18f).toInt()))
            } else {
                withAlpha(Color.parseColor("#14B8A6"), (8 + (breathe * 16f).toInt()))
            }
            canvas.drawRoundRect(backdropRect, r, r, backdropGlowPaint)

            backdropRimPaint.strokeWidth = dpF(context, 1.1f)
            backdropRimPaint.color = if (dark) {
                withAlpha(Color.WHITE, (20 + (breathe * 34f).toInt()))
            } else {
                withAlpha(Color.WHITE, (70 + (breathe * 80f).toInt()))
            }
            canvas.drawRoundRect(backdropRect, r, r, backdropRimPaint)
        }

        if (tapRippleActive) {
            val dark = isHostDark(context)
            val baseColor = when (currentTheme) {
                ThemeMode.MD3 -> if (dark) Color.parseColor("#D0BCFF") else Color.parseColor("#6750A4")
                ThemeMode.MIUIX -> if (dark) Color.parseColor("#38BDF8") else Color.parseColor("#0EA5E9")
            }
            val eased = 1f - (1f - tapRippleProgress) * (1f - tapRippleProgress)
            val alpha = ((1f - tapRippleProgress) * 115f).toInt().coerceIn(0, 120)
            tapRipplePaint.color = withAlpha(baseColor, alpha)
            val maxRadius = maxOf(w, h) * 1.15f
            val curRadius = maxRadius * eased

            pillClipPath.reset()
            backdropRect.set(0f, 0f, w, h)
            pillClipPath.addRoundRect(backdropRect, r, r, Path.Direction.CW)
            val saveCount = canvas.save()
            canvas.clipPath(pillClipPath)
            canvas.drawCircle(tapRippleX, tapRippleY, curRadius, tapRipplePaint)
            canvas.restoreToCount(saveCount)
        }
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

    fun setDockedEdge(edge: DockEdge, animateMargin: Boolean = true) {
        if (currentDockedEdge == edge) return
        currentDockedEdge = edge
        renderAll()
        if (animateMargin && draggableInFrame) {
            post {
                val parentView = parent as? View ?: return@post
                val pw = parentView.width
                val lp = layoutParams as? FrameLayout.LayoutParams ?: return@post
                normalizeLayoutParamsToStart(lp, pw)
                val edgePad = dp(context, 4f)
                val viewW = if (width > 0) width else measuredWidth
                when (edge) {
                    DockEdge.LEFT -> lp.leftMargin = edgePad
                    DockEdge.RIGHT -> lp.leftMargin = maxOf(edgePad, pw - viewW - edgePad)
                    DockEdge.NONE -> {
                        val safePad = dp(context, 10f)
                        lp.leftMargin = lp.leftMargin.coerceIn(safePad, maxOf(safePad, pw - viewW - safePad))
                    }
                }
                layoutParams = lp
            }
        }
    }

    private fun normalizeLayoutParamsToStart(lp: FrameLayout.LayoutParams, parentWidth: Int) {
        if ((lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) != Gravity.START) {
            val resolvedLeft = if (left > 0 || width > 0) {
                left
            } else {
                maxOf(0, parentWidth - lp.rightMargin - measuredWidth)
            }
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = maxOf(0, resolvedLeft)
            lp.rightMargin = 0
            layoutParams = lp
        }
    }

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
                val parentView = parent as? View
                lastParentWidth = parentView?.width ?: 0
                lastParentHeight = parentView?.height ?: 0
                val lp = layoutParams as? FrameLayout.LayoutParams
                if (lp != null && lastParentWidth > 0) {
                    normalizeLayoutParamsToStart(lp, lastParentWidth)
                    startLeftMargin = lp.leftMargin
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
                val parentView = parent as? View
                lastParentWidth = parentView?.width ?: 0
                lastParentHeight = parentView?.height ?: 0
                val lp = layoutParams as? FrameLayout.LayoutParams
                if (lp != null && lastParentWidth > 0) {
                    normalizeLayoutParamsToStart(lp, lastParentWidth)
                    startLeftMargin = lp.leftMargin
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
                            val pw = lastParentWidth.takeIf { it > 0 } ?: (parent as? View)?.width ?: 0
                            val ph = lastParentHeight.takeIf { it > 0 } ?: (parent as? View)?.height ?: 0
                            val maxLeft = if (pw > 0) maxOf(0, pw - width) else Int.MAX_VALUE
                            val maxTop = if (ph > 0) maxOf(dp(context, 24f), ph - height - dp(context, 16f)) else Int.MAX_VALUE
                            lp.leftMargin = (startLeftMargin + totalDx.toInt()).coerceIn(0, maxLeft)
                            lp.topMargin = (startTopMargin + totalDy.toInt()).coerceIn(dp(context, 24f), maxTop)
                            layoutParams = lp
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = isDraggingCapsule
                isDraggingCapsule = false
                if (!wasDragging) {
                    startTapRipple(ev.x, ev.y)
                    if (currentDockedEdge != DockEdge.NONE) {
                        // Tap the floating orb -> expand smoothly back into full capsule
                        setDockedEdge(DockEdge.NONE, animateMargin = true)
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        // Tap the HUD capsule body -> visual ripple + manual refresh
                        refreshIconBtn.spinOnce()
                        onRefreshClick?.invoke()
                    }
                    return true
                }
                if (draggableInFrame && onWindowDrag == null) {
                    val lp = layoutParams as? FrameLayout.LayoutParams
                    val pw = lastParentWidth.takeIf { it > 0 } ?: (parent as? View)?.width ?: 0
                    if (lp != null && pw > 0) {
                        val distLeft = lp.leftMargin
                        val distRight = maxOf(0, pw - width - lp.leftMargin)
                        val dockThreshold = dp(context, 26f)
                        when {
                            distLeft <= dockThreshold && distLeft <= distRight -> setDockedEdge(DockEdge.LEFT, animateMargin = true)
                            distRight <= dockThreshold -> setDockedEdge(DockEdge.RIGHT, animateMargin = true)
                            else -> setDockedEdge(DockEdge.NONE, animateMargin = true)
                        }
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    // =========================================================================
    // Programmatic Subviews: Mini Horizontal Progress Bar & Vector Action Icons
    // =========================================================================

    private class OfflineDotView(context: Context) : View(context) {
        private val dotDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        private var blinkAnimator: ObjectAnimator? = null

        init {
            background = dotDrawable
        }

        fun updateState(active: Boolean, dotColor: Int) {
            dotDrawable.setColor(dotColor)
            if (active) {
                if (blinkAnimator == null) {
                    blinkAnimator = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.22f).apply {
                        duration = 650L
                        repeatMode = ValueAnimator.REVERSE
                        repeatCount = ValueAnimator.INFINITE
                        start()
                    }
                } else if (blinkAnimator?.isStarted != true) {
                    blinkAnimator?.start()
                }
            } else {
                stopBlinking()
            }
        }

        private fun stopBlinking() {
            try {
                blinkAnimator?.cancel()
            } catch (_: Throwable) {
            }
            blinkAnimator = null
            alpha = 1f
        }

        override fun onDetachedFromWindow() {
            stopBlinking()
            super.onDetachedFromWindow()
        }
    }

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

    private class FloatingOrbView(context: Context) : View(context) {
        private val ringTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(context, 2.8f)
            strokeCap = Paint.Cap.ROUND
        }
        private val ringProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(context, 2.8f)
            strokeCap = Paint.Cap.ROUND
        }
        private val weeklyArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(context, 1.5f)
            strokeCap = Paint.Cap.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = dpF(context, 9.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            fontFeatureSettings = "tnum"
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val outerRect = RectF()
        private val innerRect = RectF()
        private var f5: Float = 0f
        private var fW: Float = 0f
        private var label: String = "--"
        private var isStale: Boolean = false
        private var staleDotColor: Int = Color.parseColor("#FB923C")
        private var edge: DockEdge = DockEdge.NONE

        fun update(
            fiveHourFraction: Float,
            weeklyFraction: Float,
            centerText: String,
            trackColor: Int,
            ringColor: Int,
            weeklyAccentColor: Int,
            textColor: Int,
            edge: DockEdge,
            isStale: Boolean,
            staleDotColor: Int
        ) {
            this.f5 = fiveHourFraction.coerceIn(0f, 1f)
            this.fW = weeklyFraction.coerceIn(0f, 1f)
            this.label = centerText
            this.ringTrackPaint.color = trackColor
            this.ringProgressPaint.color = ringColor
            this.weeklyArcPaint.color = withAlpha(weeklyAccentColor, 185)
            this.textPaint.color = textColor
            this.edge = edge
            this.isStale = isStale
            this.staleDotColor = staleDotColor
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f
            val cy = h / 2f
            val inset = dpF(context, 3.2f)
            outerRect.set(inset, inset, w - inset, h - inset)
            canvas.drawArc(outerRect, 0f, 360f, false, ringTrackPaint)
            if (f5 > 0.005f) {
                canvas.drawArc(outerRect, -90f, 360f * f5, false, ringProgressPaint)
            }
            val innerInset = dpF(context, 6.4f)
            innerRect.set(innerInset, innerInset, w - innerInset, h - innerInset)
            if (fW > 0.005f) {
                canvas.drawArc(innerRect, -90f, 360f * fW, false, weeklyArcPaint)
            }
            val fm = textPaint.fontMetrics
            val baseline = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, cx, baseline, textPaint)

            if (isStale) {
                dotPaint.color = staleDotColor
                val dotX = if (edge == DockEdge.LEFT) w - dpF(context, 4f) else dpF(context, 4f)
                canvas.drawCircle(dotX, dpF(context, 4f), dpF(context, 2.2f), dotPaint)
            }
        }
    }

    companion object {
        const val HUD_VIEW_TAG = "bard_quota_hud_capsule_v19"
        const val MODULE_PACKAGE = "com.example.bardquotahook"
        const val HIGH_BATTERY_IMPACT_THRESHOLD = 20.0

        private fun resolveLocalizedHudLabels(context: Context, lang: Lang): LocalizedHudLabels {
            val isZh = lang == Lang.ZH
            return LocalizedHudLabels(
                fiveHour = "5h",
                weekly = if (isZh) "每周" else "Weekly",
                offlineTag = if (isZh) "离线" else "Offline",
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
                    staleText = Color.parseColor("#94A3B8"),
                    staleBg = Color.parseColor("#2494A3B8"),
                    staleBorder = Color.parseColor("#4494A3B8"),
                    staleBar = Color.parseColor("#64748B"),
                    offlineDot = Color.parseColor("#FB923C"),
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
                    staleText = Color.parseColor("#64748B"),
                    staleBg = Color.parseColor("#1A64748B"),
                    staleBorder = Color.parseColor("#3864748B"),
                    staleBar = Color.parseColor("#94A3B8"),
                    offlineDot = Color.parseColor("#F97316"),
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
                            staleText = Color.parseColor("#9CA3AF"),
                            staleBg = Color.parseColor("#249CA3AF"),
                            staleBorder = Color.parseColor("#449CA3AF"),
                            staleBar = Color.parseColor("#6B7280"),
                            offlineDot = Color.parseColor("#FB923C"),
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
                            staleText = Color.parseColor("#6B7280"),
                            staleBg = Color.parseColor("#1A6B7280"),
                            staleBorder = Color.parseColor("#386B7280"),
                            staleBar = Color.parseColor("#9CA3AF"),
                            offlineDot = Color.parseColor("#F97316"),
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
                    staleText = Color.parseColor("#9CA3AF"),
                    staleBg = Color.parseColor("#249CA3AF"),
                    staleBorder = Color.parseColor("#449CA3AF"),
                    staleBar = Color.parseColor("#6B7280"),
                    offlineDot = Color.parseColor("#FB923C"),
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
                    staleText = Color.parseColor("#6B7280"),
                    staleBg = Color.parseColor("#1A6B7280"),
                    staleBorder = Color.parseColor("#386B7280"),
                    staleBar = Color.parseColor("#9CA3AF"),
                    offlineDot = Color.parseColor("#F97316"),
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
