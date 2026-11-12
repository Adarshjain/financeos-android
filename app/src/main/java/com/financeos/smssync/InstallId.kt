package com.financeos.smssync

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/** A stable id for this install, sent at sign-in so the server can reuse this phone's row. */
object InstallId {
    private val PATTERN = Regex("^[A-Za-z0-9_-]{8,64}$")

    /** First 32 hex chars of SHA-256 over "financeos-sms:" + the device id, so the raw id never leaves the phone. */
    fun hash(deviceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(("financeos-sms:" + deviceId).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 32)
    }

    fun matchesServerPattern(id: String): Boolean = PATTERN.matches(id)

    fun get(context: Context): String {
        val deviceId = androidId(context)
        if (!deviceId.isNullOrBlank()) return hash(deviceId)
        // own prefs file: LocalStore.clearAll() wipes "state" on Disconnect, this must survive it
        val prefs = context.getSharedPreferences("install", Context.MODE_PRIVATE)
        prefs.getString("id", null)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString("id", fresh).commit()
        return fresh
    }

    // ANDROID_ID survives a reinstall of an app signed with the same key, which is the point; it is hashed before use.
    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
}
