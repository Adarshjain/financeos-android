package com.financeos.smssync

object SiteUrl {
    /** Returns the normalised base address, or null when it is not acceptable. */
    fun normalize(input: String, allowHttp: Boolean): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "https" && !(allowHttp && scheme == "http")) return null
        val rest = withScheme.substringAfter("://").trimEnd('/')
        if (rest.isEmpty()) return null
        return "$scheme://$rest"
    }
}
