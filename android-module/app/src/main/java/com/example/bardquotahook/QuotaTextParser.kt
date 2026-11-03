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

    @Volatile private var pendingSlot: MetricKind? = null
    @Volatile private var pendingSlotTimestampMs: Long = 0L

    private val keyRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """["']?([a-zA-Z0-9_]*(?:quota|remaining|limit|reset|cap|usage|window)[a-zA-Z0-9_]*)["']?\s*[:=]\s*["']?([^"',}\]\n]{1,40})["']?""",
            RegexOption.IGNORE_CASE
        )
    }

    private val ratioOrPctRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex("""(\d+\s*/\s*\d+|\d+(?:\.\d+)?\s*%)""")
    }

    private val pctNumRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex("""(\d+(?:\.\d+)?)\s*%""")
    }

    private val ratioNumRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex("""(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)""")
    }

    private val explicitUsedPctRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """(?:(?:已使用|已用|剩余|可用)\s*(\d+(?:\.\d+)?\s*%)|(\d+(?:\.\d+)?\s*%)\s*(?:used|remaining|available|left|已使用|已用|剩余|可用))""",
            RegexOption.IGNORE_CASE
        )
    }

    private val countMetricRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """(\d+\s*(?:次|条|left|remaining|used|prompts?|messages?|queries))""",
            RegexOption.IGNORE_CASE
        )
    }

    private val standaloneMetricRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """^(?:已使用\s*|已用\s*|剩余\s*|可用\s*)?(\d+\s*/\s*\d+|\d+(?:\.\d+)?\s*%)(?:\s*(?:used|remaining|available|left|已使用|已用|剩余|可用))?$""",
            RegexOption.IGNORE_CASE
        )
    }

    private val compactTimeRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """((?:Mon|Tue|Wed|Thu|Fri|Sat|Sun|周一|周二|周三|周四|周五|周六|周日|星期[一二三四五六日天]|明天|明日|Tomorrow)\s*,?\s*\d{1,2}:\d{2}(?:\s*[AP]M)?|\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AP]M)?|\d+\s*(?:d|day|days|天)\s*\d*\s*(?:h|hr|hrs|小时)?|\d+\s*(?:h|hr|hrs|小时|时)\s*\d*\s*(?:m|min|mins|分钟|分)?|\d+\s*(?:m|min|mins|分钟|分))""",
            RegexOption.IGNORE_CASE
        )
    }

    private val usagePageHeaderRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """.*(usage\s+limits?|用量限制|用量与限额|使用限额|compute\s+allowance).*""",
            RegexOption.IGNORE_CASE
        )
    }

    private val fiveHourLabelRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """.*(5\s*(-|\s)?(hour|hr|h|小时)|5h|short[\s-]*term|current\s+(?:usage|session|window|cycle|allowance)|session\s+(?:usage|limit)|当前(?:用量|会话|周期|窗口)|短期(?:限额|用量|额度)?|短周期|remaining\s*prompts?|剩余\s*\d+).*""",
            RegexOption.IGNORE_CASE
        )
    }

    private val weeklyLabelRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """.*(weekly|week\s+(?:limit|cap|usage)|每周|周限额|周上限|周额度|周用量|7\s*(-|\s)?(day|天)).*""",
            RegexOption.IGNORE_CASE
        )
    }

    private val resetLabelRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """.*(resets?\b|重置|恢复|刷新|refreshes?\s+(?:in|at)|available\s+(?:in|at)|try\s+again\s+(?:in|after|at)|后重置|后恢复).*""",
            RegexOption.IGNORE_CASE
        )
    }

    private val exhaustedBannerRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """.*(reached\s+your\s+(?:usage\s+)?limit|out\s+of\s+(?:prompts|capacity)|quota\s+exceeded|已达(?:到)?(?:用量|使用)?上限|额度已用尽).*""",
            RegexOption.IGNORE_CASE
        )
    }

    fun parseProgressFraction(metric: String): Float {
        if (metric == "--" || metric.isBlank()) return 0f
        val pctMatch = pctNumRegex.find(metric)
        if (pctMatch != null) {
            val pct = pctMatch.groupValues.getOrNull(1)?.toFloatOrNull() ?: 0f
            return (pct / 100f).coerceIn(0f, 1f)
        }
        val ratioMatch = ratioNumRegex.find(metric)
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
            text.contains("remaining", ignoreCase = true) ||
            text.contains("limit", ignoreCase = true) ||
            text.contains("usage", ignoreCase = true) ||
            text.contains("reset", ignoreCase = true) ||
            text.contains("5-hour", ignoreCase = true) ||
            text.contains("weekly", ignoreCase = true) ||
            text.contains("小时") ||
            text.contains("已使用") ||
            text.contains("已用") ||
            text.contains("剩余") ||
            text.contains("限额") ||
            text.contains("额度") ||
            text.contains("重置")
    }

    private fun extractMetricToken(line: String, kind: MetricKind): String? {
        when (kind) {
            MetricKind.FIVE_HOUR, MetricKind.WEEKLY -> {
                val explicitMatch = explicitUsedPctRegex.find(line)
                if (explicitMatch != null) {
                    val g1 = explicitMatch.groupValues.getOrNull(1).orEmpty()
                    val g2 = explicitMatch.groupValues.getOrNull(2).orEmpty()
                    val pct = (if (g1.isNotEmpty()) g1 else g2).replace(" ", "")
                    if (pct.isNotEmpty()) return pct
                }
                val ratioMatch = ratioOrPctRegex.find(line)?.value?.replace(" ", "")
                if (ratioMatch != null) return ratioMatch
                val countMatch = countMetricRegex.find(line)?.value?.trim()
                if (countMatch != null) return countMatch.take(14)
            }
            MetricKind.RESET -> {
                val timeMatch = compactTimeRegex.find(line)?.value?.trim()
                if (timeMatch != null) return compactResetDisplay(timeMatch)
            }
        }
        return null
    }

    private fun compactResetDisplay(raw: String): String {
        val cleaned = raw
            .replace(Regex("""\s+"""), " ")
            .replace("days", "d", ignoreCase = true)
            .replace("day", "d", ignoreCase = true)
            .replace("hours", "h", ignoreCase = true)
            .replace("hour", "h", ignoreCase = true)
            .replace("hrs", "h", ignoreCase = true)
            .replace("hr", "h", ignoreCase = true)
            .replace("minutes", "m", ignoreCase = true)
            .replace("minute", "m", ignoreCase = true)
            .replace("mins", "m", ignoreCase = true)
            .replace("min", "m", ignoreCase = true)
            .replace("天", "d")
            .replace("小时", "h")
            .replace("分钟", "m")
            .replace("分", "m")
            .replace("d ", "d")
            .replace("h ", "h")
            .trim()
        return if (cleaned.length > 15) cleaned.take(14) + "…" else cleaned
    }

    private fun normalizeValue(raw: String, kind: MetricKind): String {
        val singleLine = raw.replace(Regex("\\s+"), " ").trim()
        if (singleLine.isEmpty()) return "--"
        val token = extractMetricToken(singleLine, kind)
        if (token != null) return token
        return if (singleLine.length > 14) singleLine.take(13) + "…" else singleLine
    }

    /**
     * Parses UI text from Compose layouts, TextViews, Accessibility descriptions,
     * or `https://gemini.google.com/usage` innerText, extracting separate 5h & Weekly resets.
     */
    fun parseUiBlock(
        block: String,
        prev: QuotaHudView.Snapshot,
        source: String
    ): QuotaHudView.Snapshot? {
        var fiveHour = prev.fiveHourQuota
        var fiveReset = prev.fiveHourReset
        var weekly = prev.weeklyLimit
        var weekReset = prev.weeklyReset
        var hit = false

        var section = SectionContext.NONE
        var sawFirstMeterInBlock = false
        val now = System.currentTimeMillis()

        for (rawLine in block.lineSequence()) {
            val line = rawLine.trim()
            if (line.length !in 2..200) continue

            // Check rate-limit exhaustion banners in chat
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

            // Pair standalone values ("15%", "29%", "32/50") with recent Compose label node
            val activeSlot = pendingSlot
            if (activeSlot != null && (now - pendingSlotTimestampMs) <= 700L) {
                if (activeSlot == MetricKind.RESET) {
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
                    val token = extractMetricToken(line, activeSlot) ?: line.replace(" ", "").take(12)
                    if (activeSlot == MetricKind.FIVE_HOUR) {
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
                    pendingSlotTimestampMs = now
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
                    pendingSlotTimestampMs = now
                }
                val inlineReset = if (resetLabelRegex.matches(line)) extractMetricToken(line, MetricKind.RESET) else null
                if (inlineReset != null) {
                    fiveReset = inlineReset
                    hit = true
                }
                continue
            }

            // Explicit "% used" / "已使用 X%" meter lines on gemini.google.com/usage
            val explicitPctMatch = explicitUsedPctRegex.find(line)
            if (explicitPctMatch != null) {
                val g1 = explicitPctMatch.groupValues.getOrNull(1).orEmpty()
                val g2 = explicitPctMatch.groupValues.getOrNull(2).orEmpty()
                val pct = (if (g1.isNotEmpty()) g1 else g2).replace(" ", "")
                if (pct.isNotEmpty()) {
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
                    pendingSlotTimestampMs = now
                }
            }
        }

        if (!hit) return null
        if (fiveHour == prev.fiveHourQuota &&
            fiveReset == prev.fiveHourReset &&
            weekly == prev.weeklyLimit &&
            weekReset == prev.weeklyReset
        ) {
            return null
        }
        return QuotaHudView.Snapshot(
            fiveHourQuota = fiveHour,
            fiveHourReset = fiveReset,
            weeklyLimit = weekly,
            weeklyReset = weekReset,
            source = source
        )
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

        var working = prev
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

        val unescaped = cleaned.replace("\\\"", "\"")
        val extracted = mutableMapOf<String, String>()
        keyRegex.findAll(unescaped).take(40).forEach { match ->
            extracted[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        if (extracted.isNotEmpty()) {
            val snap = mergeExtractedFields(extracted, working, source)
            if (snap != null) {
                working = snap
                changed = true
            }
        }

        val uiSnap = parseUiBlock(unescaped, working, source)
        if (uiSnap != null) {
            working = uiSnap
            changed = true
        }

        return if (changed) working else null
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
                val limit = minOf(node.length(), 40)
                for (i in 0 until limit) {
                    val elem = node.opt(i)
                    if (elem is String && fastHasQuotaKeyword(elem)) {
                        val trimmed = elem.trim()
                        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                            try {
                                collectFromJson(JSONTokener(trimmed).nextValue(), out, depth + 1)
                            } catch (_: Throwable) {
                                keyRegex.findAll(trimmed.replace("\\\"", "\"")).take(20).forEach { m ->
                                    out[m.groupValues[1].lowercase()] = m.groupValues[2]
                                }
                            }
                        } else {
                            keyRegex.findAll(trimmed.replace("\\\"", "\"")).take(20).forEach { m ->
                                out[m.groupValues[1].lowercase()] = m.groupValues[2]
                            }
                        }
                    } else {
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
        var fiveHour = prev.fiveHourQuota
        var fiveReset = prev.fiveHourReset
        var weekly = prev.weeklyLimit
        var weekReset = prev.weeklyReset

        var remainingVal: String? = null
        var totalLimitVal: String? = null

        for ((k, v) in fields) {
            when {
                k == "remaining" || k.endsWith("_remaining") || k.startsWith("remaining_") ->
                    remainingVal = v
                k == "limit" || k == "quota_limit" || k == "max_quota" ->
                    totalLimitVal = v
                (k.contains("week") || k.contains("cap")) && (k.contains("reset") || k.contains("expire") || k.contains("refresh")) ->
                    weekReset = normalizeValue(v, MetricKind.RESET)
                k.contains("reset") || k.contains("expire") || k.contains("refresh") ->
                    fiveReset = normalizeValue(v, MetricKind.RESET)
                k.contains("5h") || k.contains("five") || k.contains("window") || k.contains("session") || k.contains("quota") ->
                    fiveHour = normalizeValue(v, MetricKind.FIVE_HOUR)
                k.contains("week") || k.contains("cap") ->
                    weekly = normalizeValue(v, MetricKind.WEEKLY)
            }
        }

        if (remainingVal != null && totalLimitVal != null && remainingVal.all { it.isDigit() } && totalLimitVal.all { it.isDigit() }) {
            fiveHour = "$remainingVal/$totalLimitVal"
        } else if (remainingVal != null && fiveHour == prev.fiveHourQuota) {
            fiveHour = normalizeValue(remainingVal, MetricKind.FIVE_HOUR)
        } else if (totalLimitVal != null && weekly == prev.weeklyLimit) {
            weekly = normalizeValue(totalLimitVal, MetricKind.WEEKLY)
        }

        if (fiveHour == prev.fiveHourQuota &&
            fiveReset == prev.fiveHourReset &&
            weekly == prev.weeklyLimit &&
            weekReset == prev.weeklyReset
        ) {
            return null
        }
        return QuotaHudView.Snapshot(
            fiveHourQuota = fiveHour,
            fiveHourReset = fiveReset,
            weeklyLimit = weekly,
            weeklyReset = weekReset,
            source = source
        )
    }
}
