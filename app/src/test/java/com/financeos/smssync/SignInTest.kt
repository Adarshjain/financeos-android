package com.financeos.smssync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SignInTest {

    @Test fun passwordStatuses() {
        assertEquals(SignInFailure.WRONG_CREDENTIALS, SignInFailures.forStatus(false, 401))
        assertEquals(SignInFailure.TOO_MANY, SignInFailures.forStatus(false, 429))
        assertEquals(SignInFailure.SERVER, SignInFailures.forStatus(false, 404))
        assertEquals(SignInFailure.SERVER, SignInFailures.forStatus(false, 503))
        assertEquals(SignInFailure.SERVER, SignInFailures.forStatus(false, 500))
    }

    @Test fun googleStatuses() {
        assertEquals(SignInFailure.GOOGLE_REJECTED, SignInFailures.forStatus(true, 401))
        assertEquals(SignInFailure.NO_ACCOUNT, SignInFailures.forStatus(true, 404))
        assertEquals(SignInFailure.TOO_MANY, SignInFailures.forStatus(true, 429))
        assertEquals(SignInFailure.GOOGLE_NOT_SET_UP, SignInFailures.forStatus(true, 503))
        assertEquals(SignInFailure.SERVER, SignInFailures.forStatus(true, 500))
    }

    @Test fun passwordBody() {
        val o = JSONObject(HttpApi.loginBody("name@example.test", "p\"w", "Phone X", "abcd1234efgh5678"))
        assertEquals("name@example.test", o.getString("email"))
        assertEquals("p\"w", o.getString("password"))
        assertEquals("Phone X", o.getString("deviceName"))
        assertEquals("abcd1234efgh5678", o.getString("installId"))
        assertEquals(4, o.length())
    }

    @Test fun googleBody() {
        val o = JSONObject(HttpApi.googleLoginBody("invented-id-token", "Phone X", "abcd1234efgh5678"))
        assertEquals("invented-id-token", o.getString("idToken"))
        assertEquals("Phone X", o.getString("deviceName"))
        assertFalse(o.has("password"))
        assertEquals("abcd1234efgh5678", o.getString("installId"))
        assertEquals(3, o.length())
    }
}
