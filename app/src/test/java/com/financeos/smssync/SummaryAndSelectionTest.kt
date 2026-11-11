package com.financeos.smssync

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class SummaryAndSelectionTest {
    private val utc = ZoneId.of("UTC")
    private val cfg = TestConfig.config
    private val classifier = LineClassifier(cfg)

    private fun ms(date: String, hour: Int = 12) = Dates.startOfDayMillis(date, utc) + hour * 3_600_000L

    private val messages = listOf(
        RawMessage("VM-TESTBK-S", "Rs 100 debited from XX1234", ms("2026-03-12")),
        RawMessage("VM-TESTBK-S", "Rs 50 credited to XX1234", ms("2026-03-09", 23)), // before syncFrom
        RawMessage("VM-TESTBK-S", "Rs 70 debited from XX1234", ms("2026-03-10", 0)), // exactly at boundary
        RawMessage("VM-TESTBK-S", "Hello from the bank XX1234", ms("2026-03-11")),
        RawMessage("VM-TESTBK-S", "Rs 9 debited from XX9999", ms("2026-03-11")), // other ending, line off
        RawMessage("JM-WALLET-S", "Rs 30 debited from Apay", ms("2026-01-01")),
        RawMessage("+919876543210", "Rs 30 debited", ms("2026-03-11")),
        RawMessage("9876543210", "hi", ms("2026-03-11")),
        RawMessage("VM-TESTBK-S", "111111 is your OTP", ms("2026-03-11")),
        RawMessage("VM-TESTBK-P", "Rs 5 off", ms("2026-03-11")),
        RawMessage("VM-NEWBNK-S", "Rs 5 off", ms("2026-03-11")),
    )

    @Test fun summaryAndSkippedCounts() {
        val (classified, skipped) = SummaryBuilder.classifyAll(messages, classifier)
        assertEquals(Skipped(personal = 2, otp = 1, promo = 1, unknownSender = 1), skipped)
        val s = SummaryBuilder.build(classified, skipped)
        val main = s.lines.single { it.key == LineKey("TESTBK", "1234", null) }
        assertEquals(4, main.msgCount)
        assertEquals(3, main.txnCount)
        assertEquals(ms("2026-03-09", 23), main.firstMsgAt)
        assertEquals(ms("2026-03-12"), main.lastMsgAt)
        assertEquals(3, s.lines.size)
        assertEquals(1, s.lines.single { it.key.family == "apay" }.txnCount)
    }

    private fun select(sent: Set<String> = emptySet(), rejected: Map<String, String> = emptyMap(), extra: List<RawMessage> = emptyList(), lines: List<OnLine> = cfg.lines): Selection {
        val (classified, _) = SummaryBuilder.classifyAll(messages + extra, classifier)
        return UploadSelection.select(classified, lines, sent, rejected, utc)
    }

    @Test fun onlyOnLinesFromSyncFromOldestFirst() {
        val sel = select()
        val bodies = sel.toSend.map { it.raw.body }
        // wallet (no syncFrom) first, then bank lines from the boundary onward
        assertEquals(
            listOf("Rs 30 debited from Apay", "Rs 70 debited from XX1234", "Hello from the bank XX1234", "Rs 100 debited from XX1234"),
            bodies,
        )
    }

    @Test fun nullSyncFromMeansAll() {
        val lines = listOf(OnLine("L1", "TESTBK", "1234", null, null))
        assertEquals(4, select(lines = lines).toSend.size)
    }

    @Test fun alreadySentIsSkipped() {
        val first = select().toSend.first()
        val sel = select(sent = setOf(first.fingerprint))
        assertEquals(3, sel.toSend.size)
        assertFalse(sel.toSend.any { it.fingerprint == first.fingerprint })
    }

    @Test fun rejectedNotRetriedUntilLineConfigChanges() {
        val target = select().toSend.first { it.raw.body.contains("Rs 100") }
        val sigL1 = Rejections.encode(UploadSelection.signature(cfg.lines[0]), "BEFORE_START")
        assertFalse(select(rejected = mapOf(target.fingerprint to sigL1)).toSend.any { it.fingerprint == target.fingerprint })
        // syncFrom changed -> retried
        val changed = listOf(cfg.lines[0].copy(syncFrom = "2026-03-01"), cfg.lines[1])
        assertTrue(select(rejected = mapOf(target.fingerprint to sigL1), lines = changed).toSend.any { it.fingerprint == target.fingerprint })
        // id changed -> retried
        val newId = listOf(cfg.lines[0].copy(id = "L9"), cfg.lines[1])
        assertTrue(select(rejected = mapOf(target.fingerprint to sigL1), lines = newId).toSend.any { it.fingerprint == target.fingerprint })
    }

    @Test fun overlongBodySkippedAndCounted() {
        val long = "Rs 1 debited from XX1234 " + "x".repeat(MAX_BODY_CHARS)
        val ok = "Rs 1 debited from XX1234 " + "x".repeat(MAX_BODY_CHARS - 25)
        val sel = select(extra = listOf(RawMessage("VM-TESTBK-S", long, ms("2026-03-13")), RawMessage("VM-TESTBK-S", ok, ms("2026-03-14"))))
        assertEquals(1, sel.tooLong)
        assertTrue(sel.toSend.any { it.raw.body == ok })
        assertFalse(sel.toSend.any { it.raw.body == long })
    }

    @Test fun duplicateMessagesUploadedOnce() {
        val dup = RawMessage("VM-TESTBK-S", "Rs 100 debited from XX1234", ms("2026-03-12"))
        assertEquals(4, select(extra = listOf(dup)).toSend.size)
    }

    @Test fun configParsed() {
        assertEquals(2, cfg.lines.size)
        assertNull(cfg.lines[1].ending)
        assertEquals("apay", cfg.lines[1].family)
        assertNull(cfg.lines[1].syncFrom)
        assertFalse(cfg.paused)
    }

    @Test fun rejectionMemoRules() {
        val sig = "L1|2026-03-10"
        assertTrue(Rejections.skip(Rejections.PERMANENT, "anything"))
        assertTrue(Rejections.skip(Rejections.encode(sig, "BEFORE_START"), sig))
        assertFalse(Rejections.skip(Rejections.encode(sig, "BEFORE_START"), "L1|2026-03-01"))
        assertFalse(Rejections.skip(Rejections.encode(sig, "LINE_OFF"), sig)) // line is ON again: retry
        assertFalse(Rejections.skip(Rejections.encode(sig, "LINE_OFF"), "L2|"))
        assertTrue(Rejections.skip(Rejections.encode(sig, "SOMETHING_NEW"), sig))
    }

    @Test fun messageInBothStoresCountsOnce() {
        val m = RawMessage("VM-TESTBK-S", "Rs 100 debited from XX1234", ms("2026-03-12"))
        val (classified, skipped) = SummaryBuilder.classifyAll(listOf(m, m.copy()), classifier)
        val line = SummaryBuilder.build(classified, skipped).lines.single()
        assertEquals(1, line.msgCount)
        assertEquals(1, line.txnCount)
        // same text at another millisecond is a different message
        val (c2, s2) = SummaryBuilder.classifyAll(listOf(m, m.copy(dateMillis = m.dateMillis + 1)), classifier)
        assertEquals(2, SummaryBuilder.build(c2, s2).lines.single().msgCount)
    }

    @Test fun summaryLimitedTo1000LinesWithMostMessages() {
        val lines = (1..1100).map { SummaryLine(LineKey("S$it", null, null), it, 0, 1, 1) }
        val limited = SummaryBuilder.limit(Summary(lines, Skipped(0, 0, 0, 0)))
        assertEquals(1000, limited.lines.size)
        assertTrue(limited.lines.all { it.msgCount > 100 })
    }

    @Test fun zoneIsOptionalInConfig() {
        assertNull(cfg.zone)
        val z = ConfigParser.parse(TestConfig.JSON.replace("\"paused\": false", "\"paused\": false, \"zone\": \"Asia/Kolkata\""))
        assertEquals("Asia/Kolkata", z.zone)
    }
}
