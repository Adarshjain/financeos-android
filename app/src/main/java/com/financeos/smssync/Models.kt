package com.financeos.smssync

/** One message as read from the SMS or MMS store. Date is always epoch millis. */
data class RawMessage(val address: String, val body: String, val dateMillis: Long)

data class Family(val key: String, val pattern: String)

data class CatalogueSender(
    val header: String,
    val numberless: Boolean,
    val families: List<Family>,
)

data class EndingPattern(val key: String, val pattern: String)

data class OnLine(
    val id: String,
    val sender: String,
    val ending: String?,
    val family: String?,
    val syncFrom: String?,
)

data class DeviceConfig(
    val senders: Map<String, CatalogueSender>,
    val otpPatterns: List<String>,
    val promoHeaderSuffixes: List<String>,
    val endingPatterns: List<EndingPattern>,
    val lines: List<OnLine>,
    val paused: Boolean,
    val zone: String? = null,
)

data class LineKey(val sender: String, val ending: String?, val family: String?)

enum class SkipReason { PERSONAL, OTP, PROMO, UNKNOWN_SENDER }

sealed class Classification {
    data class Skipped(val reason: SkipReason) : Classification()
    data class Line(val key: LineKey, val sender: String, val isTxn: Boolean) : Classification()
}

data class ClassifiedMessage(val raw: RawMessage, val line: Classification.Line) {
    val fingerprint: String by lazy { Fingerprint.of(line.sender, raw.dateMillis, raw.body) }
}

data class SummaryLine(
    val key: LineKey,
    val msgCount: Int,
    val txnCount: Int,
    val firstMsgAt: Long,
    val lastMsgAt: Long,
)

data class Skipped(val personal: Int, val otp: Int, val promo: Int, val unknownSender: Int)

data class Summary(val lines: List<SummaryLine>, val skipped: Skipped)
