package com.financeos.smssync

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.DateTimeException
import java.time.ZoneId
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class UnauthorizedException : IOException("unauthorized")
class PausedException : IOException("paused")

class HttpFailure(val code: Int) : IOException("http $code") {
    /** 5xx, 408 and 429 are worth retrying; other statuses will fail the same way again. */
    val retryable: Boolean get() = code >= 500 || code == 408 || code == 429
}

data class UploadResult(val fingerprint: String, val status: String, val reason: String? = null)

interface DeviceApi {
    /** Raw config JSON. */
    fun getConfig(): String
    fun postSummary(summary: Summary)
    fun postMessages(messages: List<ClassifiedMessage>): List<UploadResult>
}

interface MessageSource {
    fun readAll(): List<RawMessage>
}

interface SyncStore {
    fun isPausedLocally(): Boolean
    fun sentFingerprints(): Set<String>
    fun rejected(): Map<String, String>
    fun markSent(fingerprints: Collection<String>)
    fun markRejected(fingerprintToMemo: Map<String, String>)
    fun saveConfig(json: String)
    fun saveRun(stats: RunStats)
}

data class RunStats(
    /** 0 = do not touch the last successful sync time. */
    val syncedAt: Long,
    val skipped: Skipped,
    val tooLong: Int,
    val waiting: Int,
    val pausedByServer: Boolean,
    val failedPermanently: Int = 0,
    val rulesError: Boolean = false,
)

enum class SyncOutcome { DONE, PAUSED_LOCAL, PAUSED_SERVER, UNAUTHORIZED, RULES_ERROR, CANCELLED }

class SyncEngine(
    private val api: DeviceApi,
    private val source: MessageSource,
    private val store: SyncStore,
    private val deviceZone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
    private val cancelled: () -> Boolean = { false },
) {
    /**
     * Throws IOException for anything worth retrying with backoff (malformed server data
     * included). Only one run happens at a time in the process.
     */
    fun run(): SyncOutcome = LOCK.withLock {
        try {
            runLocked()
        } catch (e: DateTimeException) {
            throw IOException("malformed server data")
        } catch (e: JSONException) {
            throw IOException("malformed server data")
        }
    }

    private fun runLocked(): SyncOutcome {
        if (store.isPausedLocally()) return SyncOutcome.PAUSED_LOCAL
        var waiting = 0
        var skipped = Skipped(0, 0, 0, 0)
        var tooLong = 0
        var failed = 0
        try {
            val configJson = api.getConfig()
            val config = ConfigParser.parse(configJson)
            store.saveConfig(configJson)
            if (config.paused) {
                store.saveRun(RunStats(0, skipped, 0, 0, true))
                return SyncOutcome.PAUSED_SERVER
            }
            val classifier = LineClassifier(config)
            if (!classifier.otpRulesOk) {
                store.saveRun(RunStats(0, skipped, 0, 0, false, rulesError = true))
                return SyncOutcome.RULES_ERROR
            }
            val classifiedAll = SummaryBuilder.classifyAll(source.readAll(), classifier)
            val classified = classifiedAll.first
            skipped = classifiedAll.second
            if (cancelled()) return SyncOutcome.CANCELLED
            try {
                api.postSummary(SummaryBuilder.limit(SummaryBuilder.build(classified, skipped)))
            } catch (e: HttpFailure) {
                if (e.retryable) throw e // otherwise the summary will fail the same way: carry on with uploads
            }

            val zone = resolveZone(config.zone)
            val sel = UploadSelection.select(classified, config.lines, store.sentFingerprints(), store.rejected(), zone)
            tooLong = sel.tooLong
            val sigByKey = config.lines.associate { LineKey(it.sender, it.ending, it.family) to UploadSelection.signature(it) }
            val sigByFp = sel.toSend.associate { it.fingerprint to sigByKey.getValue(it.line.key) }
            waiting = sel.toSend.size

            for (batch in sel.toSend.chunked(MAX_BATCH)) {
                val r = sendBatch(batch, sigByFp) ?: return SyncOutcome.CANCELLED
                waiting -= r.handled
                failed += r.failed
            }
            store.saveRun(RunStats(now(), skipped, tooLong, waiting.coerceAtLeast(0), false, failed))
            return SyncOutcome.DONE
        } catch (e: PausedException) {
            store.saveRun(RunStats(0, skipped, tooLong, waiting, true, failed))
            return SyncOutcome.PAUSED_SERVER
        } catch (e: UnauthorizedException) {
            return SyncOutcome.UNAUTHORIZED
        }
    }

    private class BatchResult(val handled: Int, val failed: Int)

    /** Null = cancelled. A non-retryable HTTP failure splits the batch down to single messages. */
    private fun sendBatch(batch: List<ClassifiedMessage>, sigByFp: Map<String, String>): BatchResult? {
        if (cancelled()) return null
        try {
            val results = api.postMessages(batch)
            val ok = results.filter { it.status == "ACCEPTED" || it.status == "DUPLICATE" }.map { it.fingerprint }
            val bad = results.filter { it.status == "REJECTED" }.associate {
                it.fingerprint to if (it.reason == "BAD_MESSAGE") Rejections.PERMANENT
                else Rejections.encode(sigByFp[it.fingerprint] ?: "", it.reason)
            }
            store.markSent(ok)
            store.markRejected(bad)
            return BatchResult(ok.size + bad.size, 0)
        } catch (e: HttpFailure) {
            if (e.retryable) throw e
            if (batch.size == 1) {
                store.markRejected(mapOf(batch[0].fingerprint to Rejections.PERMANENT))
                return BatchResult(1, 1)
            }
            val half = batch.size / 2
            val a = sendBatch(batch.subList(0, half), sigByFp) ?: return null
            val b = sendBatch(batch.subList(half, batch.size), sigByFp) ?: return null
            return BatchResult(a.handled + b.handled, a.failed + b.failed)
        }
    }

    private fun resolveZone(id: String?): ZoneId =
        if (id.isNullOrEmpty()) deviceZone else try { ZoneId.of(id) } catch (e: DateTimeException) { deviceZone }

    companion object {
        /** Process-wide: one sync at a time, and no wipe while a run is writing state. */
        val LOCK = ReentrantLock()

        fun messagesJson(messages: List<ClassifiedMessage>): String {
            val arr = JSONArray()
            for (m in messages) {
                arr.put(
                    JSONObject()
                        .put("sender", m.line.sender)
                        .put("body", m.raw.body)
                        .put("receivedAt", Dates.iso(m.raw.dateMillis))
                        .put("simSlot", JSONObject.NULL)
                        .put("fingerprint", m.fingerprint),
                )
            }
            return JSONObject().put("messages", arr).toString()
        }

        fun summaryJson(s: Summary): String {
            val arr = JSONArray()
            for (l in s.lines) {
                arr.put(
                    JSONObject()
                        .put("sender", l.key.sender)
                        .put("ending", l.key.ending ?: JSONObject.NULL)
                        .put("family", l.key.family ?: JSONObject.NULL)
                        .put("msgCount", l.msgCount)
                        .put("txnCount", l.txnCount)
                        .put("firstMsgAt", Dates.iso(l.firstMsgAt))
                        .put("lastMsgAt", Dates.iso(l.lastMsgAt)),
                )
            }
            val k = s.skipped
            return JSONObject().put("lines", arr).put(
                "skipped",
                JSONObject().put("personal", k.personal).put("otp", k.otp).put("promo", k.promo).put("unknownSender", k.unknownSender),
            ).toString()
        }
    }
}
