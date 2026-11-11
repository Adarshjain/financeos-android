package com.financeos.smssync

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object Fingerprint {
    /** Lower-case hex SHA-256 of UTF-8 `sender \n receivedAt epoch millis \n body`. */
    fun of(sender: String, receivedAtMillis: Long, body: String): String {
        val bytes = "$sender\n$receivedAtMillis\n$body".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

object Dates {
    /** MMS dates are seconds, SMS dates are millis. */
    fun mmsToMillis(seconds: Long): Long = seconds * 1000L

    fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    /** Start of the given yyyy-MM-dd day in the zone, as epoch millis. */
    fun startOfDayMillis(date: String, zone: ZoneId): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
}
