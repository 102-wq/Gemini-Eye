package com.example.bardquotahook

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/**
 * Purely In-Process Quota Binary & Structured Parser (`QuotaTextParser`).
 *
 * Capabilities:
 * 1. Binary ProtoBuf Wire-Format & gRPC Frame Scanner (`scanProtoBufBytes`):
 *    - Unwraps gRPC length-prefixed frames (raw `0x00` and gzip `0x01`) + raw ProtoBuf payloads.
 *    - Walks wire tags (varint, fixed64/double, length-delimited sub-messages, fixed32/float).
 *    - Anchors on future reset epochs (`60s..5h10m` -> 5h window; `5h10m..7d1h` -> Weekly window)
 *      and `google.protobuf.Timestamp` sub-messages paired with fraction/percentage or `(used, limit)` fields.
 * 2. Structured JSON / JSPB Array Inspector (`parseInAppStructuredPayload`, `inspectJsonObject`).
 * 3. Strict HUD Whitelist Sanitizer (`sanitizeQuotaMetric`, `sanitizeResetToken`) so non-numeric
 *    labels or chat text can never leak into `QuotaHudView`.
 */
object QuotaTextParser {

    const val ACTION_QUOTA_INTERNAL_SYNC = "com.example.bardquotahook.ACTION_QUOTA_SYNC"
    const val ACTION_QUOTA_EXTERNAL_SYNC = "com.example.bardquotahook.ACTION_QUOTA_EXTERNAL_SYNC"
    const val ACTION_HOOK_HEARTBEAT = "com.example.bardquotahook.ACTION_HOOK_HEARTBEAT"
    const val ACTION_REQUEST_QUOTA_REFRESH = "com.example.bardquotahook.ACTION_REQUEST_QUOTA_REFRESH"
    const val PREFS_NAME = "gemini_eye_state"

    private const val FIVE_HOUR_MAX_WINDOW_SEC = 5L * 3600L + 600L      // 5h 10m
    private const val WEEKLY_MAX_WINDOW_SEC = 7L * 86400L + 3600L       // 7d 1h
    private const val MIN_FUTURE_RESET_SEC = 45L
    private const val MAX_PROTO_SCAN_BYTES = 65_536
    private const val MAX_GUNZIP_BYTES = 65_536

    enum class MetricKind { FIVE_HOUR, WEEKLY, RESET }

    private data class DecodedProtoWindow(
        val kind: MetricKind,
        val metric: String,
        val resetToken: String,
        val confidence: Int
    )

    private data class ScalarEntry(
        val fieldNumber: Int,
        val wireType: Int,
        val intVal: Long?,
        val doubleVal: Double?
    )

    // Strict whitelist validators: HUD TextViews can ONLY render valid numeric % / ratios & compact times
    private val validQuotaMetricRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex("""^(?:\d{1,3}(?:\.\d+)?%|\d{1,5}/\d{1,5})$""")
    }

    private val validResetTokenRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """^(?:\d{1,3}(?:\.\d+)?d(?:\d{1,2}h)?|\d{1,3}h(?:\d{1,2}m)?|\d{1,3}m|(?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun|Tomorrow|周一|周二|周三|周四|周五|周六|周日|明天)\s*)?\d{1,2}:\d{2}(?:\s*[AP]M)?)$""",
            RegexOption.IGNORE_CASE
        )
    }

    private val structuredQuotaKeyRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """^(?:five_hour_quota|five_hour_used|five_hour_percent|5h_quota|short_term_quota|short_term_percent|session_quota|remaining_quota|quota_remaining|prompts_remaining|weekly_limit|weekly_quota|weekly_used|weekly_percent|quota_limit|max_quota|five_reset|five_hour_reset|short_term_reset|weekly_reset|reset_time|reset_timestamp|reset_epoch|quota_reset)$""",
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

    private val compactTimeRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """((?:Mon|Tue|Wed|Thu|Fri|Sat|Sun|周一|周二|周三|周四|周五|周六|周日|星期[一二三四五六日天]|明天|明日|Tomorrow)\s*,?\s*\d{1,2}:\d{2}(?:\s*[AP]M)?|\d{1,2}:\d{2}(?:\s*[AP]M)?|\d{1,3}\s*(?:d|day|days|天)(?:\s*\d{1,2}\s*(?:h|hr|hrs|hours?|小时))?|\d{1,3}\s*(?:h|hr|hrs|hours?|小时|时)(?:\s*\d{1,2}\s*(?:m|min|mins|minutes?|分钟|分))?|\d{1,3}\s*(?:m|min|mins|minutes?|分钟|分))""",
            RegexOption.IGNORE_CASE
        )
    }

    private val exhaustedBannerRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            """.*(reached\s+your\s+(?:usage\s+)?limit|out\s+of\s+(?:prompts|capacity)|quota\s+exceeded|已达(?:到)?(?:用量|使用)?上限|额度已用尽).*""",
            RegexOption.IGNORE_CASE
        )
    }

    // Standard Gemini / Google compute cap denominators used to validate (used, limit) varint pairs
    private val plausibleLimitCaps = setOf(
        5, 10, 15, 20, 25, 30, 40, 50, 60, 75, 100, 120, 150, 200, 250, 300, 500, 1000, 1500, 2000, 5000
    )

    // =========================================================================
    // Strict HUD Whitelist Sanitizers
    // =========================================================================

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
            text.contains("remaining", ignoreCase = true) ||
            text.contains("weekly", ignoreCase = true) ||
            text.contains("已使用") ||
            text.contains("已用") ||
            text.contains("限额") ||
            text.contains("重置") ||
            text.contains("上限")
    }

    // =========================================================================
    // Epoch & Countdown Formatting Helpers (Shared by ProtoBuf & Memory Hooks)
    // =========================================================================

    fun formatDurationSeconds(deltaSec: Long): String {
        if (deltaSec <= 0L) return "--"
        val days = deltaSec / 86400L
        val remAfterDays = deltaSec % 86400L
        val hours = remAfterDays / 3600L
        val mins = (remAfterDays % 3600L) / 60L

        val raw = when {
            days >= 1L -> if (hours > 0L) "${days}d${hours}h" else "${days}d"
            hours >= 1L -> if (mins > 0L) "${hours}h${mins}m" else "${hours}h"
            else -> "${maxOf(1L, mins)}m"
        }
        return sanitizeResetToken(raw)
    }

    /**
     * Formats Weekly quota reset duration strictly in days (`Xd` or `X.Yd`) so the UI renders `天` / `day`.
     */
    fun formatWeeklyDurationSeconds(deltaSec: Long): String {
        if (deltaSec <= 0L) return "--"
        val daysFloat = (deltaSec.toDouble() / 86400.0).coerceAtLeast(0.1)
        val roundedTenths = (daysFloat * 10.0).roundToInt()
        val whole = roundedTenths / 10
        val frac = roundedTenths % 10
        val raw = if (frac == 0) "${whole}d" else "${whole}.${frac}d"
        return sanitizeResetToken(raw)
    }

    /**
     * Converts any sanitized weekly reset token (`6d`, `4d12h`, `23h52m`, `11h05m`, etc.)
     * into localized day display (`6天` / `4.5天` / `1天` for ZH, `6day` / `4.5day` / `1day` for EN).
     */
    fun formatWeeklyResetForDisplay(rawToken: String, isZh: Boolean): String {
        val clean = sanitizeResetToken(rawToken)
        if (clean == "--") return "--"
        val unit = if (isZh) "天" else "day"

        val lower = clean.lowercase()
        val decDayMatch = Regex("""^(\d{1,3}(?:\.\d+)?)d$""").matchEntire(lower)
        if (decDayMatch != null) {
            return "${decDayMatch.groupValues[1]}$unit"
        }

        val dhMatch = Regex("""^(\d{1,3})d(\d{1,2})h$""").matchEntire(lower)
        if (dhMatch != null) {
            val d = dhMatch.groupValues[1].toIntOrNull() ?: 0
            val h = dhMatch.groupValues[2].toIntOrNull() ?: 0
            val totalTenths = (((d * 24 + h).toDouble() / 24.0) * 10.0).roundToInt().coerceAtLeast(1)
            val whole = totalTenths / 10
            val frac = totalTenths % 10
            val numStr = if (frac == 0) "$whole" else "$whole.$frac"
            return "$numStr$unit"
        }

        val hmMatch = Regex("""^(?:(\d{1,3})h)?(?:(\d{1,2})m)?$""").matchEntire(lower)
        if (hmMatch != null && (hmMatch.groupValues[1].isNotEmpty() || hmMatch.groupValues[2].isNotEmpty())) {
            val h = hmMatch.groupValues[1].toIntOrNull() ?: 0
            val m = hmMatch.groupValues[2].toIntOrNull() ?: 0
            val totalHours = h.toDouble() + m.toDouble() / 60.0
            val totalTenths = ((totalHours / 24.0) * 10.0).roundToInt().coerceAtLeast(1)
            val whole = totalTenths / 10
            val frac = totalTenths % 10
            val numStr = if (frac == 0) "$whole" else "$whole.$frac"
            return "$numStr$unit"
        }

        return clean
    }

    /**
     * Classifies a candidate future Unix timestamp (in seconds or milliseconds) into
     * `MetricKind.FIVE_HOUR` (`45s .. 5h10m` ahead) or `MetricKind.WEEKLY` (`5h10m .. 7d1h` ahead).
     */
    fun classifyFutureEpochTimestamp(
        rawValue: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<MetricKind, String>? {
        val nowSec = nowMs / 1000L
        val deltaSec: Long = when {
            // Epoch seconds (roughly 2024..2040)
            rawValue in 1_700_000_000L..2_250_000_000L -> rawValue - nowSec
            // Epoch milliseconds
            rawValue in 1_700_000_000_000L..2_250_000_000_000L -> (rawValue - nowMs) / 1000L
            // Epoch microseconds
            rawValue in 1_700_000_000_000_000L..2_250_000_000_000_000L -> (rawValue / 1_000_000L) - nowSec
            else -> return null
        }

        if (deltaSec !in MIN_FUTURE_RESET_SEC..WEEKLY_MAX_WINDOW_SEC) return null
        val isFiveHour = deltaSec <= FIVE_HOUR_MAX_WINDOW_SEC
        val kind = if (isFiveHour) MetricKind.FIVE_HOUR else MetricKind.WEEKLY
        val token = if (isFiveHour) {
            formatDurationSeconds(deltaSec)
        } else {
            formatWeeklyDurationSeconds(deltaSec)
        }
        if (token == "--") return null

        return kind to token
    }

    fun formatFractionAsPercent(value: Double): String {
        if (!value.isFinite()) return "--"
        val pct = when {
            value in 0.0..1.0 -> (value * 100.0).roundToInt()
            value > 1.0 && value <= 100.0 -> value.roundToInt()
            else -> return "--"
        }
        return sanitizeQuotaMetric("$pct%")
    }

    // =========================================================================
    // 1. Native Binary ProtoBuf & gRPC Frame Wire Scanner
    // =========================================================================

    fun scanProtoBufBytes(
        rawBytes: ByteArray,
        length: Int = rawBytes.size,
        prev: QuotaHudView.Snapshot,
        source: String,
        rpcMethodHint: String? = null
    ): QuotaHudView.Snapshot? {
        val safeLen = minOf(length, rawBytes.size, MAX_PROTO_SCAN_BYTES)
        if (safeLen < 6) return null

        // Check if payload is actually UTF-8 JSON / JSPB text (e.g. batchexecute or gRPC-Web JSON)
        val firstNonWs = findFirstNonWhitespace(rawBytes, safeLen)
        if (firstNonWs >= 0) {
            val b = rawBytes[firstNonWs].toInt() and 0xFF
            if (b == '{'.code || b == '['.code || b == ')'.code) {
                val utf8 = try {
                    String(rawBytes, firstNonWs, safeLen - firstNonWs, Charsets.UTF_8)
                } catch (_: Throwable) {
                    null
                }
                if (utf8 != null) {
                    val structured = parseInAppStructuredPayload(utf8, prev, source)
                    if (structured != null) return structured
                }
            }
        }

        val nowMs = System.currentTimeMillis()
        val windows = ArrayList<DecodedProtoWindow>(4)
        var hasGlobalQuotaMarker = rpcMethodHint?.let { hasQuotaContextHint(it) } == true

        // Unwrap gRPC length-prefixed frames: [compressed_flag: 1B][msg_len: 4B BE][payload...]
        var cursor = 0
        var parsedGrpcFrames = false
        while (cursor + 5 <= safeLen) {
            val flag = rawBytes[cursor].toInt() and 0xFF
            val frameLen = ((rawBytes[cursor + 1].toInt() and 0xFF) shl 24) or
                ((rawBytes[cursor + 2].toInt() and 0xFF) shl 16) or
                ((rawBytes[cursor + 3].toInt() and 0xFF) shl 8) or
                (rawBytes[cursor + 4].toInt() and 0xFF)

            if ((flag == 0 || flag == 1) && frameLen in 4..(safeLen - cursor - 5)) {
                parsedGrpcFrames = true
                val start = cursor + 5
                if (flag == 0) {
                    if (scanAsciiQuotaMarker(rawBytes, start, frameLen)) {
                        hasGlobalQuotaMarker = true
                    }
                    scanProtoMessageSlice(
                        buf = rawBytes,
                        offset = start,
                        end = start + frameLen,
                        depth = 0,
                        nowMs = nowMs,
                        inheritedHint = hasGlobalQuotaMarker,
                        outWindows = windows
                    )
                } else {
                    val decompressed = gunzipBounded(rawBytes, start, frameLen)
                    if (decompressed != null && decompressed.size >= 4) {
                        if (scanAsciiQuotaMarker(decompressed, 0, decompressed.size)) {
                            hasGlobalQuotaMarker = true
                        }
                        scanProtoMessageSlice(
                            buf = decompressed,
                            offset = 0,
                            end = decompressed.size,
                            depth = 0,
                            nowMs = nowMs,
                            inheritedHint = hasGlobalQuotaMarker,
                            outWindows = windows
                        )
                    }
                }
                cursor += 5 + frameLen
            } else {
                break
            }
        }

        // Also scan raw unframed ProtoBuf / GZIP buffer when not framed by gRPC 5-byte header
        if (!parsedGrpcFrames) {
            val payloadBuf: ByteArray
            val payloadLen: Int
            if (safeLen >= 10 &&
                (rawBytes[0].toInt() and 0xFF) == 0x1F &&
                (rawBytes[1].toInt() and 0xFF) == 0x8B
            ) {
                val gunzipped = gunzipBounded(rawBytes, 0, safeLen)
                if (gunzipped != null) {
                    payloadBuf = gunzipped
                    payloadLen = gunzipped.size
                } else {
                    payloadBuf = rawBytes
                    payloadLen = safeLen
                }
            } else {
                payloadBuf = rawBytes
                payloadLen = safeLen
            }

            if (scanAsciiQuotaMarker(payloadBuf, 0, payloadLen)) {
                hasGlobalQuotaMarker = true
            }
            scanProtoMessageSlice(
                buf = payloadBuf,
                offset = 0,
                end = payloadLen,
                depth = 0,
                nowMs = nowMs,
                inheritedHint = hasGlobalQuotaMarker,
                outWindows = windows
            )
        }

        if (windows.isEmpty()) return null

        val bestFive = windows
            .filter { it.kind == MetricKind.FIVE_HOUR }
            .maxByOrNull { it.confidence }
        val bestWeek = windows
            .filter { it.kind == MetricKind.WEEKLY }
            .maxByOrNull { it.confidence }

        // Require either BOTH 5h & Weekly windows co-existing in the same RPC message,
        // or a high-confidence window (paired Timestamp sub-message + quota context / cap).
        val pairedDualWindows = bestFive != null && bestWeek != null
        val strongSingleWindow = (bestFive?.confidence ?: 0) >= 3 || (bestWeek?.confidence ?: 0) >= 3
        if (!pairedDualWindows && !strongSingleWindow) return null

        val base = sanitizeSnapshot(prev)
        val next = QuotaHudView.Snapshot(
            fiveHourQuota = if (bestFive != null && bestFive.metric != "--") bestFive.metric else base.fiveHourQuota,
            fiveHourReset = if (bestFive != null && bestFive.resetToken != "--") bestFive.resetToken else base.fiveHourReset,
            weeklyLimit = if (bestWeek != null && bestWeek.metric != "--") bestWeek.metric else base.weeklyLimit,
            weeklyReset = if (bestWeek != null && bestWeek.resetToken != "--") bestWeek.resetToken else base.weeklyReset,
            source = source
        ).sanitized()

        return if (next != base && next.hasLiveData) next else null
    }

    private fun scanProtoMessageSlice(
        buf: ByteArray,
        offset: Int,
        end: Int,
        depth: Int,
        nowMs: Long,
        inheritedHint: Boolean,
        outWindows: MutableList<DecodedProtoWindow>
    ): Boolean {
        if (depth > 5 || end - offset < 2 || outWindows.size >= 12) return false

        val scalars = ArrayList<ScalarEntry>(8)
        var nestedTimestamp: Pair<MetricKind, String>? = null
        var localHint = inheritedHint
        var explicitKindOverride: MetricKind? = null

        var pos = offset
        var fieldsDecoded = 0

        while (pos < end && fieldsDecoded < 96) {
            val tagPair = readVarint64(buf, pos, end) ?: return false
            val tag = tagPair.first
            pos = tagPair.second
            if (tag <= 0L) return false

            val wireType = (tag and 0x07L).toInt()
            val fieldNumLong = tag ushr 3
            if (fieldNumLong <= 0L || fieldNumLong > 50_000L) return false
            val fieldNum = fieldNumLong.toInt()
            fieldsDecoded++

            when (wireType) {
                0 -> { // Varint
                    val vPair = readVarint64(buf, pos, end) ?: return false
                    pos = vPair.second
                    if (scalars.size < 16) {
                        scalars.add(ScalarEntry(fieldNum, 0, vPair.first, null))
                    }
                }
                1 -> { // 64-bit (fixed64 / double)
                    if (pos + 8 > end) return false
                    val bits = readLittleEndian64(buf, pos)
                    pos += 8
                    val dbl = Double.fromBits(bits)
                    if (scalars.size < 16) {
                        scalars.add(ScalarEntry(fieldNum, 1, bits, dbl))
                    }
                }
                2 -> { // Length-delimited (nested message, string, packed repeated)
                    val lenPair = readVarint64(buf, pos, end) ?: return false
                    val sliceLenLong = lenPair.first
                    pos = lenPair.second
                    if (sliceLenLong < 0L || sliceLenLong > (end - pos).toLong()) return false
                    val sliceLen = sliceLenLong.toInt()
                    val sliceEnd = pos + sliceLen

                    if (sliceLen in 2..16384) {
                        // 1. Check if sub-slice is a google.protobuf.Timestamp {1: seconds, 2: nanos}
                        val ts = tryDecodeProtobufTimestamp(buf, pos, sliceEnd, nowMs)
                        if (ts != null) {
                            nestedTimestamp = ts
                        } else if (isPrintableUtf8Ascii(buf, pos, sliceEnd)) {
                            val str = try {
                                String(buf, pos, sliceLen, Charsets.UTF_8)
                            } catch (_: Throwable) {
                                ""
                            }
                            if (hasQuotaContextHint(str)) {
                                localHint = true
                            }
                            when {
                                str.contains("week", ignoreCase = true) || str.contains("7d", ignoreCase = true) || str.contains("每周") ->
                                    explicitKindOverride = MetricKind.WEEKLY
                                str.contains("5h", ignoreCase = true) || str.contains("5-hour", ignoreCase = true) ||
                                    str.contains("short", ignoreCase = true) || str.contains("session", ignoreCase = true) ->
                                    explicitKindOverride = MetricKind.FIVE_HOUR
                            }
                        } else if (sliceLen <= 8192) {
                            // 2. Try nested ProtoBuf sub-message recursively
                            scanProtoMessageSlice(
                                buf = buf,
                                offset = pos,
                                end = sliceEnd,
                                depth = depth + 1,
                                nowMs = nowMs,
                                inheritedHint = localHint,
                                outWindows = outWindows
                            )
                        }
                    }
                    pos = sliceEnd
                }
                5 -> { // 32-bit (fixed32 / float)
                    if (pos + 4 > end) return false
                    val bits = readLittleEndian32(buf, pos)
                    pos += 4
                    val flt = Float.fromBits(bits).toDouble()
                    if (scalars.size < 16) {
                        scalars.add(ScalarEntry(fieldNum, 5, bits.toLong(), flt))
                    }
                }
                else -> {
                    // Deprecated groups (3, 4) or invalid wire types (6, 7) -> not a valid message slice
                    return false
                }
            }
        }

        if (pos != end) return false

        // Evaluate whether this sub-message represents a Quota Window (reset timestamp + usage/limit)
        if (depth >= 1 && scalars.size <= 12) {
            var detectedTs = nestedTimestamp
            var tsFieldNum = -1
            if (detectedTs == null) {
                for (sc in scalars) {
                    val v = sc.intVal ?: continue
                    val classified = classifyFutureEpochTimestamp(v, nowMs)
                    if (classified != null) {
                        detectedTs = classified
                        tsFieldNum = sc.fieldNumber
                        break
                    }
                }
            }

            if (detectedTs != null) {
                val windowKind = explicitKindOverride ?: detectedTs.first
                val resetTok = detectedTs.second
                val metricPair = extractMetricFromScalars(scalars, tsFieldNum)
                if (metricPair != null) {
                    var conf = metricPair.second
                    if (nestedTimestamp != null) conf += 1
                    if (localHint) conf += 2
                    outWindows.add(
                        DecodedProtoWindow(
                            kind = windowKind,
                            metric = metricPair.first,
                            resetToken = resetTok,
                            confidence = conf
                        )
                    )
                }
            }
        }

        return true
    }

    /**
     * Recognizes `google.protobuf.Timestamp` wire layout:
     * field 1 (varint) = future Unix epoch seconds, optional field 2 (varint) = nanos (0..999_999_999).
     */
    private fun tryDecodeProtobufTimestamp(
        buf: ByteArray,
        offset: Int,
        end: Int,
        nowMs: Long
    ): Pair<MetricKind, String>? {
        val len = end - offset
        if (len !in 5..18) return null
        var pos = offset
        var secVal: Long? = null
        var nanosOk = true
        var count = 0

        while (pos < end) {
            val tagPair = readVarint64(buf, pos, end) ?: return null
            val tag = tagPair.first
            pos = tagPair.second
            if ((tag and 0x07L) != 0L) return null
            val fieldNum = (tag ushr 3).toInt()
            val valPair = readVarint64(buf, pos, end) ?: return null
            pos = valPair.second
            count++
            when (fieldNum) {
                1 -> secVal = valPair.first
                2 -> if (valPair.first !in 0L..999_999_999L) nanosOk = false
                else -> return null
            }
        }

        if (!nanosOk || count !in 1..2 || secVal == null) return null
        return classifyFutureEpochTimestamp(secVal, nowMs)
    }

    private fun extractMetricFromScalars(
        scalars: List<ScalarEntry>,
        excludeFieldNum: Int
    ): Pair<String, Int>? {
        // 1. Float / Double fraction in [0.0, 1.0] (wireType 1 or 5)
        for (sc in scalars) {
            if (sc.fieldNumber == excludeFieldNum) continue
            val d = sc.doubleVal ?: continue
            if ((sc.wireType == 1 || sc.wireType == 5) && d in 0.001..1.0) {
                val pct = formatFractionAsPercent(d)
                if (pct != "--") return pct to 2
            }
        }

        // 2. Integer (used, limit) pair where 0 <= used <= limit and limit is a plausible quota cap
        val nonTsInts = scalars.mapNotNull { sc ->
            if (sc.fieldNumber == excludeFieldNum || sc.wireType != 0) null
            else sc.intVal?.takeIf { it in 0L..10_000L }?.toInt()
        }

        if (nonTsInts.size >= 2) {
            for (i in nonTsInts.indices) {
                for (j in nonTsInts.indices) {
                    if (i == j) continue
                    val a = nonTsInts[i]
                    val b = nonTsInts[j]
                    if (a in 0..b && b in plausibleLimitCaps && b >= 5) {
                        val pctInt = ((a.toDouble() / b.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                        val pctStr = sanitizeQuotaMetric("$pctInt%")
                        if (pctStr != "--") return pctStr to 3
                    }
                }
            }
        }

        // 3. Float / Double explicit percentage in (1.0, 100.0]
        for (sc in scalars) {
            if (sc.fieldNumber == excludeFieldNum) continue
            val d = sc.doubleVal ?: continue
            if ((sc.wireType == 1 || sc.wireType == 5) && d > 1.0 && d <= 100.0) {
                val pct = formatFractionAsPercent(d)
                if (pct != "--") return pct to 1
            }
        }

        // 4. Direct integer percentage 0..100
        val singlePct = nonTsInts.firstOrNull { it in 0..100 }
        if (singlePct != null) {
            val pctStr = sanitizeQuotaMetric("$singlePct%")
            if (pctStr != "--") return pctStr to 1
        }

        return null
    }

    private fun readVarint64(buf: ByteArray, start: Int, end: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < end && shift < 64) {
            val b = buf[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) {
                return result to pos
            }
            shift += 7
        }
        return null
    }

    private fun readLittleEndian32(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readLittleEndian64(buf: ByteArray, offset: Int): Long {
        val lo = readLittleEndian32(buf, offset).toLong() and 0xFFFFFFFFL
        val hi = readLittleEndian32(buf, offset + 4).toLong() and 0xFFFFFFFFL
        return lo or (hi shl 32)
    }

    private fun isPrintableUtf8Ascii(buf: ByteArray, start: Int, end: Int): Boolean {
        val len = end - start
        if (len !in 2..96) return false
        var printable = 0
        for (i in start until end) {
            val c = buf[i].toInt() and 0xFF
            if (c in 0x20..0x7E || c >= 0xC2) {
                printable++
            } else if (c != 0x09 && c != 0x0A && c != 0x0D) {
                return false
            }
        }
        return printable >= (len * 4) / 5
    }

    private fun scanAsciiQuotaMarker(buf: ByteArray, offset: Int, length: Int): Boolean {
        val end = minOf(buf.size, offset + minOf(length, 16_384))
        var i = offset
        while (i + 4 < end) {
            val c = (buf[i].toInt() and 0xFF) or 0x20
            if (c == 'q'.code || c == 'r'.code || c == 'w'.code || c == 'u'.code || c == 'l'.code) {
                if (matchAsciiIgnoreCase(buf, i, end, "quota") ||
                    matchAsciiIgnoreCase(buf, i, end, "remaining") ||
                    matchAsciiIgnoreCase(buf, i, end, "weekly") ||
                    matchAsciiIgnoreCase(buf, i, end, "usage_limit") ||
                    matchAsciiIgnoreCase(buf, i, end, "ratelimit") ||
                    matchAsciiIgnoreCase(buf, i, end, "short_term")
                ) {
                    return true
                }
            }
            i++
        }
        return false
    }

    private fun matchAsciiIgnoreCase(buf: ByteArray, pos: Int, end: Int, needle: String): Boolean {
        if (pos + needle.length > end) return false
        for (k in needle.indices) {
            val a = (buf[pos + k].toInt() and 0xFF)
            val lower = if (a in 'A'.code..'Z'.code) a + 32 else a
            if (lower != needle[k].code) return false
        }
        return true
    }

    private fun hasQuotaContextHint(s: String): Boolean {
        if (s.isEmpty()) return false
        return s.contains("quota", ignoreCase = true) ||
            s.contains("limit", ignoreCase = true) ||
            s.contains("usage", ignoreCase = true) ||
            s.contains("remaining", ignoreCase = true) ||
            s.contains("allowance", ignoreCase = true) ||
            s.contains("weekly", ignoreCase = true) ||
            s.contains("short_term", ignoreCase = true) ||
            s.contains("ratelimit", ignoreCase = true)
    }

    private fun findFirstNonWhitespace(buf: ByteArray, len: Int): Int {
        val limit = minOf(len, 64)
        for (i in 0 until limit) {
            val c = buf[i].toInt() and 0xFF
            if (c != 0x20 && c != 0x09 && c != 0x0A && c != 0x0D) return i
        }
        return -1
    }

    private fun gunzipBounded(src: ByteArray, offset: Int, length: Int): ByteArray? {
        return try {
            GZIPInputStream(ByteArrayInputStream(src, offset, length)).use { gis ->
                val out = ByteArrayOutputStream(minOf(length * 2, 16_384))
                val tmp = ByteArray(2048)
                var total = 0
                while (total < MAX_GUNZIP_BYTES) {
                    val n = gis.read(tmp, 0, minOf(tmp.size, MAX_GUNZIP_BYTES - total))
                    if (n <= 0) break
                    out.write(tmp, 0, n)
                    total += n
                }
                out.toByteArray()
            }
        } catch (_: Throwable) {
            null
        }
    }

    // =========================================================================
    // 2. In-App Structured JSON / JSPB & Rate-Limit Banner Parsers
    // =========================================================================

    fun parseInAppStructuredPayload(
        payload: String,
        prev: QuotaHudView.Snapshot,
        source: String
    ): QuotaHudView.Snapshot? {
        val cleaned = payload
            .removePrefix(")]}'\n")
            .removePrefix(")]}'")
            .trim()
        if (cleaned.length < 4) return null

        // Handle Google batchexecute multi-chunk envelopes:
        // <len>\n[["wrb.fr","<rpcId>","<embedded_json>",...]]
        var best = sanitizeSnapshot(prev)
        var changed = false

        val lines = cleaned.lineSequence().iterator()
        var processedLineChunks = false
        while (lines.hasNext()) {
            val line = lines.next().trim()
            if (line.length < 4) continue
            if (line[0] == '[' || line[0] == '{') {
                processedLineChunks = true
                val parsed = parseSingleJsonNode(line, best, source)
                if (parsed != null && parsed.hasLiveData) {
                    best = parsed
                    changed = true
                }
            }
        }
        if (changed) return best

        if (!processedLineChunks || cleaned.firstOrNull { it == '{' || it == '[' } != null) {
            val firstBracket = cleaned.indexOfFirst { it == '{' || it == '[' }
            if (firstBracket !in 0..64) return null
            val candidate = cleaned.substring(firstBracket)
            val parsed = parseSingleJsonNode(candidate, best, source)
            if (parsed != null && parsed.hasLiveData) return parsed
        }
        return null
    }

    private fun parseSingleJsonNode(
        jsonStr: String,
        prev: QuotaHudView.Snapshot,
        source: String
    ): QuotaHudView.Snapshot? {
        return try {
            when (val node = JSONTokener(jsonStr).nextValue()) {
                is JSONObject -> inspectJsonObject(node, prev, source)
                is JSONArray -> inspectJsonArray(node, prev, source)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
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
        if (extracted.isNotEmpty()) {
            val merged = mergeExtractedFields(extracted, prev, source)
            if (merged != null) return merged
        }
        // Also check JSPB numeric tuple arrays: e.g. [[15, 100, 1762300000], [29, 100, 1762800000]]
        return inspectJspbWindowTuples(arr, prev, source)
    }

    private fun inspectJspbWindowTuples(
        root: JSONArray,
        prev: QuotaHudView.Snapshot,
        source: String
    ): QuotaHudView.Snapshot? {
        val nowMs = System.currentTimeMillis()
        val found = ArrayList<DecodedProtoWindow>(4)
        scanJspbArrayRecursive(root, 0, nowMs, found)
        if (found.size < 2 && found.none { it.confidence >= 3 }) return null

        val bestFive = found.filter { it.kind == MetricKind.FIVE_HOUR }.maxByOrNull { it.confidence }
        val bestWeek = found.filter { it.kind == MetricKind.WEEKLY }.maxByOrNull { it.confidence }
        val base = sanitizeSnapshot(prev)
        val next = QuotaHudView.Snapshot(
            fiveHourQuota = bestFive?.metric ?: base.fiveHourQuota,
            fiveHourReset = bestFive?.resetToken ?: base.fiveHourReset,
            weeklyLimit = bestWeek?.metric ?: base.weeklyLimit,
            weeklyReset = bestWeek?.resetToken ?: base.weeklyReset,
            source = source
        ).sanitized()
        return if (next != base && next.hasLiveData) next else null
    }

    private fun scanJspbArrayRecursive(
        arr: JSONArray,
        depth: Int,
        nowMs: Long,
        out: MutableList<DecodedProtoWindow>
    ) {
        if (depth > 5 || out.size >= 8) return
        val len = minOf(arr.length(), 24)
        var detectedTs: Pair<MetricKind, String>? = null
        val nums = ArrayList<Double>(6)

        for (i in 0 until len) {
            val elem = arr.opt(i) ?: continue
            when (elem) {
                is JSONArray -> scanJspbArrayRecursive(elem, depth + 1, nowMs, out)
                is String -> {
                    val s = elem.trim()
                    if (depth < 4 && s.length >= 4 && (s[0] == '[' || s[0] == '{')) {
                        try {
                            when (val inner = JSONTokener(s).nextValue()) {
                                is JSONArray -> scanJspbArrayRecursive(inner, depth + 1, nowMs, out)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
                is Number -> {
                    val lv = elem.toLong()
                    val ts = classifyFutureEpochTimestamp(lv, nowMs)
                    if (ts != null && detectedTs == null) {
                        detectedTs = ts
                    } else if (nums.size < 8) {
                        nums.add(elem.toDouble())
                    }
                }
            }
        }

        if (detectedTs != null && nums.isNotEmpty() && len <= 10) {
            var metric = "--"
            var conf = 1
            // Check (used, limit) pair first
            val ints = nums.filter { it == it.toLong().toDouble() && it in 0.0..10_000.0 }.map { it.toInt() }
            if (ints.size >= 2) {
                for (i in ints.indices) {
                    for (j in ints.indices) {
                        if (i != j && ints[i] in 0..ints[j] && ints[j] in plausibleLimitCaps) {
                            val pct = ((ints[i].toDouble() / ints[j].toDouble()) * 100.0).roundToInt()
                            metric = sanitizeQuotaMetric("$pct%")
                            conf = 3
                            break
                        }
                    }
                    if (metric != "--") break
                }
            }
            if (metric == "--") {
                val frac = nums.firstOrNull { it in 0.001..1.0 && it != it.toLong().toDouble() }
                if (frac != null) {
                    metric = formatFractionAsPercent(frac)
                    conf = 2
                } else if (ints.size == 1 && ints[0] in 0..100) {
                    metric = sanitizeQuotaMetric("${ints[0]}%")
                    conf = 2
                }
            }
            if (metric != "--") {
                out.add(DecodedProtoWindow(detectedTs.first, metric, detectedTs.second, conf))
            }
        }
    }

    /**
     * Parses either raw JSON, JSPB array, batchexecute response, or full Gemini HTML containing
     * balanced `AF_initDataCallback({ ... data: [...], sideChannel: {} })` blocks & `window.WIZ_global_data`.
     */
    fun parseHtmlOrJsonPayload(
        payload: String,
        prev: QuotaHudView.Snapshot,
        source: String
    ): QuotaHudView.Snapshot? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null

        // 1. Direct JSON / JSPB / batchexecute payload
        if (trimmed[0] == '{' || trimmed[0] == '[' || trimmed.startsWith(")]}'")) {
            val direct = parseInAppStructuredPayload(trimmed, prev, source)
            if (direct != null && direct.hasLiveData) return direct
        }

        // 2. Balanced extraction of AF_initDataCallback({ ..., data: [...], sideChannel: {} })
        var bestSnap = sanitizeSnapshot(prev)
        var foundAny = false

        for (dataBlock in extractCallbackDataBlocks(trimmed)) {
            val parsed = parseInAppStructuredPayload(dataBlock, bestSnap, "$source-AF")
            if (parsed != null && parsed.hasLiveData) {
                bestSnap = parsed
                foundAny = true
            }
        }

        // 3. Extract window.WIZ_global_data = { ... };
        val wizIdx = trimmed.indexOf("window.WIZ_global_data")
        if (wizIdx >= 0) {
            val braceStart = trimmed.indexOf('{', wizIdx)
            if (braceStart >= 0) {
                val wizJsonStr = extractBalancedJsonSlice(trimmed, braceStart)
                if (wizJsonStr != null) {
                    val parsed = parseInAppStructuredPayload(wizJsonStr, bestSnap, "$source-WIZ")
                    if (parsed != null && parsed.hasLiveData) {
                        bestSnap = parsed
                        foundAny = true
                    }
                }
            }
        }

        // 4. Regex scan for explicit quota JSON properties in minified script text
        if (!foundAny) {
            val keyValMatches = Regex(""""([a-zA-Z0-9_]{3,36})"\s*:\s*([0-9.]+|"[^"]+")""").findAll(trimmed)
            val extracted = mutableMapOf<String, String>()
            for (m in keyValMatches) {
                val k = m.groupValues[1].lowercase()
                val v = m.groupValues[2].trim('"')
                if (structuredQuotaKeyRegex.matches(k)) {
                    extracted[k] = v
                }
            }
            if (extracted.isNotEmpty()) {
                val merged = mergeExtractedFields(extracted, bestSnap, "$source-Regex")
                if (merged != null && merged.hasLiveData) return merged
            }
        }

        return if (foundAny && bestSnap != sanitizeSnapshot(prev) && bestSnap.hasLiveData) bestSnap else null
    }

    /**
     * Extracts Google `AF_initDataCallback({ ... data: [...] })` JSON payloads using
     * quote-aware bracket matching so trailing fields like `, sideChannel: {}` never break parsing.
     */
    private fun extractCallbackDataBlocks(html: String): List<String> {
        val results = ArrayList<String>(8)
        var cursor = 0
        val marker = "AF_initDataCallback"
        while (results.size < 24) {
            val hit = html.indexOf(marker, cursor)
            if (hit < 0) break
            val searchLimit = minOf(html.length, hit + 240)
            val dataKeyIdx = html.indexOf("data:", hit)
            if (dataKeyIdx in hit..searchLimit) {
                var valIdx = dataKeyIdx + 5
                while (valIdx < html.length && html[valIdx].isWhitespace()) valIdx++
                if (valIdx < html.length && (html[valIdx] == '[' || html[valIdx] == '{')) {
                    val block = extractBalancedJsonSlice(html, valIdx)
                    if (block != null && block.length >= 4) {
                        results.add(block)
                        cursor = valIdx + block.length
                        continue
                    }
                }
            }
            cursor = hit + marker.length
        }
        return results
    }

    private fun extractBalancedJsonSlice(src: String, startIdx: Int, maxChars: Int = 262_144): String? {
        if (startIdx !in src.indices) return null
        val openCh = src[startIdx]
        if (openCh != '[' && openCh != '{') return null

        var depth = 0
        var inString = false
        var escapeNext = false
        val limit = minOf(src.length, startIdx + maxChars)

        for (i in startIdx until limit) {
            val ch = src[i]
            if (escapeNext) {
                escapeNext = false
                continue
            }
            if (inString) {
                when (ch) {
                    '\\' -> escapeNext = true
                    '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    if (depth == 0) {
                        return src.substring(startIdx, i + 1)
                    }
                }
            }
        }
        return null
    }

    /**
     * Checks screen views / Accessibility / Compose nodes / WebView DOM text for quota metrics.
     * Uses a multi-line sliding context window so separate label & value rows ("5小时限额" followed
     * on the next line by "15%" and "1小时52分后重置") associate accurately to 5h vs Weekly pools.
     */
    fun parseScreenQuotaTexts(
        text: String,
        prev: QuotaHudView.Snapshot,
        source: String
    ): QuotaHudView.Snapshot? {
        val base = sanitizeSnapshot(prev)
        var fiveHour = base.fiveHourQuota
        var fiveReset = base.fiveHourReset
        var weekly = base.weeklyLimit
        var weekReset = base.weeklyReset
        var hit = false

        var activeSection: MetricKind? = null
        var sectionAge = 0

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.length !in 2..250) continue

            // 1. Check for rate-limit exhaustion banners
            if (exhaustedBannerRegex.matches(line)) {
                if (fiveHour == "--" || fiveHour != "100%") {
                    fiveHour = "100%"
                    hit = true
                }
                val bannerReset = extractMetricToken(line, MetricKind.RESET)
                if (bannerReset != null) {
                    fiveReset = bannerReset
                    hit = true
                }
                activeSection = MetricKind.FIVE_HOUR
                sectionAge = 0
                continue
            }

            val lower = line.lowercase()
            val explicit5h = lower.contains("5h") ||
                lower.contains("5-hour") ||
                lower.contains("5 hour") ||
                lower.contains("5小时") ||
                lower.contains("五小时") ||
                lower.contains("short-term") ||
                lower.contains("short term") ||
                lower.contains("短期")
            val explicitWeek = lower.contains("weekly") ||
                lower.contains("week") ||
                lower.contains("每周") ||
                lower.contains("本周") ||
                lower.contains("7d") ||
                lower.contains("7-day") ||
                lower.contains("7天")

            if (explicit5h && !explicitWeek) {
                activeSection = MetricKind.FIVE_HOUR
                sectionAge = 0
            } else if (explicitWeek && !explicit5h) {
                activeSection = MetricKind.WEEKLY
                sectionAge = 0
            } else if (lower.contains("quota") || lower.contains("usage") || lower.contains("limit") ||
                lower.contains("限额") || lower.contains("用量") || lower.contains("使用") || lower.contains("额度")
            ) {
                if (activeSection == null) {
                    activeSection = MetricKind.FIVE_HOUR
                    sectionAge = 0
                }
            } else {
                sectionAge++
                if (sectionAge > 4) {
                    activeSection = null
                }
            }

            val targetKind = when {
                explicit5h && !explicitWeek -> MetricKind.FIVE_HOUR
                explicitWeek && !explicit5h -> MetricKind.WEEKLY
                else -> activeSection
            } ?: continue

            // Skip generic OS clock strings like "09:41" unless reset context is on the line
            val hasResetContext = lower.contains("reset") ||
                lower.contains("refresh") ||
                lower.contains("renew") ||
                lower.contains("available") ||
                lower.contains("until") ||
                lower.contains("重置") ||
                lower.contains("恢复") ||
                lower.contains("刷新") ||
                lower.contains("后") ||
                lower.contains("明天") ||
                lower.contains("周") ||
                Regex("""\d+\s*[d天h小时时m分]""", RegexOption.IGNORE_CASE).containsMatchIn(lower)

            val metricTok = extractUsedOrConvertedRemainingMetric(line, targetKind)
            if (metricTok != null && metricTok != "--") {
                if (targetKind == MetricKind.FIVE_HOUR) {
                    fiveHour = metricTok
                } else {
                    weekly = metricTok
                }
                hit = true
                sectionAge = 0
            }

            if (hasResetContext) {
                val resetTok = extractMetricToken(line, MetricKind.RESET)
                if (resetTok != null && resetTok != "--") {
                    if (targetKind == MetricKind.FIVE_HOUR) {
                        fiveReset = resetTok
                    } else {
                        weekReset = resetTok
                    }
                    hit = true
                    sectionAge = 0
                }
            }
        }

        if (!hit) return null
        val next = base.copy(
            fiveHourQuota = fiveHour,
            fiveHourReset = fiveReset,
            weeklyLimit = weekly,
            weeklyReset = weekReset,
            source = source
        ).sanitized()
        return if (next != base && next.hasLiveData) next else null
    }

    private fun extractUsedOrConvertedRemainingMetric(line: String, kind: MetricKind): String? {
        val rawTok = extractMetricToken(line, kind) ?: return null
        val lower = line.lowercase()
        val isRemainingOnly = (lower.contains("remaining") ||
            lower.contains("available") ||
            lower.contains("left") ||
            lower.contains("剩余") ||
            lower.contains("可用") ||
            lower.contains("还剩")) &&
            !lower.contains("used") &&
            !lower.contains("已用") &&
            !lower.contains("已使用")

        if (isRemainingOnly) {
            val pctM = pctNumRegex.matchEntire(rawTok)
            if (pctM != null) {
                val remPct = pctM.groupValues.getOrNull(1)?.toFloatOrNull()
                if (remPct != null && remPct in 0f..100f) {
                    val used = (100f - remPct).roundToInt().coerceIn(0, 100)
                    return sanitizeQuotaMetric("$used%")
                }
            }
            val ratioM = ratioNumRegex.matchEntire(rawTok)
            if (ratioM != null) {
                val rem = ratioM.groupValues.getOrNull(1)?.toIntOrNull()
                val lim = ratioM.groupValues.getOrNull(2)?.toIntOrNull()
                if (rem != null && lim != null && lim > 0 && rem in 0..lim) {
                    val usedPct = (((lim - rem).toDouble() / lim.toDouble()) * 100.0).roundToInt()
                    return sanitizeQuotaMetric("$usedPct%")
                }
            }
        }
        return rawTok
    }

    /**
     * Backward-compatible alias for banner-only scanning. Accepts rawText or block.
     */
    fun parseExhaustedBannerOnly(
        rawText: String,
        prev: QuotaHudView.Snapshot,
        source: String
    ): QuotaHudView.Snapshot? = parseScreenQuotaTexts(rawText, prev, source)

    fun normalizeFieldCandidate(raw: String, kind: MetricKind): String {
        val singleLine = raw.replace(Regex("\\s+"), " ").trim()
        if (singleLine.isEmpty()) return "--"

        // Support raw numeric percentages / fractions / epoch resets in JSON & ViewModel fields
        val asLong = singleLine.toLongOrNull()
        if (asLong != null && kind == MetricKind.RESET) {
            val ts = classifyFutureEpochTimestamp(asLong)
            if (ts != null) return ts.second
            if (asLong in 60L..WEEKLY_MAX_WINDOW_SEC) {
                return formatDurationSeconds(asLong)
            }
        }

        val asDouble = singleLine.toDoubleOrNull()
        if (asDouble != null && (kind == MetricKind.FIVE_HOUR || kind == MetricKind.WEEKLY)) {
            if (asDouble in 0.0..1.0 && singleLine.contains('.')) {
                return formatFractionAsPercent(asDouble)
            }
            if (asDouble in 0.0..100.0) {
                return sanitizeQuotaMetric("${asDouble.roundToInt()}%")
            }
        }

        return extractMetricToken(singleLine, kind) ?: "--"
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

    private fun collectFromJson(node: Any?, out: MutableMap<String, String>, depth: Int) {
        if (depth > 6 || node == null) return
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
                    } else if (value is String && depth < 5) {
                        val trimmed = value.trim()
                        if (trimmed.length >= 4 && (trimmed[0] == '{' || trimmed[0] == '[')) {
                            try {
                                collectFromJson(JSONTokener(trimmed).nextValue(), out, depth + 1)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }
            is JSONArray -> {
                val limit = minOf(node.length(), 30)
                for (i in 0 until limit) {
                    val elem = node.opt(i)
                    if (elem is JSONObject || elem is JSONArray) {
                        collectFromJson(elem, out, depth + 1)
                    } else if (elem is String && depth < 5) {
                        val trimmed = elem.trim()
                        if (trimmed.length >= 4 && (trimmed[0] == '{' || trimmed[0] == '[')) {
                            try {
                                collectFromJson(JSONTokener(trimmed).nextValue(), out, depth + 1)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }
        }
    }

    fun mergeExtractedFields(
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
                k == "remaining_quota" || k == "quota_remaining" || k == "prompts_remaining" ->
                    remainingVal = v
                k == "quota_limit" || k == "max_quota" ->
                    totalLimitVal = v
                k.contains("week") && k.contains("reset") -> {
                    val clean = normalizeFieldCandidate(v, MetricKind.RESET)
                    if (clean != "--") weekReset = clean
                }
                k.contains("reset") -> {
                    val clean = normalizeFieldCandidate(v, MetricKind.RESET)
                    if (clean != "--") fiveReset = clean
                }
                k.contains("5h") || k.contains("five") || k.contains("short_term") || k.contains("session") -> {
                    val clean = normalizeFieldCandidate(v, MetricKind.FIVE_HOUR)
                    if (clean != "--") fiveHour = clean
                }
                k.contains("week") -> {
                    val clean = normalizeFieldCandidate(v, MetricKind.WEEKLY)
                    if (clean != "--") weekly = clean
                }
            }
        }

        if (remainingVal != null &&
            totalLimitVal != null &&
            remainingVal.all { it.isDigit() } &&
            totalLimitVal.all { it.isDigit() }
        ) {
            val rem = remainingVal.toIntOrNull()
            val lim = totalLimitVal.toIntOrNull()
            if (rem != null && lim != null && lim > 0 && rem in 0..lim) {
                val usedPct = (((lim - rem).toDouble() / lim.toDouble()) * 100.0).roundToInt()
                val pctClean = sanitizeQuotaMetric("$usedPct%")
                if (pctClean != "--") fiveHour = pctClean
            }
        } else if (remainingVal != null) {
            val clean = normalizeFieldCandidate(remainingVal, MetricKind.FIVE_HOUR)
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
