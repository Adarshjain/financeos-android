package com.financeos.smssync

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InstallIdTest {

    @Test fun hashShape() {
        val h = InstallId.hash("invented-device-1")
        assertEquals(32, h.length)
        assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(InstallId.matchesServerPattern(h))
    }

    @Test fun hashStableAndDistinct() {
        assertEquals(InstallId.hash("invented-device-1"), InstallId.hash("invented-device-1"))
        assertNotEquals(InstallId.hash("invented-device-1"), InstallId.hash("invented-device-2"))
    }

    @Test fun usesHashedDeviceId() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "invented-device-3")
        assertEquals(InstallId.hash("invented-device-3"), InstallId.get(context))
    }

    @Test fun fallbackSurvivesDisconnect() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "")
        val first = InstallId.get(context)
        assertTrue(InstallId.matchesServerPattern(first))
        assertEquals(first, InstallId.get(context))
        LocalStore(context).clearAll()
        assertEquals(first, InstallId.get(context))
    }
}
