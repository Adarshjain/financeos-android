package com.financeos.smssync

object SummaryBuilder {
    fun classifyAll(messages: List<RawMessage>, classifier: LineClassifier): Pair<List<ClassifiedMessage>, Skipped> {
        var personal = 0
        var otp = 0
        var promo = 0
        var unknown = 0
        val out = ArrayList<ClassifiedMessage>()
        for (m in messages) {
            when (val c = classifier.classify(m.address, m.body)) {
                is Classification.Skipped -> when (c.reason) {
                    SkipReason.PERSONAL -> personal++
                    SkipReason.OTP -> otp++
                    SkipReason.PROMO -> promo++
                    SkipReason.UNKNOWN_SENDER -> unknown++
                }
                is Classification.Line -> out.add(ClassifiedMessage(m, c))
            }
        }
        return out to Skipped(personal, otp, promo, unknown)
    }

    fun build(classified: List<ClassifiedMessage>, skipped: Skipped): Summary {
        val map = LinkedHashMap<LineKey, SummaryLine>()
        val seen = HashSet<String>()
        for (c in classified) {
            // the same message can sit in both stores: count it once
            if (!seen.add(c.fingerprint)) continue
            val t = c.raw.dateMillis
            val prev = map[c.line.key]
            map[c.line.key] = if (prev == null) {
                SummaryLine(c.line.key, 1, if (c.line.isTxn) 1 else 0, t, t)
            } else {
                prev.copy(
                    msgCount = prev.msgCount + 1,
                    txnCount = prev.txnCount + if (c.line.isTxn) 1 else 0,
                    firstMsgAt = minOf(prev.firstMsgAt, t),
                    lastMsgAt = maxOf(prev.lastMsgAt, t),
                )
            }
        }
        return Summary(map.values.toList(), skipped)
    }

    const val MAX_SUMMARY_LINES = 1000

    /** The server accepts at most 1000 lines: keep the ones with the most messages. */
    fun limit(summary: Summary): Summary =
        if (summary.lines.size <= MAX_SUMMARY_LINES) summary
        else summary.copy(lines = summary.lines.sortedByDescending { it.msgCount }.take(MAX_SUMMARY_LINES))
}
