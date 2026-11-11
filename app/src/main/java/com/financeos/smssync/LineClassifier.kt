package com.financeos.smssync

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** The 6-step line key of the SMS sync contract. Must stay identical to the server's. */
class LineClassifier(private val config: DeviceConfig) {

    private val otp = compileAll(config.otpPatterns)
    /** False when any OTP drop rule failed to compile: the caller must then upload nothing. */
    val otpRulesOk: Boolean = otp.size == config.otpPatterns.size

    private val endings = config.endingPatterns.mapNotNull { p -> compile(p.pattern)?.let { p.key to it } }
    private val familyPatterns: Map<String, List<Pair<String, Pattern>>> = config.senders.mapValues { (_, s) ->
        s.families.mapNotNull { f -> compile(f.pattern, ci = true)?.let { f.key to it } }
    }

    fun classify(address: String, body: String): Classification {
        val sender = normalizeSender(address) ?: return Classification.Skipped(SkipReason.PERSONAL)
        if (isPromoAddress(address, config.promoHeaderSuffixes)) return Classification.Skipped(SkipReason.PROMO)
        if (otp.any { it.matcher(body).find() }) return Classification.Skipped(SkipReason.OTP)

        val entry = config.senders[sender]
        if (entry != null && entry.numberless) {
            val patterns = familyPatterns[sender].orEmpty()
            var family: String? = null
            if (entry.families.isNotEmpty()) {
                family = patterns.firstOrNull { it.second.matcher(body).find() }?.first
                    ?: return Classification.Skipped(SkipReason.UNKNOWN_SENDER)
            }
            return Classification.Line(LineKey(sender, null, family), sender, isTransaction(body))
        }

        val ending = findEnding(body)
        val txn = isTransaction(body)
        if (entry == null && !(txn && ending != null)) {
            return Classification.Skipped(SkipReason.UNKNOWN_SENDER)
        }
        return Classification.Line(LineKey(sender, ending, null), sender, txn)
    }

    private fun findEnding(body: String): String? {
        var bestStart = Int.MAX_VALUE
        var best: String? = null
        for ((_, p) in endings) {
            val m = p.matcher(body)
            if (m.find() && m.start() < bestStart) {
                bestStart = m.start()
                best = m.group(1)
            }
        }
        return best
    }

    companion object {
        private val SENDER = Regex("^[A-Z0-9]{3,20}$")
        private val OPERATOR_PREFIX = Regex("^[A-Z]{2}-")
        private val SENDER_SUFFIX = Regex("-[STPG]$")
        private val AMOUNT = Pattern.compile("(?i)(?:inr|rs\\.?|₹)\\s*[\\d,]+(?:\\.\\d{1,2})?")
        private val MONEY_WORD = Pattern.compile(
            "(?i)\\b(debited|credited|spent|withdrawn|received|paid|deposited|refund(ed)?|deducted|purchase|sent)\\b",
        )

        /** Step 1. Null = personal (not a business header). */
        fun normalizeSender(address: String): String? {
            var s = address.trim().uppercase()
            s = OPERATOR_PREFIX.replace(s, "")
            s = SENDER_SUFFIX.replace(s, "")
            if (!SENDER.matches(s) || s.none { it.isLetter() }) return null
            return s
        }

        fun isPromoAddress(address: String, suffixes: List<String>): Boolean {
            val a = address.trim().uppercase()
            return suffixes.any { a.endsWith("-" + it.uppercase()) }
        }

        fun isTransaction(body: String): Boolean =
            AMOUNT.matcher(body).find() && MONEY_WORD.matcher(body).find()

        private fun compile(p: String, ci: Boolean = false): Pattern? = try {
            Pattern.compile(p, if (ci) Pattern.CASE_INSENSITIVE else 0)
        } catch (e: PatternSyntaxException) {
            null
        }

        private fun compileAll(ps: List<String>) = ps.mapNotNull { compile(it) }
    }
}
