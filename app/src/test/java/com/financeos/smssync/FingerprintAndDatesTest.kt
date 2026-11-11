package com.financeos.smssync

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class FingerprintAndDatesTest {
    @Test fun fingerprintFixedVector() {
        // sha256("TESTBK\n1700000000000\nRs 10 debited"), computed independently
        assertEquals(
            "97504d7a09db99c68657bf07b270feea6ffb0e1a4c9db3c15756856dc7b163b5",
            Fingerprint.of("TESTBK", 1700000000000L, "Rs 10 debited"),
        )
    }

    @Test fun fingerprintDependsOnEveryPart() {
        val base = Fingerprint.of("A1B", 1L, "x")
        assertNotEquals(base, Fingerprint.of("A1C", 1L, "x"))
        assertNotEquals(base, Fingerprint.of("A1B", 2L, "x"))
        assertNotEquals(base, Fingerprint.of("A1B", 1L, "y"))
    }

    @Test fun mmsSecondsToMillis() {
        assertEquals(1700000000000L, Dates.mmsToMillis(1700000000L))
    }

    @Test fun isoInstant() {
        assertEquals("2023-11-14T22:13:20Z", Dates.iso(1700000000000L))
        assertEquals("2023-11-14T22:13:20.123Z", Dates.iso(1700000000123L))
    }

    @Test fun startOfDayUsesZone() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        // 2026-03-10 00:00 IST = 2026-03-09 18:30 UTC
        assertEquals(Dates.iso(Dates.startOfDayMillis("2026-03-10", kolkata)), "2026-03-09T18:30:00Z")
        assertEquals(Dates.iso(Dates.startOfDayMillis("2026-03-10", ZoneId.of("UTC"))), "2026-03-10T00:00:00Z")
    }
}
