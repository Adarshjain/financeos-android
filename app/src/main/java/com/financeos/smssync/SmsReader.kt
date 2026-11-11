package com.financeos.smssync

import android.content.Context
import android.net.Uri

/** Reads both the SMS inbox and the MMS inbox (migrated history lives in the MMS store). */
class SmsReader(private val context: Context) : MessageSource {

    override fun readAll(): List<RawMessage> = readSms() + readMms()

    private fun readSms(): List<RawMessage> {
        val out = ArrayList<RawMessage>()
        context.contentResolver.query(
            Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), null, null, "date ASC",
        )?.use { c ->
            val a = c.getColumnIndexOrThrow("address")
            val b = c.getColumnIndexOrThrow("body")
            val d = c.getColumnIndexOrThrow("date")
            while (c.moveToNext()) {
                val address = c.getString(a) ?: continue
                out.add(RawMessage(address, c.getString(b) ?: "", c.getLong(d)))
            }
        }
        return out
    }

    private fun readMms(): List<RawMessage> {
        val texts = HashMap<Long, MutableList<String>>()
        context.contentResolver.query(
            Uri.parse("content://mms/part"), arrayOf("_id", "mid", "text", "_data"), "ct = 'text/plain'", null, "_id ASC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow("_id")
            val mid = c.getColumnIndexOrThrow("mid")
            val text = c.getColumnIndexOrThrow("text")
            val data = c.getColumnIndexOrThrow("_data")
            while (c.moveToNext()) {
                var t = c.getString(text)
                if (t == null && c.getString(data) != null) t = readPart(c.getLong(id))
                if (!t.isNullOrEmpty()) texts.getOrPut(c.getLong(mid)) { ArrayList() }.add(t)
            }
        }
        val bulk = bulkSenders()
        val out = ArrayList<RawMessage>()
        context.contentResolver.query(
            Uri.parse("content://mms"), arrayOf("_id", "date"), "msg_box = 1", null, "date ASC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow("_id")
            val d = c.getColumnIndexOrThrow("date")
            while (c.moveToNext()) {
                val mmsId = c.getLong(id)
                val parts = texts[mmsId] ?: continue
                val address = bulk[mmsId] ?: mmsSender(mmsId) ?: continue
                out.add(RawMessage(address, parts.joinToString(" "), Dates.mmsToMillis(c.getLong(d))))
            }
        }
        return out
    }

    /** One query for all senders when the platform allows it; empty means use the per-message query. */
    private fun bulkSenders(): Map<Long, String> {
        val map = HashMap<Long, String>()
        try {
            context.contentResolver.query(
                Uri.parse("content://mms/addr"), arrayOf("msg_id", "address"), "type = 137", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val a = c.getString(1) ?: continue
                    map.putIfAbsent(c.getLong(0), a)
                }
            }
        } catch (e: Exception) {
            map.clear()
        }
        return map
    }

    private fun mmsSender(mmsId: Long): String? {
        context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"), arrayOf("address"), "type = 137", null, null,
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private fun readPart(partId: Long): String? = try {
        context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))
            ?.use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }
}
