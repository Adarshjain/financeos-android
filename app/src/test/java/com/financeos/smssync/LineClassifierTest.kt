package com.financeos.smssync

import org.junit.Assert.*
import org.junit.Test

class LineClassifierTest {
    private val c = TestConfig.classifier

    private fun line(address: String, body: String) = c.classify(address, body) as Classification.Line
    private fun skipped(address: String, body: String) = (c.classify(address, body) as Classification.Skipped).reason

    @Test fun normalisesSender() {
        assertEquals("TESTBK", LineClassifier.normalizeSender("VM-TESTBK-S"))
        assertEquals("TESTBK", LineClassifier.normalizeSender("ax-testbk"))
        assertEquals("TESTBK", LineClassifier.normalizeSender("TESTBK-T"))
        assertEquals("AB1C2D", LineClassifier.normalizeSender("JD-AB1C2D-G"))
    }

    @Test fun personalSenders() {
        assertNull(LineClassifier.normalizeSender("+919876543210"))
        assertNull(LineClassifier.normalizeSender("AB"))
        assertNull(LineClassifier.normalizeSender("TEST BK"))
        assertNull(LineClassifier.normalizeSender("ABCDEFGHIJKLMNOPQRSTU"))
        assertEquals(SkipReason.PERSONAL, skipped("+919876543210", "Rs 10 debited"))
    }

    @Test fun allDigitSenderIsPersonal() {
        assertNull(LineClassifier.normalizeSender("9876543210"))
        assertNull(LineClassifier.normalizeSender("56767"))
        assertEquals(SkipReason.PERSONAL, skipped("9876543210", "Rs 10 debited from a/c XX1234"))
    }

    @Test fun promoAddressDropped() {
        assertEquals(SkipReason.PROMO, skipped("VM-TESTBK-P", "Rs 10 debited from XX1234"))
    }

    @Test fun otpDropped() {
        assertEquals(SkipReason.OTP, skipped("VM-TESTBK-S", "123456 is your OTP. Rs 10 debited XX1234"))
        assertEquals(SkipReason.OTP, skipped("VM-TESTBK-S", "Do not share this code"))
    }

    @Test fun numberedCatalogueSender() {
        val l = line("VM-TESTBK-S", "Rs 250.50 debited from a/c XX1234 on 01-03")
        assertEquals(LineKey("TESTBK", "1234", null), l.key)
        assertTrue(l.isTxn)
        assertEquals("TESTBK", l.sender)
    }

    @Test fun numberedSenderWithoutEndingStillALine() {
        val l = line("VM-TESTBK-S", "Your statement is ready")
        assertEquals(LineKey("TESTBK", null, null), l.key)
        assertFalse(l.isTxn)
    }

    @Test fun numberlessWithFamilies() {
        val l = line("JM-WALLET-S", "Rs 99 debited from your Apay balance")
        assertEquals(LineKey("WALLET", null, "apay"), l.key)
        val g = line("JM-WALLET-S", "Rs 5 credited to your GIFT CARD")
        assertEquals("gift", g.key.family)
        // first family in config order wins when several match
        assertEquals("apay", line("JM-WALLET-S", "Apay gift card Rs 5 credited").key.family)
    }

    @Test fun numberlessMessageMatchingNoFamilyIsUnknown() {
        assertEquals(SkipReason.UNKNOWN_SENDER, skipped("JM-WALLET-S", "Rs 99 debited somewhere"))
    }

    @Test fun numberlessWithoutFamilies() {
        val l = line("PLAINW", "hello")
        assertEquals(LineKey("PLAINW", null, null), l.key)
        assertFalse(l.isTxn)
    }

    @Test fun unknownSenderGate() {
        assertEquals(LineKey("NEWBNK", "4321", null), line("VM-NEWBNK-S", "INR 1,200.00 debited from card XX4321").key)
        // no ending
        assertEquals(SkipReason.UNKNOWN_SENDER, skipped("VM-NEWBNK-S", "INR 1,200 debited from your card"))
        // no amount
        assertEquals(SkipReason.UNKNOWN_SENDER, skipped("VM-NEWBNK-S", "Card XX4321 debited"))
        // no money word
        assertEquals(SkipReason.UNKNOWN_SENDER, skipped("VM-NEWBNK-S", "Rs 100 offer on card XX4321"))
    }

    @Test fun earliestEndingMatchWins() {
        // masked group appears first
        assertEquals("1111", line("TESTBK", "A/c XX1111 to ref ***2222 Rs 5 debited").key.ending)
        // other order
        assertEquals("2222", line("TESTBK", "To ref ***2222 from XX1111 Rs 5 debited").key.ending)
    }

    @Test fun groupedMaskForm() {
        assertEquals("789", line("TESTBK", "Rs 5 debited from 123-456***-789 today").key.ending)
        assertEquals("789", line("TESTBK", "123-456***-789 Rs 5 debited").key.ending)
    }

    @Test fun transactionCriteria() {
        assertTrue(LineClassifier.isTransaction("Rs.10 spent"))
        assertTrue(LineClassifier.isTransaction("₹ 1,000 refunded"))
        assertFalse(LineClassifier.isTransaction("Rs 10 will arrive"))
        assertFalse(LineClassifier.isTransaction("debited"))
    }
}
