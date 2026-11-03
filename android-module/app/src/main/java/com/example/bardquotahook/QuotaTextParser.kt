package com.example.bardquotahook

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object QuotaTextParser {

    const val ACTION_QUOTA_INTERNAL_SYNC = "com.example.bardquotahook.ACTION_QUOTA_SYNC"
    const val ACTION_QUOTA_EXTERNAL_SYNC = "com.example.bardquotahook.ACTION_QUOTA_EXTERNAL_SYNC"
    const val ACTION_HOOK_HEARTBEAT = "com.example.bardquotahook.ACTION_HOOK_HEARTBEAT"
    const val ACTION_REQUEST_QUOTA_REFRESH = "com.example.bardquotahook.ACTION_REQUEST_QUOTA_REFRESH"
    const val PREFS_NAME = "gemini_eye_state"

    enum class MetricKind { FIVE_HOUR, WEEKLY, RESET }

    private enum class SectionContext { NONE, FIVE_HOUR, WEEKLY }

    // Strict whitelist validators: HUD TextViews can ONLY render valid numeric % / ratios & compact times
    private val validQuotaMetricRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex("""^(?:\d{1,3}(?:\.\d+)?%|\d{1,5}/\d{1,5})$""")
    }

    private val validResetTokenRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """^(?:\d{1,3}d(?:\d{1,2}h)?|\d{1,3}h(?:\d{1,2}m)?|\d{1,3}m|(?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun|Tomorrow|周一|周二|周三|周四|周五|周六|周日|明天)\s*)?\d{1,2}:\d{2}(?:\s*[AP]M)?)$""",
            RegexOption.IGNORE_CASE
        )
    }

    private val structuredQuotaKeyRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """^(?:five_hour_quota|five_hour_used|5h_quota|short_term_quota|session_quota|remaining_quota|quota_remaining|weekly_limit|weekly_quota|weekly_used|quota_limit|max_quota|five_reset|five_hour_reset|weekly_reset|reset_time|quota_reset)$""",
            RegexOption.IGNORE_CASE
        )
    }

    private val ratioOrPctRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex("""(\d{1,5}\s*/\s*\d{1,5}|\d{1,3}(?:\.\d+)?\s*%)""")
    }

    private val pctNumRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex("""^(\d{1,3}(?:\.\d+)?)%$""")
    }

    private val ratioNumRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex("""^(\d{1,5})/(\d{1,5})$""")
    }

    private val explicitUsedPctRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """(?:(?:已使用|已用|剩余|可用)\s*(\d{1,3}(?:\.\d+)?\s*%)|(\d{1,3}(?:\.\d+)?\s*%)\s*(?:used|remaining|available|left|已使用|已用|剩余|可用))""",
            RegexOption.IGNORE_CASE
        )
    }

    private val standaloneMetricRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """^(?:已使用\s*|已用\s*|剩余\s*|可用\s*)?(\d{1,5}\s*/\s*\d{1,5}|\d{1,3}(?:\.\d+)?\s*%)(?:\s*(?:used|remaining|available|left|已使用|已用|剩余|可用))?$""",
            RegexOption.IGNORE_CASE
        )
    }

    private val compactTimeRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """((?:Mon|Tue|Wed|Thu|Fri|Sat|Sun|周一|周二|周三|周四|周五|周六|周日|星期[一二三四五六日天]|明天|明日|Tomorrow)\s*,?\s*\d{1,2}:\d{2}(?:\s*[AP]M)?|\d{1,2}:\d{2}(?:\s*[AP]M)?|\d{1,3}\s*(?:d|day|days|天)(?:\s*\d{1,2}\s*(?:h|hr|hrs|hours?|小时))?|\d{1,3}\s*(?:h|hr|hrs|hours?|小时|时)(?:\s*\d{1,2}\s*(?:m|min|mins|minutes?|分钟|分))?|\d{1,3}\s*(?:m|min|mins|minutes?|分钟|分))""",
            RegexOption.IGNORE_CASE
        )
    }

    private val usagePageHeaderRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """.*(usage\s+limits?|用量限制|用量与限额|使用限额|compute\s+allowance|gemini\s+app\s+activity).*""",
            RegexOption.IGNORE_CASE
        )
    }

    private val fiveHourLabelRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """.*(5\s*(-|\s)?(hour|hr|h|小时)|(?:\b5h\b)|short[\s-]*term|current\s+(?:usage|session|window|cycle|allowance)|session\s+(?:usage|limit)|当前(?:用量|会话|周期|窗口)|短期(?:限额|用量|额度)).*""",
            RegexOption.IGNORE_CASE
        )
    }

    private val weeklyLabelRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """.*(weekly|week\s+(?:limit|cap|usage)|每周(?:限额|上限|额度|用量)?|周限额|周上限|周额度|周用量|7\s*(-|\s)?(day|天)).*""",
            RegexOption.IGNORE_CASE
        )
    }

    private val resetLabelRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """.*(resets?\b|重置|恢复|refreshes?\s+(?:in|at)|available\s+(?:in|at)|try\s+again\s+(?:in|after|at)|后重置|后恢复).*""",
            RegexOption.IGNORE_CASE
        )
    }

    private val exhaustedBannerRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """.*(reached\s+your\s+(?:usage\s+)?limit|out\s+of\s+(?:prompts|capacity)|quota\s+exceeded|已达(?:到)?(?:用量|使用)?上限|额度已用尽).*""",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * Strictly validates a quota metric ("15%", "29.5%", "32/50").
     * Rejects all config strings, prose, or non-numeric labels by returning "--".
     */
    fun sanitizeQuotaMetric(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "--") return "--"
        val compact = raw.replace(" ", "").trim()
        if (!validQuotaMetricRegex.matches(compact)) return "--"
        val pctMatch = pctNumRegex.matchEntire(compact)
        if (pctMatch != null) {
            val pct = pctMatch.groupValues.getOrNull(1)?.toFloatOrNull() ?: return "--"
            if (pct < 0f || pct > 100f) return "--"
        }
        return compact
    }

    /**
     * Strictly validates a reset countdown/clock token ("1h52m", "23h52m", "4d12h", "Mon 9:00 AM").
     * Rejects all arbitrary text, CJK sentences, or debug labels by returning "--".
     */
    fun sanitizeResetToken(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "--") return "--"
        val normalized = compactResetDisplay(raw)
        if (!validResetTokenRegex.matches(normalized)) return "--"
        return normalized
    }

    fun sanitizeSnapshot(snapshot: QuotaHudView.Snapshot): QuotaHudView.Snapshot {
        val cFive = sanitizeQuotaMetric(snapshot.fiveHourQuota)
        val cFiveReset = sanitizeResetToken(snapshot.fiveHourReset)
        val cWeek = sanitizeQuotaMetric(snapshot.weeklyLimit)
        val cWeekReset = sanitizeResetToken(snapshot.weeklyReset)
        if (cFive == snapshot.fiveHourQuota &&
            cFiveReset == snapshot.fiveHourReset &&
            cWeek == snapshot.weeklyLimit &&
            cWeekReset == snapshot.weeklyReset
        ) {
            return snapshot
        }
        return snapshot.copy(
            fiveHourQuota = cFive,
            fiveHourReset = cFiveReset,
            weeklyLimit = cWeek,
            weeklyReset = cWeekReset
        )
    }

    fun parseProgressFraction(metric: String): Float {
        val clean = sanitizeQuotaMetric(metric)
        if (clean == "--") return 0f
        val pctMatch = pctNumRegex.matchEntire(clean)
        if (pctMatch != null) {
            val pct = pctMatch.groupValues.getOrNull(1)?.toFloatOrNull() ?: 0f
            return (pct / 100f).coerceIn(0f, 1f)
        }
        val ratioMatch = ratioNumRegex.matchEntire(clean)
        if (ratioMatch != null) {
            val num = ratioMatch.groupValues.getOrNull(1)?.toFloatOrNull() ?: 0f
            val den = ratioMatch.groupValues.getOrNull(2)?.toFloatOrNull() ?: 0f
            if (den > 0f) {
                return (num / den).coerceIn(0f, 1f)
            }
        }
        return 0f
    }

    fun fastHasQuotaKeyword(text: String): Boolean {
        val len = text.length
        if (len < 3 || len > 262_144) return false
        return text.contains('%') ||
            text.contains("quota", ignoreCase = true) ||
            text.contains("limit", ignoreCase = true) ||
            text.contains("usage", ignoreCase = true) ||
            text.contains("reset", ignoreCase = true) ||
            text.contains("5-hour", ignoreCase = true) ||
            text.contains("weekly", ignoreCase = true) ||
            text.contains("已使用") ||
            text.contains("已用") ||
            text.contains("限额") ||
            text.contains("重置") ||
            text.contains("上限")
    }

    private fun extractMetricToken(line: String, kind: MetricKind): String? {
        when (kind) {
            MetricKind.FIVE_HOUR, MetricKind.WEEKLY -> {
                val explicitMatch = explicitUsedPctRegex.find(line)
                if (explicitMatch != null) {
                    val g1 = explicitMatch.groupValues.getOrNull(1).orEmpty()
                    val g2 = explicitMatch.groupValues.getOrNull(2).orEmpty()
                    val pct = sanitizeQuotaMetric(if (g1.isNotEmpty()) g1 else g2)
                    if (pct != "--") return pct
                }
                val ratioMatch = ratioOrPctRegex.find(line)?.value
                if (ratioMatch != null) {
                    val clean = sanitizeQuotaMetric(ratioMatch)
                    if (clean != "--") return clean
                }
            }
            MetricKind.RESET -> {
                val timeMatch = compactTimeRegex.find(line)?.value?.trim()
                if (timeMatch != null) {
                    val clean = sanitizeResetToken(timeMatch)
                    if (clean != "--") return clean
                }
            }
        }
        return null
    }

    private fun compactResetDisplay(raw: String): String {
        return raw
            .replace(",", " ")
            .replace("星期一", "周一")
            .replace("星期二", "周二")
            .replace("星期三", "周三")
            .replace("星期四", "周四")
            .replace("星期五", "周五")
            .replace("星期六", "周六")
            .replace("星期日", "周日")
            .replace("星期天", "周日")
            .replace("明日", "明天")
            .replace(Regex("""days?""", RegexOption.IGNORE_CASE), "d")
            .replace(Regex("""hours?|hrs?""", RegexOption.IGNORE_CASE), "h")
            .replace(Regex("""minutes?|mins?""", RegexOption.IGNORE_CASE), "m")
            .replace("天", "d")
            .replace("小时", "h")
            .replace("时", "h")
            .replace("分钟", "m")
            .replace("分", "m")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""(\d)\s+([dhm])\b""", RegexOption.IGNORE_CASE), "$1$2")
            .replace(Regex("""(\d[dh])\s+(\d)""", RegexOption.IGNORE_CASE), "$1$2")
            .trim()
    }

    private fun normalizeValue(raw: String, kind: MetricKind): String {
        val singleLine = raw.replace(Regex("\\s+"), " ").trim()
        if (singleLine.isEmpty()) return "--"
        return extractMetricToken(singleLine, kind) ?: "--"
    }

    /**
     * Checks ONLY for explicit Gemini rate-limit exhaustion banners in active screen views
     * (never scrapes user chat bubbles or config text).
     */
    fun parseExhaustedBannerOnly(
        block: String,
        prev: QuotaHudView.Snapshot,
        source: String
    ): QuotaHudView.Snapshot? {
        val base = sanitizeSnapshot(prev)
        var fiveHour = base.fiveHourQuota
        var fiveReset = base.fiveHourReset
        var hit = false

        for (rawLine in block.lineSequence()) {
            val line = rawLine.trim()
            if (line.length !in 4..200) continue
            if (exhaustedBannerRegex.matches(line)) {
                if (fiveHour == "--") {
                    fiveHour = "100%"
                    hit = true
                }
                val bannerReset = extractMetricToken(line, MetricKind.RESET)
                if (bannerReset != null) {
                    fiveReset = bannerReset
                    hit = true
                }
            }
        }
        if (!hit) return null
        val next = base.copy(
            fiveHourQuota = fiveHour,
            fiveHourReset = fiveReset,
            source = source
        ).sanitized()
        return if (next != base) next else null
    }

    /**
     * Parses structured usage-limits DOM text from `https://gemini.google.com/usage`,
     * extracting separate 5h & Weekly percentages and reset countdowns.
     */
    fun parseUiBlock(
        block: String,
        prev: QuotaHudView.Snapshot,
        source: String,
        requireUsageStructure: Boolean = true
    ): QuotaHudView.Snapshot? {
        val base = sanitizeSnapshot(prev)

        if (requireUsageStructure) {
            var hasHeaderOrExplicit = false
            var hasWindowLabel = false
            var hasResetLabel = false
            for (rawLine in block.lineSequence()) {
                val l = rawLine.trim()
                if (l.length !in 2..200) continue
                if (usagePageHeaderRegex.matches(l) ||
                    explicitUsedPctRegex.containsMatchIn(l) ||
                    exhaustedBannerRegex.matches(l)
                ) {
                    hasHeaderOrExplicit = true
                    break
                }
                if (fiveHourLabelRegex.matches(l) || weeklyLabelRegex.matches(l)) {
                    hasWindowLabel = true
                }
                if (resetLabelRegex.matches(l)) {
                    hasResetLabel = true
                }
            }
            if (!hasHeaderOrExplicit && !(hasWindowLabel && hasResetLabel)) {
                return null
            }
        }

        var fiveHour = base.fiveHourQuota
        var fiveReset = base.fiveHourReset
        var weekly = base.weeklyLimit
        var weekReset = base.weeklyReset
        var hit = false

        var section = SectionContext.NONE
        var pendingSlot: MetricKind? = null
        var sawFirstMeterInBlock = false

        for (rawLine in block.lineSequence()) {
            val line = rawLine.trim()
            if (line.length !in 2..200) continue

            if (exhaustedBannerRegex.matches(line)) {
                if (fiveHour == "--") {
                    fiveHour = "100%"
                    hit = true
                }
                val bannerReset = extractMetricToken(line, MetricKind.RESET)
                if (bannerReset != null) {
                    fiveReset = bannerReset
                    hit = true
                }
            }

            if (pendingSlot != null) {
                if (pendingSlot == MetricKind.RESET) {
                    val resetToken = extractMetricToken(line, MetricKind.RESET)
                    if (resetToken != null) {
                        if (section == SectionContext.WEEKLY) {
                            weekReset = resetToken
                        } else {
                            fiveReset = resetToken
                        }
                        hit = true
                        pendingSlot = null
                        continue
                    }
                } else if (standaloneMetricRegex.matches(line)) {
                    val token = extractMetricToken(line, pendingSlot)
                    if (token != null) {
                        if (pendingSlot == MetricKind.FIVE_HOUR) {
                            fiveHour = token
                            sawFirstMeterInBlock = true
                        } else {
                            weekly = token
                        }
                        hit = true
                        pendingSlot = null
                        continue
                    }
                }
            }

            if (usagePageHeaderRegex.matches(line) && section == SectionContext.NONE) {
                section = SectionContext.FIVE_HOUR
            }

            if (weeklyLabelRegex.matches(line)) {
                section = SectionContext.WEEKLY
                val token = extractMetricToken(line, MetricKind.WEEKLY)
                if (token != null) {
                    weekly = token
                    hit = true
                    pendingSlot = null
                } else {
                    pendingSlot = MetricKind.WEEKLY
                }
                val inlineReset = if (resetLabelRegex.matches(line)) extractMetricToken(line, MetricKind.RESET) else null
                if (inlineReset != null) {
                    weekReset = inlineReset
                    hit = true
                }
                continue
            }

            if (fiveHourLabelRegex.matches(line)) {
                section = SectionContext.FIVE_HOUR
                val token = extractMetricToken(line, MetricKind.FIVE_HOUR)
                if (token != null) {
                    fiveHour = token
                    sawFirstMeterInBlock = true
                    hit = true
                    pendingSlot = null
                } else {
                    pendingSlot = MetricKind.FIVE_HOUR
                }
                val inlineReset = if (resetLabelRegex.matches(line)) extractMetricToken(line, MetricKind.RESET) else null
                if (inlineReset != null) {
                    fiveReset = inlineReset
                    hit = true
                }
                continue
            }

            val explicitPctMatch = explicitUsedPctRegex.find(line)
            if (explicitPctMatch != null) {
                val g1 = explicitPctMatch.groupValues.getOrNull(1).orEmpty()
                val g2 = explicitPctMatch.groupValues.getOrNull(2).orEmpty()
                val pct = sanitizeQuotaMetric(if (g1.isNotEmpty()) g1 else g2)
                if (pct != "--") {
                    if (section == SectionContext.WEEKLY) {
                        weekly = pct
                    } else if (!sawFirstMeterInBlock) {
                        fiveHour = pct
                        sawFirstMeterInBlock = true
                        section = SectionContext.FIVE_HOUR
                    } else {
                        weekly = pct
                        section = SectionContext.WEEKLY
                    }
                    hit = true
                    pendingSlot = null
                }
                if (resetLabelRegex.matches(line)) {
                    val inlineReset = extractMetricToken(line, MetricKind.RESET)
                    if (inlineReset != null) {
                        if (section == SectionContext.WEEKLY) {
                            weekReset = inlineReset
                        } else {
                            fiveReset = inlineReset
                        }
                        hit = true
                    }
                }
                continue
            }

            if (resetLabelRegex.matches(line)) {
                val token = extractMetricToken(line, MetricKind.RESET)
                if (token != null) {
                    if (section == SectionContext.WEEKLY) {
                        weekReset = token
                    } else {
                        fiveReset = token
                    }
                    hit = true
                    pendingSlot = null
                } else {
                    pendingSlot = MetricKind.RESET
                }
            }
        }

        if (!hit) return null
        val candidate = QuotaHudView.Snapshot(
            fiveHourQuota = fiveHour,
            fiveHourReset = fiveReset,
            weeklyLimit = weekly,
            weeklyReset = weekReset,
            source = source
        ).sanitized()

        if (candidate == base || !candidate.hasLiveData) return null
        return candidate
    }

    fun inspectJsonObject(
        json: JSONObject,
        prev: QuotaHudView.Snapshot,
        source: String
    ): QuotaHudView.Snapshot? {
        val extracted = mutableMapOf<String, String>()
        collectFromJson(json, extracted, 0)
        if (extracted.isEmpty()) return null
        return mergeExtractedFields(extracted, prev, source)
    }

    fun inspectJsonArray(
        arr: JSONArray,
        prev: QuotaHudView.Snapshot,
        source: String
    ): QuotaHudView.Snapshot? {
        val extracted = mutableMapOf<String, String>()
        collectFromJson(arr, extracted, 0)
        if (extracted.isEmpty()) return null
        return mergeExtractedFields(extracted, prev, source)
    }

    fun parseRawPayload(
        payload: String,
        prev: QuotaHudView.Snapshot,
        source: String
    ): QuotaHudView.Snapshot? {
        val cleaned = payload
            .removePrefix(")]}'\n")
            .removePrefix(")]}'")
            .trim()

        var working = sanitizeSnapshot(prev)
        var changed = false

        val firstBracket = cleaned.indexOfFirst { it == '{' || it == '[' }
        if (firstBracket in 0..32) {
            val candidate = cleaned.substring(firstBracket)
            try {
                when (val node = JSONTokener(candidate).nextValue()) {
                    is JSONObject -> {
                        val snap = inspectJsonObject(node, working, source)
                        if (snap != null) {
                            working = snap
                            changed = true
                        }
                    }
                    is JSONArray -> {
                        val snap = inspectJsonArray(node, working, source)
                        if (snap != null) {
                            working = snap
                            changed = true
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        val uiSnap = parseUiBlock(cleaned.replace("\\\"", "\""), working, source, requireUsageStructure = true)
        if (uiSnap != null) {
            working = uiSnap
            changed = true
        }

        return if (changed) working else null
    }

    private fun collectFromJson(node: Any?, out: MutableMap<String, String>, depth: Int) {
        if (depth > 5 || node == null) return
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (structuredQuotaKeyRegex.matches(key) &&
                        value != null &&
                        value !is JSONObject &&
                        value !is JSONArray
                    ) {
                        out[key.lowercase()] = value.toString()
                    } else if (value is JSONObject || value is JSONArray) {
                        collectFromJson(value, out, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                val limit = minOf(node.length(), 25)
                for (i in 0 until limit) {
                    val elem = node.opt(i)
                    if (elem is JSONObject || elem is JSONArray) {
                        collectFromJson(elem, out, depth + 1)
                    }
                }
            }
        }
    }

    private fun mergeExtractedFields(
        fields: Map<String, String>,
        prev: QuotaHudView.Snapshot,
        source: String
    ): QuotaHudView.Snapshot? {
        val base = sanitizeSnapshot(prev)
        var fiveHour = base.fiveHourQuota
        var fiveReset = base.fiveHourReset
        var weekly = base.weeklyLimit
        var weekReset = base.weeklyReset

        var remainingVal: String? = null
        var totalLimitVal: String? = null

        for ((k, v) in fields) {
            when {
                k == "remaining_quota" || k == "quota_remaining" ->
                    remainingVal = v
                k == "quota_limit" || k == "max_quota" ->
                    totalLimitVal = v
                k.contains("week") && k.contains("reset") -> {
                    val clean = normalizeValue(v, MetricKind.RESET)
                    if (clean != "--") weekReset = clean
                }
                k.contains("reset") -> {
                    val clean = normalizeValue(v, MetricKind.RESET)
                    if (clean != "--") fiveReset = clean
                }
                k.contains("5h") || k.contains("five") || k.contains("short_term") || k.contains("session") -> {
                    val clean = normalizeValue(v, MetricKind.FIVE_HOUR)
                    if (clean != "--") fiveHour = clean
                }
                k.contains("week") -> {
                    val clean = normalizeValue(v, MetricKind.WEEKLY)
                    if (clean != "--") weekly = clean
                }
            }
        }

        if (remainingVal != null &&
            totalLimitVal != null &&
            remainingVal.all { it.isDigit() } &&
            totalLimitVal.all { it.isDigit() }
        ) {
            val ratio = sanitizeQuotaMetric("$remainingVal/$totalLimitVal")
            if (ratio != "--") fiveHour = ratio
        } else if (remainingVal != null) {
            val clean = normalizeValue(remainingVal, MetricKind.FIVE_HOUR)
            if (clean != "--") fiveHour = clean
        }

        val candidate = QuotaHudView.Snapshot(
            fiveHourQuota = fiveHour,
            fiveHourReset = fiveReset,
            weeklyLimit = weekly,
            weeklyReset = weekReset,
            source = source
        ).sanitized()

        if (candidate == base || !candidate.hasLiveData) return null
        return candidate
    }
}
