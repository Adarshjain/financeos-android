package com.financeos.smssync

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class SyncEngineTest {
    private class Store : SyncStore {
        var paused = false
        val sent = HashSet<String>()
        val rej = HashMap<String, String>()
        var run: RunStats? = null
        override fun isPausedLocally() = paused
        override fun sentFingerprints(): Set<String> = sent
        override fun rejected(): Map<String, String> = rej
        override fun markSent(fingerprints: Collection<String>) { sent.addAll(fingerprints) }
        override fun markRejected(fingerprintToMemo: Map<String, String>) { rej.putAll(fingerprintToMemo) }
        override fun saveConfig(json: String) {}
        override fun saveRun(stats: RunStats) { run = stats }
    }

    private class Api(val json: String, val status: (String) -> String = { "ACCEPTED" }, val reason: (String) -> String? = { null }) : DeviceApi {
        var summaries = 0
        val batches = ArrayList<List<ClassifiedMessage>>()
        override fun getConfig() = json
        override fun postSummary(summary: Summary) { summaries++ }
        override fun postMessages(messages: List<ClassifiedMessage>): List<UploadResult> {
            batches.add(messages)
            return messages.map { UploadResult(it.fingerprint, status(it.raw.body), reason(it.raw.body)) }
        }
    }

    private val src = object : MessageSource {
        override fun readAll() = (1..450).map { RawMessage("VM-TESTBK-S", "Rs $it debited from XX1234", 1_800_000_000_000L + it) } +
            RawMessage("VM-TESTBK-S", "Rs 999 debited from XX1234", 1_000_000_000_000L)
    }

    @Test fun batchesOf200AndRemembersSent() {
        val store = Store()
        val api = Api(TestConfig.JSON)
        val out = SyncEngine(api, src, store, ZoneId.of("UTC")).run()
        assertEquals(SyncOutcome.DONE, out)
        assertEquals(listOf(200, 200, 50), api.batches.map { it.size })
        assertEquals(450, store.sent.size)
        assertEquals(0, store.run!!.waiting)
        // second run uploads nothing
        val api2 = Api(TestConfig.JSON)
        SyncEngine(api2, src, store, ZoneId.of("UTC")).run()
        assertTrue(api2.batches.isEmpty())
        assertEquals(1, api2.summaries)
    }

    @Test fun rejectedRecordedNotSent() {
        val store = Store()
        SyncEngine(Api(TestConfig.JSON, { if (it.contains("Rs 7 ")) "REJECTED" else "DUPLICATE" }, { "BEFORE_START" }), src, store, ZoneId.of("UTC")).run()
        assertEquals(449, store.sent.size)
        assertEquals(1, store.rej.size)
        assertEquals("L1|2026-03-10#BEFORE_START", store.rej.values.single())
    }

    @Test fun localPauseUploadsNothing() {
        val store = Store().also { it.paused = true }
        val api = Api(TestConfig.JSON)
        assertEquals(SyncOutcome.PAUSED_LOCAL, SyncEngine(api, src, store).run())
        assertEquals(0, api.summaries)
    }

    @Test fun serverPausedFlag() {
        val api = Api(TestConfig.JSON.replace("\"paused\": false", "\"paused\": true"))
        val store = Store()
        assertEquals(SyncOutcome.PAUSED_SERVER, SyncEngine(api, src, store).run())
        assertEquals(0, api.summaries)
        assertTrue(store.run!!.pausedByServer)
    }

    @Test fun unauthorizedBubblesAsOutcome() {
        val api = object : DeviceApi {
            override fun getConfig(): String = throw UnauthorizedException()
            override fun postSummary(summary: Summary) {}
            override fun postMessages(messages: List<ClassifiedMessage>) = emptyList<UploadResult>()
        }
        assertEquals(SyncOutcome.UNAUTHORIZED, SyncEngine(api, src, Store()).run())
    }

    private val utc = ZoneId.of("UTC")

    @Test fun badMessageIsPermanentLineOffIsNot() {
        val store = Store()
        SyncEngine(
            Api(TestConfig.JSON, { if (it.contains("Rs 7 ")) "REJECTED" else if (it.contains("Rs 8 ")) "REJECTED" else "ACCEPTED" },
                { if (it.contains("Rs 7 ")) "BAD_MESSAGE" else "LINE_OFF" }),
            src, store, utc,
        ).run()
        assertEquals(setOf(Rejections.PERMANENT, "L1|2026-03-10#LINE_OFF"), store.rej.values.toSet())
    }

    @Test fun overlappingRunsAreSerialised() {
        val active = java.util.concurrent.atomic.AtomicInteger()
        val max = java.util.concurrent.atomic.AtomicInteger()
        val slow = object : DeviceApi {
            override fun getConfig(): String {
                max.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                Thread.sleep(80)
                active.decrementAndGet()
                return TestConfig.JSON
            }
            override fun postSummary(summary: Summary) {}
            override fun postMessages(messages: List<ClassifiedMessage>) = messages.map { UploadResult(it.fingerprint, "ACCEPTED") }
        }
        val threads = (1..3).map { Thread { SyncEngine(slow, src, Store(), utc).run() }.also { it.start() } }
        threads.forEach { it.join() }
        assertEquals(1, max.get())
    }

    @Test fun cancelStopsBeforeNextBatch() {
        val api = Api(TestConfig.JSON)
        val store = Store()
        val out = SyncEngine(api, src, store, utc, cancelled = { api.batches.size >= 1 }).run()
        assertEquals(SyncOutcome.CANCELLED, out)
        assertEquals(1, api.batches.size)
        assertEquals(200, store.sent.size)
    }

    @Test fun cancelBeforeSummaryUploadsNothing() {
        val api = Api(TestConfig.JSON)
        assertEquals(SyncOutcome.CANCELLED, SyncEngine(api, src, Store(), utc, cancelled = { true }).run())
        assertEquals(0, api.summaries)
        assertTrue(api.batches.isEmpty())
    }

    private class FailingApi(val failure: (List<ClassifiedMessage>) -> Int?) : DeviceApi {
        val calls = ArrayList<Int>()
        override fun getConfig() = TestConfig.JSON
        override fun postSummary(summary: Summary) {}
        override fun postMessages(messages: List<ClassifiedMessage>): List<UploadResult> {
            calls.add(messages.size)
            failure(messages)?.let { throw HttpFailure(it) }
            return messages.map { UploadResult(it.fingerprint, "ACCEPTED") }
        }
    }

    @Test fun badRequestHalvesDownToTheOffendingMessage() {
        val store = Store()
        val api = FailingApi { b -> if (b.any { it.raw.body.contains("Rs 13 ") }) 400 else null }
        val out = SyncEngine(api, src, store, utc).run()
        assertEquals(SyncOutcome.DONE, out)
        assertEquals(449, store.sent.size)
        assertEquals(1, store.rej.size)
        assertEquals(Rejections.PERMANENT, store.rej.values.single())
        assertEquals(1, store.run!!.failedPermanently)
        assertEquals(0, store.run!!.waiting)
    }

    @Test fun serverErrorKeepsStateAndRetries() {
        val store = Store()
        val api = FailingApi { 500 }
        try {
            SyncEngine(api, src, store, utc).run()
            fail("expected a retryable failure")
        } catch (e: HttpFailure) {
            assertTrue(e.retryable)
        }
        assertTrue(store.sent.isEmpty())
        assertTrue(store.rej.isEmpty())
        assertEquals(1, api.calls.size)
        assertTrue(HttpFailure(408).retryable && HttpFailure(429).retryable && !HttpFailure(404).retryable)
    }

    @Test fun configZoneDecidesTheSyncFromBoundary() {
        // 2026-03-09T20:00Z is 2026-03-10 01:30 in Kolkata, but still 03-09 in UTC
        val one = object : MessageSource {
            override fun readAll() = listOf(RawMessage("VM-TESTBK-S", "Rs 1 debited from XX1234", 1_773_086_400_000L))
        }
        assertEquals("2026-03-09T20:00:00Z", Dates.iso(1_773_086_400_000L))
        val utcApi = Api(TestConfig.JSON)
        SyncEngine(utcApi, one, Store(), utc).run()
        assertTrue(utcApi.batches.isEmpty())
        val withZone = TestConfig.JSON.replace("\"paused\": false", "\"paused\": false, \"zone\": \"Asia/Kolkata\"")
        val kApi = Api(withZone)
        SyncEngine(kApi, one, Store(), utc).run()
        assertEquals(1, kApi.batches.single().size)
        // an unknown zone id falls back to the device zone
        val badZone = Api(TestConfig.JSON.replace("\"paused\": false", "\"paused\": false, \"zone\": \"Nowhere/Land\""))
        SyncEngine(badZone, one, Store(), utc).run()
        assertTrue(badZone.batches.isEmpty())
    }

    @Test fun brokenOtpRuleFailsClosed() {
        val json = TestConfig.JSON.replace("\"(?i)do\\\\s+not\\\\s+share\"", "\"(\"")
        assertTrue("replacement applied", json != TestConfig.JSON)
        val api = Api(json)
        val store = Store()
        assertEquals(SyncOutcome.RULES_ERROR, SyncEngine(api, src, store, utc).run())
        assertEquals(0, api.summaries)
        assertTrue(api.batches.isEmpty())
        assertTrue(store.run!!.rulesError)
    }

    @Test fun brokenFamilyOrEndingRuleOnlySkipsThatRule() {
        val json = TestConfig.JSON.replace("\"pattern\": \"Apay\"", "\"pattern\": \"(\"")
        assertTrue(LineClassifier(ConfigParser.parse(json)).otpRulesOk)
    }

    @Test fun malformedServerDataIsARetryableFailure() {
        for (json in listOf("not json", TestConfig.JSON.replace("2026-03-10", "10/03/2026"))) {
            try {
                SyncEngine(Api(json), src, Store(), utc).run()
                fail("expected IOException")
            } catch (e: java.io.IOException) {
                // retried by the worker
            }
        }
    }
}
