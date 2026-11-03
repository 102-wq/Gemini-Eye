package com.example.bardquotahook

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
