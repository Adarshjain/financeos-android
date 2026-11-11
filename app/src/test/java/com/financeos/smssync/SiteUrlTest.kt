package com.financeos.smssync

import org.junit.Assert.*
import org.junit.Test

class SiteUrlTest {
    @Test fun httpsAccepted() {
        assertEquals("https://example.test", SiteUrl.normalize(" https://example.test/ ", false))
        assertEquals("https://example.test", SiteUrl.normalize("example.test", false))
    }

    @Test fun httpOnlyInDebug() {
        assertNull(SiteUrl.normalize("http://10.0.2.2:6969", false))
        assertEquals("http://10.0.2.2:6969", SiteUrl.normalize("http://10.0.2.2:6969", true))
    }

    @Test fun junkRejected() {
        assertNull(SiteUrl.normalize("", true))
        assertNull(SiteUrl.normalize("ftp://example.test", true))
        assertNull(SiteUrl.normalize("https://", true))
    }
}
