package com.financeos.smssync

import java.time.ZoneId

const val MAX_BODY_CHARS = 2000
const val MAX_BATCH = 200

data class Selection(val toSend: List<ClassifiedMessage>, val tooLong: Int)

object Rejections {
    const val PERMANENT = "PERMANENT"

    fun encode(signature: String, reason: String?): String = "$signature#${reason ?: "UNKNOWN"}"

    /**
     * PERMANENT is never retried. Otherwise a rejection is kept while the line config is
     * unchanged, except LINE_OFF which is retried because the line is ON in the fresh config.
     */
    fun skip(stored: String, currentSignature: String): Boolean {
        if (stored == PERMANENT) return true
        val i = stored.lastIndexOf('#')
        val sig = if (i < 0) stored else stored.substring(0, i)
        val reason = if (i < 0) "BEFORE_START" else stored.substring(i + 1)
        return sig == currentSignature && reason != "LINE_OFF"
    }
}

object UploadSelection {
    /** Signature of a line's config: a rejected message is retried only when this changes. */
    fun signature(line: OnLine): String = "${line.id}|${line.syncFrom ?: ""}"

    /**
     * ON lines only, received on or after syncFrom (device-zone start of day), body within
     * the limit, not already sent, not rejected under the same line config. Oldest first.
     */
    fun select(
        classified: List<ClassifiedMessage>,
        onLines: List<OnLine>,
        sent: Set<String>,
        rejected: Map<String, String>,
        zone: ZoneId,
    ): Selection {
        val byKey = onLines.associateBy { LineKey(it.sender, it.ending, it.family) }
        val seen = HashSet<String>()
        var tooLong = 0
        val out = ArrayList<ClassifiedMessage>()
        for (c in classified) {
            val line = byKey[c.line.key] ?: continue
            if (line.syncFrom != null && c.raw.dateMillis < Dates.startOfDayMillis(line.syncFrom, zone)) continue
            val fp = c.fingerprint
            if (fp in sent) continue
            val memo = rejected[fp]
            if (memo != null && Rejections.skip(memo, signature(line))) continue
            if (c.raw.body.length > MAX_BODY_CHARS) {
                tooLong++
                continue
            }
            if (!seen.add(fp)) continue
            out.add(c)
        }
        out.sortBy { it.raw.dateMillis }
        return Selection(out, tooLong)
    }
}
