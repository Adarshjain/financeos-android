package com.financeos.smssync

import org.json.JSONArray
import org.json.JSONObject

object ConfigParser {
    fun parse(json: String): DeviceConfig {
        val root = JSONObject(json)
        val cat = root.optJSONObject("catalogue") ?: JSONObject()
        val senders = LinkedHashMap<String, CatalogueSender>()
        val arr = cat.optJSONArray("senders") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val fams = ArrayList<Family>()
            val fa = s.optJSONArray("families") ?: JSONArray()
            for (j in 0 until fa.length()) {
                val f = fa.getJSONObject(j)
                fams.add(Family(f.getString("key"), f.getString("pattern")))
            }
            val header = s.getString("header").uppercase()
            senders[header] = CatalogueSender(header, s.optBoolean("numberless", false), fams)
        }
        val drop = cat.optJSONObject("drop")
        val otp = strings(drop?.optJSONArray("otpPatterns"))
        val promo = drop?.optJSONArray("promoHeaderSuffixes")?.let { strings(it) } ?: listOf("P")
        val endings = ArrayList<EndingPattern>()
        val ea = cat.optJSONArray("endingPatterns") ?: JSONArray()
        for (i in 0 until ea.length()) {
            val e = ea.getJSONObject(i)
            endings.add(EndingPattern(e.getString("key"), e.getString("pattern")))
        }
        val lines = ArrayList<OnLine>()
        val la = root.optJSONArray("lines") ?: JSONArray()
        for (i in 0 until la.length()) {
            val l = la.getJSONObject(i)
            lines.add(OnLine(l.getString("id"), l.getString("sender"), str(l, "ending"), str(l, "family"), str(l, "syncFrom")))
        }
        return DeviceConfig(senders, otp, promo, endings, lines, root.optBoolean("paused", false), str(root, "zone"))
    }

    private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
}
