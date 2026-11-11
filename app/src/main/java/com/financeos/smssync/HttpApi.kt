package com.financeos.smssync

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain HttpURLConnection client. Never logs bodies, tokens or the code. */
class HttpApi(private val site: String, private val token: String?) : DeviceApi {

    class Response(val code: Int, val body: String)

    override fun getConfig(): String = deviceCall("GET", "/api/v1/sms/device/config", null).body

    override fun postSummary(summary: Summary) {
        deviceCall("POST", "/api/v1/sms/device/summary", SyncEngine.summaryJson(summary))
    }

    override fun postMessages(messages: List<ClassifiedMessage>): List<UploadResult> {
        val r = deviceCall("POST", "/api/v1/sms/device/messages", SyncEngine.messagesJson(messages))
        val arr = JSONObject(r.body).getJSONArray("results")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            UploadResult(
                o.getString("fingerprint"),
                o.getString("status"),
                if (o.isNull("reason")) null else o.optString("reason"),
            )
        }
    }

    private fun deviceCall(method: String, path: String, body: String?): Response {
        val r = request(method, path, body, token)
        when {
            r.code == 401 -> throw UnauthorizedException()
            r.code == 409 -> throw PausedException()
            r.code !in 200..299 -> throw HttpFailure(r.code)
        }
        return r
    }

    fun pair(code: String, deviceName: String): Response =
        request("POST", "/api/v1/sms/pair", JSONObject().put("code", code).put("deviceName", deviceName).toString(), null)

    /** Best effort: tells the server this phone is gone. Every failure is ignored. */
    fun revoke() {
        try {
            request("POST", "/api/v1/sms/device/revoke", null, token, connectMs = 4_000, readMs = 4_000)
        } catch (e: Exception) {
            // ignored on purpose
        }
    }

    private fun request(
        method: String,
        path: String,
        body: String?,
        bearer: String?,
        connectMs: Int = 20_000,
        readMs: Int = 60_000,
    ): Response {
        val conn = URL(site + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            } else if (method == "POST") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(0)
                conn.outputStream.close()
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return Response(code, text)
        } catch (e: IOException) {
            throw IOException("network error")
        } finally {
            conn.disconnect()
        }
    }
}
