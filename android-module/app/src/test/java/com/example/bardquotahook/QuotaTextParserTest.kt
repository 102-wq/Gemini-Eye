package com.example.bardquotahook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class QuotaTextParserTest {

    @Test
    fun sanitizeQuotaMetric_handlesPercentagesRatiosAndRejectsInvalidText() {
        assertEquals("15%", QuotaTextParser.sanitizeQuotaMetric("15%"))
        assertEquals("42%", QuotaTextParser.sanitizeQuotaMetric(" 42 % "))
        assertEquals("12.5%", QuotaTextParser.sanitizeQuotaMetric("12.5%"))
        assertEquals("0%", QuotaTextParser.sanitizeQuotaMetric("0%"))
        assertEquals("100%", QuotaTextParser.sanitizeQuotaMetric("100%"))
        assertEquals("15/100", QuotaTextParser.sanitizeQuotaMetric("15 / 100"))

        assertEquals("--", QuotaTextParser.sanitizeQuotaMetric(null))
        assertEquals("--", QuotaTextParser.sanitizeQuotaMetric(""))
        assertEquals("--", QuotaTextParser.sanitizeQuotaMetric("--"))
        assertEquals("--", QuotaTextParser.sanitizeQuotaMetric("101%"))
        assertEquals("--", QuotaTextParser.sanitizeQuotaMetric("-5%"))
        assertEquals("--", QuotaTextParser.sanitizeQuotaMetric("Gemini Advanced"))
    }

    @Test
    fun parseProgressFraction_computesFractionsForPercentagesAndRatios() {
        assertEquals(0.15f, QuotaTextParser.parseProgressFraction("15%"), 0.0001f)
        assertEquals(0.29f, QuotaTextParser.parseProgressFraction("29%"), 0.0001f)
        assertEquals(0.30f, QuotaTextParser.parseProgressFraction("30/100"), 0.0001f)
        assertEquals(0.225f, QuotaTextParser.parseProgressFraction("45 / 200"), 0.0001f)
        assertEquals(0f, QuotaTextParser.parseProgressFraction("--"), 0.0001f)
        assertEquals(0f, QuotaTextParser.parseProgressFraction("invalid"), 0.0001f)
    }

    @Test
    fun sanitizeResetToken_normalizesVaryingEnglishAndChineseTimeUnits() {
        // Hours + minutes variations
        assertEquals("1h52m", QuotaTextParser.sanitizeResetToken("1 hr 52 mins"))
        assertEquals("1h52m", QuotaTextParser.sanitizeResetToken("1 hours 52 minutes"))
        assertEquals("2h5m", QuotaTextParser.sanitizeResetToken("2小时5分钟"))
        assertEquals("1h52m", QuotaTextParser.sanitizeResetToken("1时52分"))

        // Days + hours variations
        assertEquals("4d12h", QuotaTextParser.sanitizeResetToken("4 days 12 hours"))
        assertEquals("4d12h", QuotaTextParser.sanitizeResetToken("4d 12 hrs"))
        assertEquals("4d12h", QuotaTextParser.sanitizeResetToken("4天12小时"))
        assertEquals("3d", QuotaTextParser.sanitizeResetToken("3 days"))
        assertEquals("12.5d", QuotaTextParser.sanitizeResetToken("12.5 days"))

        // Minutes only variations
        assertEquals("45m", QuotaTextParser.sanitizeResetToken("45 minutes"))
        assertEquals("18m", QuotaTextParser.sanitizeResetToken("18 min"))
        assertEquals("30m", QuotaTextParser.sanitizeResetToken("30分钟"))
        assertEquals("9m", QuotaTextParser.sanitizeResetToken("9分"))

        // Weekday & localized day-clock variations
        assertEquals("Mon 14:30", QuotaTextParser.sanitizeResetToken("Mon, 14:30"))
        assertEquals("周五 09:00", QuotaTextParser.sanitizeResetToken("星期五 09:00"))
        assertEquals("明天 08:15", QuotaTextParser.sanitizeResetToken("明日 08:15"))
        assertEquals("Tomorrow 10:30 AM", QuotaTextParser.sanitizeResetToken("Tomorrow 10:30 AM"))

        // Invalid tokens
        assertEquals("--", QuotaTextParser.sanitizeResetToken(null))
        assertEquals("--", QuotaTextParser.sanitizeResetToken("soon"))
        assertEquals("--", QuotaTextParser.sanitizeResetToken("--"))
    }

    @Test
    fun formatDurationSeconds_andWeeklyDurationSeconds_coverVaryingTimeScales() {
        assertEquals("--", QuotaTextParser.formatDurationSeconds(0L))
        assertEquals("1m", QuotaTextParser.formatDurationSeconds(45L))
        assertEquals("5m", QuotaTextParser.formatDurationSeconds(300L))
        assertEquals("1h", QuotaTextParser.formatDurationSeconds(3600L))
        assertEquals("1h52m", QuotaTextParser.formatDurationSeconds(6720L))
        assertEquals("1d1h", QuotaTextParser.formatDurationSeconds(90_000L))

        assertEquals("--", QuotaTextParser.formatWeeklyDurationSeconds(-10L))
        assertEquals("0d11h", QuotaTextParser.formatWeeklyDurationSeconds(39_600L))
        assertEquals("0d23h", QuotaTextParser.formatWeeklyDurationSeconds(86_000L))
        assertEquals("4d12h", QuotaTextParser.formatWeeklyDurationSeconds(4L * 86_400L + 12L * 3600L))
    }

    @Test
    fun formatWeeklyResetForDisplay_handlesVaryingUnitsForZhAndEn() {
        assertEquals("4天12h", QuotaTextParser.formatWeeklyResetForDisplay("4d12h", isZh = true))
        assertEquals("4day12h", QuotaTextParser.formatWeeklyResetForDisplay("4d12h", isZh = false))

        assertEquals("6天0h", QuotaTextParser.formatWeeklyResetForDisplay("6 days", isZh = true))
        assertEquals("6day0h", QuotaTextParser.formatWeeklyResetForDisplay("6d", isZh = false))

        assertEquals("0天12h", QuotaTextParser.formatWeeklyResetForDisplay("0.5d", isZh = true))
        assertEquals("0day12h", QuotaTextParser.formatWeeklyResetForDisplay("0.5d", isZh = false))

        assertEquals("0天23h", QuotaTextParser.formatWeeklyResetForDisplay("23h52m", isZh = true))
        assertEquals("1day4h", QuotaTextParser.formatWeeklyResetForDisplay("28h15m", isZh = false))
    }

    @Test
    fun classifyFutureEpochTimestamp_handlesSecondsMillisAndMicros() {
        val nowMs = 1_760_000_000_000L
        val nowSec = nowMs / 1000L

        val fiveHourSec = nowSec + 6720L // 1h52m ahead -> 5h window
        val weeklySec = nowSec + (4L * 86_400L + 12L * 3600L) // 4d12h ahead -> weekly window

        val fiveFromSec = QuotaTextParser.classifyFutureEpochTimestamp(fiveHourSec, nowMs)
        assertEquals(QuotaTextParser.MetricKind.FIVE_HOUR to "1h52m", fiveFromSec)

        val fiveFromMillis = QuotaTextParser.classifyFutureEpochTimestamp(fiveHourSec * 1000L, nowMs)
        assertEquals(QuotaTextParser.MetricKind.FIVE_HOUR to "1h52m", fiveFromMillis)

        val fiveFromMicros = QuotaTextParser.classifyFutureEpochTimestamp(fiveHourSec * 1_000_000L, nowMs)
        assertEquals(QuotaTextParser.MetricKind.FIVE_HOUR to "1h52m", fiveFromMicros)

        val weeklyFromSec = QuotaTextParser.classifyFutureEpochTimestamp(weeklySec, nowMs)
        assertEquals(QuotaTextParser.MetricKind.WEEKLY to "4d12h", weeklyFromSec)

        // Past or out-of-range (>7d1h) timestamps are rejected
        assertNull(QuotaTextParser.classifyFutureEpochTimestamp(nowSec - 60L, nowMs))
        assertNull(QuotaTextParser.classifyFutureEpochTimestamp(nowSec + 8L * 86_400L, nowMs))
    }

    @Test
    fun parseInAppStructuredPayload_extractsFiveHourQuotaAndWeeklyLimitAcrossFormats() {
        val jsonPayload = """
            {
              "fiveHourQuota": "15%",
              "fiveHourReset": "1 hr 52 mins",
              "weeklyLimit": "29%",
              "weeklyReset": "4 days 12 hours"
            }
        """.trimIndent()

        val snap1 = QuotaTextParser.parseInAppStructuredPayload(
            payload = jsonPayload,
            prev = QuotaHudView.Snapshot(),
            source = "Cronet-JSON"
        )
        assertNotNull(snap1)
        assertEquals("15%", snap1?.fiveHourQuota)
        assertEquals("1h52m", snap1?.fiveHourReset)
        assertEquals("29%", snap1?.weeklyLimit)
        assertEquals("4d12h", snap1?.weeklyReset)

        // Ratio + localized time units
        val ratioPayload = """
            {
              "data": {
                "five_hour_ratio": 0.42,
                "five_hour_reset_time": "50 minutes",
                "weekly_percent": 64,
                "weekly_reset_time": "2天6小时"
              }
            }
        """.trimIndent()

        val snap2 = QuotaTextParser.parseInAppStructuredPayload(
            payload = ratioPayload,
            prev = QuotaHudView.Snapshot(),
            source = "gRPC-JSON"
        )
        assertNotNull(snap2)
        assertEquals("42%", snap2?.fiveHourQuota)
        assertEquals("50m", snap2?.fiveHourReset)
        assertEquals("64%", snap2?.weeklyLimit)
        assertEquals("2d6h", snap2?.weeklyReset)

        // Google batchexecute envelope with embedded JSON string + remaining_quota / max_prompts
        val batchPayload = ")]}'\n" +
            """[["wrb.fr","hOoQ4d","{\"remaining_quota\":12,\"max_prompts\":40,\"reset_time\":\"1小时15分钟\",\"weekly_quota\":\"80%\",\"weekly_reset\":\"3d8h\"}",null,null]]"""

        val snap3 = QuotaTextParser.parseInAppStructuredPayload(
            payload = batchPayload,
            prev = QuotaHudView.Snapshot(),
            source = "batchexecute"
        )
        assertNotNull(snap3)
        assertEquals("70%", snap3?.fiveHourQuota)
        assertEquals("1h15m", snap3?.fiveHourReset)
        assertEquals("80%", snap3?.weeklyLimit)
        assertEquals("3d8h", snap3?.weeklyReset)
    }

    @Test
    fun parseScreenQuotaTexts_handlesEnglishChineseAndVaryingResetUnits() {
        val enScreenText = """
            09:41
            5-hour usage limit
            15% used
            Resets in 1 hr 52 mins
            Weekly limit
            29% used
            Resets in 4 days 12 hours
        """.trimIndent()

        val enSnap = QuotaTextParser.parseScreenQuotaTexts(
            text = enScreenText,
            prev = QuotaHudView.Snapshot(),
            source = "DOM-EN"
        )
        assertNotNull(enSnap)
        assertEquals("15%", enSnap?.fiveHourQuota)
        assertEquals("1h52m", enSnap?.fiveHourReset)
        assertEquals("29%", enSnap?.weeklyLimit)
        assertEquals("4d12h", enSnap?.weeklyReset)

        // Chinese screen text with "剩余" (remaining -> used% conversion) & localized time units
        val zhScreenText = """
            5小时限额
            剩余 75%
            35分钟后重置
            每周限额
            已使用 64%
            星期五 09:00 恢复
        """.trimIndent()

        val zhSnap = QuotaTextParser.parseScreenQuotaTexts(
            text = zhScreenText,
            prev = QuotaHudView.Snapshot(),
            source = "DOM-ZH"
        )
        assertNotNull(zhSnap)
        assertEquals("25%", zhSnap?.fiveHourQuota)
        assertEquals("35m", zhSnap?.fiveHourReset)
        assertEquals("64%", zhSnap?.weeklyLimit)
        assertEquals("周五 09:00", zhSnap?.weeklyReset)

        // Bare OS clock without reset context must NOT be treated as reset time
        val bareClockText = """
            5-hour quota
            20% used
            09:41
        """.trimIndent()
        val bareSnap = QuotaTextParser.parseScreenQuotaTexts(
            text = bareClockText,
            prev = QuotaHudView.Snapshot(),
            source = "DOM-Bare"
        )
        assertNotNull(bareSnap)
        assertEquals("20%", bareSnap?.fiveHourQuota)
        assertEquals("--", bareSnap?.fiveHourReset)
    }

    @Test
    fun parseExhaustedBannerOnly_matchesLimitBannersAndIgnoresNormalChat() {
        val normalChat = "Please help me understand how rate limit works when quota is 0% remaining."
        assertNull(QuotaTextParser.parseExhaustedBannerOnly(normalChat, QuotaHudView.Snapshot(), "UI"))

        val banner = "You've reached your usage limit. Try again in 2 hrs 10 mins."
        val snap = QuotaTextParser.parseExhaustedBannerOnly(banner, QuotaHudView.Snapshot(), "Banner")
        assertNotNull(snap)
        assertEquals("100%", snap?.fiveHourQuota)
        assertEquals("2h10m", snap?.fiveHourReset)
    }

    @Test
    fun normalizeFieldCandidate_handlesEpochSecondsAndDurationSeconds() {
        val futureEpoch = (System.currentTimeMillis() / 1000L) + 6750L
        val fiveReset = QuotaTextParser.normalizeFieldCandidate(
            futureEpoch.toString(),
            QuotaTextParser.MetricKind.RESET
        )
        assertTrue(fiveReset == "1h52m" || fiveReset == "1h51m")

        assertEquals(
            "3h20m",
            QuotaTextParser.normalizeFieldCandidate("12000", QuotaTextParser.MetricKind.RESET)
        )
        assertEquals(
            "45%",
            QuotaTextParser.normalizeFieldCandidate("0.45", QuotaTextParser.MetricKind.FIVE_HOUR)
        )
        assertEquals(
            "88%",
            QuotaTextParser.normalizeFieldCandidate("88", QuotaTextParser.MetricKind.WEEKLY)
        )
    }

    @Test
    fun parseHtmlOrJsonPayload_extractsAfInitDataCallbackAndFormatsSnapshotDisplay() {
        val html = """
            <html>
              <script>
                AF_initDataCallback({
                  key: 'ds:1',
                  hash: '1',
                  data: {
                    "fiveHourQuota": "18%",
                    "fiveHourReset": "1 hr 20 mins",
                    "weeklyLimit": "45%",
                    "weeklyReset": "3 days 6 hours"
                  },
                  sideChannel: {}
                });
              </script>
            </html>
        """.trimIndent()

        val snap = QuotaTextParser.parseHtmlOrJsonPayload(
            payload = html,
            prev = QuotaHudView.Snapshot(),
            source = "HTML-AF"
        )
        assertNotNull(snap)
        assertEquals("18%", snap?.fiveHourQuota)
        assertEquals("1h20m", snap?.fiveHourReset)
        assertEquals("45%", snap?.weeklyLimit)
        assertEquals("3d6h", snap?.weeklyReset)

        assertEquals("18% (1h20m)", snap?.formatFiveHourDisplay())
        assertEquals("45% (3天6h)", snap?.formatWeeklyDisplay(QuotaHudView.Lang.ZH))
        assertEquals("45% (3day6h)", snap?.formatWeeklyDisplay(QuotaHudView.Lang.EN))
    }

    @Test
    fun scanProtoBufBytes_decodesUncompressedAndGzipGrpcDualWindows() {
        val nowSec = System.currentTimeMillis() / 1000L
        val fiveEpochSec = nowSec + 6750L // ~1h52m
        val weeklyEpochSec = nowSec + (4L * 86_400L + 12L * 3600L + 1800L) // 4d12h

        val fiveWindowMsg = protoMessage(
            varintField(1, fiveEpochSec),
            varintField(2, 3L),
            varintField(3, 20L) // 3/20 = 15%
        )
        val weeklyWindowMsg = protoMessage(
            varintField(1, weeklyEpochSec),
            varintField(2, 29L),
            varintField(3, 100L) // 29/100 = 29%
        )
        val rootProto = protoMessage(
            lengthDelimitedField(1, fiveWindowMsg),
            lengthDelimitedField(2, weeklyWindowMsg)
        )

        // 1. Uncompressed gRPC frame (flag = 0x00)
        val rawGrpcFrame = wrapGrpcFrame(flag = 0, payload = rootProto)
        val decoded = QuotaTextParser.scanProtoBufBytes(
            rawBytes = rawGrpcFrame,
            length = rawGrpcFrame.size,
            prev = QuotaHudView.Snapshot(),
            source = "gRPC-PB"
        )
        assertNotNull(decoded)
        assertEquals("15%", decoded?.fiveHourQuota)
        assertTrue(decoded?.fiveHourReset == "1h52m" || decoded?.fiveHourReset == "1h51m")
        assertEquals("29%", decoded?.weeklyLimit)
        assertEquals("4d12h", decoded?.weeklyReset)

        // 2. GZIP-compressed gRPC frame (flag = 0x01)
        val gzipBytes = gzip(rootProto)
        val gzipGrpcFrame = wrapGrpcFrame(flag = 1, payload = gzipBytes)
        val decodedGzip = QuotaTextParser.scanProtoBufBytes(
            rawBytes = gzipGrpcFrame,
            length = gzipGrpcFrame.size,
            prev = QuotaHudView.Snapshot(),
            source = "gRPC-GZIP"
        )
        assertNotNull(decodedGzip)
        assertEquals("15%", decodedGzip?.fiveHourQuota)
        assertEquals("29%", decodedGzip?.weeklyLimit)
        assertEquals("4d12h", decodedGzip?.weeklyReset)
    }

    // =========================================================================
    // Test Helpers (Wire ProtoBuf + gRPC Framing)
    // =========================================================================

    private fun protoMessage(vararg fields: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (f in fields) {
            out.write(f)
        }
        return out.toByteArray()
    }

    private fun varintField(fieldNumber: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, (fieldNumber.toLong() shl 3) or 0L)
        writeVarint(out, value)
        return out.toByteArray()
    }

    private fun lengthDelimitedField(fieldNumber: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, (fieldNumber.toLong() shl 3) or 2L)
        writeVarint(out, payload.size.toLong())
        out.write(payload)
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    private fun wrapGrpcFrame(flag: Int, payload: ByteArray): ByteArray {
        val len = payload.size
        val out = ByteArray(5 + len)
        out[0] = (flag and 0xFF).toByte()
        out[1] = ((len ushr 24) and 0xFF).toByte()
        out[2] = ((len ushr 16) and 0xFF).toByte()
        out[3] = ((len ushr 8) and 0xFF).toByte()
        out[4] = (len and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 5, len)
        return out
    }

    private fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }
}
