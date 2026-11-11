package com.financeos.smssync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.withLock

/** Local state: encrypted token, site, counters, sent fingerprints, cached config. */
class LocalStore(private val context: Context) : SyncStore {

    private val prefs = context.getSharedPreferences("state", Context.MODE_PRIVATE)
    private val db: DbHelper by lazy { DbHelper(context) }
    private val configFile get() = File(context.filesDir, "config.json")

    var site: String?
        get() = prefs.getString("site", null)
        set(v) = prefs.edit().putString("site", v).apply()

    var token: String?
        get() = prefs.getString("token", null)?.let { decrypt(it) }
        set(v) = prefs.edit().putString("token", v?.let { encrypt(it) }).apply()

    var pausedLocally: Boolean
        get() = prefs.getBoolean("paused", false)
        set(v) = prefs.edit().putBoolean("paused", v).apply()

    var notice: String?
        get() = prefs.getString("notice", null)
        set(v) = prefs.edit().putString("notice", v).apply()

    var permissionAsked: Boolean
        get() = prefs.getBoolean("permAsked", false)
        set(v) = prefs.edit().putBoolean("permAsked", v).apply()

    val rulesError get() = prefs.getBoolean("rulesError", false)
    val failedPermanently get() = prefs.getInt("failedPerm", 0)

    val isConnected get() = site != null && token != null

    val lastSyncAt get() = prefs.getLong("lastSyncAt", 0L)
    val pausedByServer get() = prefs.getBoolean("pausedByServer", false)
    val waiting get() = prefs.getInt("waiting", 0)
    val tooLong get() = prefs.getInt("tooLong", 0)
    val skipped
        get() = Skipped(
            prefs.getInt("sk_personal", 0), prefs.getInt("sk_otp", 0),
            prefs.getInt("sk_promo", 0), prefs.getInt("sk_unknown", 0),
        )

    fun sentCount(): Int = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM sent", null).use { it.moveToFirst(); it.getInt(0) }

    fun cachedConfig(): DeviceConfig? = try {
        if (configFile.exists()) ConfigParser.parse(configFile.readText()) else null
    } catch (e: Exception) {
        null
    }

    override fun isPausedLocally() = pausedLocally

    override fun sentFingerprints(): Set<String> {
        val out = HashSet<String>()
        db.readableDatabase.rawQuery("SELECT fp FROM sent", null).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    override fun rejected(): Map<String, String> {
        val out = HashMap<String, String>()
        db.readableDatabase.rawQuery("SELECT fp, sig FROM rejected", null).use { while (it.moveToNext()) out[it.getString(0)] = it.getString(1) }
        return out
    }

    override fun markSent(fingerprints: Collection<String>) {
        if (fingerprints.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (fp in fingerprints) {
                w.insertWithOnConflict("sent", null, cv("fp" to fp), SQLiteDatabase.CONFLICT_IGNORE)
                w.delete("rejected", "fp = ?", arrayOf(fp))
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    override fun markRejected(fingerprintToMemo: Map<String, String>) {
        if (fingerprintToMemo.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for ((fp, sig) in fingerprintToMemo) {
                w.insertWithOnConflict(
                    "rejected", null, cv("fp" to fp, "sig" to sig), SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    override fun saveConfig(json: String) {
        configFile.writeText(json)
    }

    override fun saveRun(stats: RunStats) {
        val e = prefs.edit()
        if (stats.syncedAt > 0) e.putLong("lastSyncAt", stats.syncedAt)
        e.putBoolean("rulesError", stats.rulesError).putInt("failedPerm", stats.failedPermanently)
            .putBoolean("pausedByServer", stats.pausedByServer)
            .putInt("waiting", stats.waiting)
            .putInt("tooLong", stats.tooLong)
            .putInt("sk_personal", stats.skipped.personal)
            .putInt("sk_otp", stats.skipped.otp)
            .putInt("sk_promo", stats.skipped.promo)
            .putInt("sk_unknown", stats.skipped.unknownSender)
            .apply()
    }

    /** Forgets the token and all local state. */
    fun clearAll(noticeAfter: String? = null) {
        disconnecting = true // tells a run in flight to stop at its next check
        try {
            // same lock as a sync run: a worker cannot recreate state after the wipe
            SyncEngine.LOCK.withLock {
                prefs.edit().clear().commit()
                try { db.close() } catch (e: Exception) { }
                context.deleteDatabase(DbHelper.NAME)
                configFile.delete()
                try {
                    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
                } catch (e: Exception) { }
                if (noticeAfter != null) notice = noticeAfter
            }
        } finally {
            disconnecting = false
        }
    }

    private fun cv(vararg pairs: Pair<String, String>) = ContentValues().apply { pairs.forEach { (k, v) -> put(k, v) } }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val (iv, ct) = stored.split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private class DbHelper(context: Context) : SQLiteOpenHelper(context, NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sent (fp TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE rejected (fp TEXT PRIMARY KEY, sig TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

        companion object { const val NAME = "sync.db" }
    }

    companion object {
        private const val KEY_ALIAS = "sms_sync_token"
        @Volatile var disconnecting = false
    }
}
