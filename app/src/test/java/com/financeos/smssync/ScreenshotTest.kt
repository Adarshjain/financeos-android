package com.financeos.smssync

import android.Manifest
import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.security.Provider
import java.security.Security
import java.util.Locale
import java.util.TimeZone

/**
 * Renders MainActivity to PNG files (real pixels, native graphics) in light and dark at 360x800dp.
 * Output folder: system property shotsDir. Only invented values are used here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val outDir = File(System.getProperty("shotsDir") ?: "build/shots").apply { mkdirs() }

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        installFakeKeystore()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test fun connectEmpty() = shoot("connect-empty") { }

    @Test fun connectWithError() = shoot("connect-error") { activity ->
        activity.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.siteInput).setText("https://example.test")
        activity.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.codeInput).setText("123456")
        activity.findViewById<View>(R.id.connectError).let {
            (it as android.widget.TextView).setText(R.string.error_code_rejected)
            it.visibility = View.VISIBLE
        }
    }

    @Test fun permission() {
        connected()
        shoot("permission") { }
    }

    @Test fun statusNormal() {
        connected()
        grantSms()
        seedRun(skipped = Skipped(personal = 4, otp = 10, promo = 20, unknownSender = 3), waiting = 0)
        shoot("status-normal") { }
    }

    @Test fun statusBusy() {
        connected()
        grantSms()
        val store = LocalStore(context)
        store.pausedLocally = true
        store.notice = "Syncing"
        seedRun(skipped = Skipped(personal = 4, otp = 10, promo = 20, unknownSender = 3), waiting = 5, tooLong = 2, failed = 1)
        shadowOf(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).setBackgroundRestricted(true)
        shoot("status-busy") { }
    }

    @Test fun statusRulesError() {
        connected()
        grantSms()
        context.getSharedPreferences("state", Context.MODE_PRIVATE).edit().putBoolean("rulesError", true).commit()
        seedRun(skipped = Skipped(1, 2, 3, 4), waiting = 0)
        shoot("status-rules-error") { }
    }

    private fun grantSms() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
    }

    private fun connected() {
        val store = LocalStore(context)
        store.site = "https://example.test"
        store.token = "invented-token"
        assertTrue(store.isConnected)
        store.saveConfig(TestConfig.JSON)
    }

    private fun seedRun(skipped: Skipped, waiting: Int, tooLong: Int = 0, failed: Int = 0) {
        val store = LocalStore(context)
        store.markSent((1..12).map { "fp$it" })
        store.saveRun(
            RunStats(
                syncedAt = System.currentTimeMillis(), skipped = skipped, tooLong = tooLong, waiting = waiting,
                pausedByServer = false, failedPermanently = failed,
            ),
        )
    }

    private fun shoot(name: String, prepare: (MainActivity) -> Unit) {
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("w360dp-h800dp-" + (if (night) "night" else "notnight") + "-xxhdpi")
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val activity = controller.get()
            prepare(activity)
            org.robolectric.shadows.ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS)
            val density = activity.resources.displayMetrics.density
            val decor = activity.window.decorView
            val w = (360 * density).toInt()
            val h = (800 * density).toInt()
            // pretend a 24dp status bar and a 48dp navigation bar
            val bars = Insets.of(0, (24 * density).toInt(), 0, (48 * density).toInt())
            ViewCompat.dispatchApplyWindowInsets(
                decor,
                WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), bars).build(),
            )
            decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, w, h)
            val suffix = if (night) "dark" else "light"
            save(draw(decor, w, h, bars), "$name-$suffix.png")

            // full content height, when it does not fit the screen
            val scroll = activity.findViewById<ViewGroup>(R.id.scroll)
            val content = scroll.getChildAt(0)
            if (content.height > h - bars.top - bars.bottom) {
                val fullH = content.height + bars.top + bars.bottom
                decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(fullH, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, w, fullH)
                save(draw(decor, w, fullH, bars), "$name-$suffix-full.png")
            }
            controller.pause().stop().destroy()
        }
    }

    private fun draw(decor: View, w: Int, h: Int, bars: Insets): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        decor.draw(canvas)
        val p = Paint().apply { color = Color.argb(70, 128, 128, 128) }
        canvas.drawRect(0f, 0f, w.toFloat(), bars.top.toFloat(), p) // status bar area
        canvas.drawRect(0f, (h - bars.bottom).toFloat(), w.toFloat(), h.toFloat(), p) // navigation bar area
        return bmp
    }

    private fun save(bmp: Bitmap, file: String) {
        FileOutputStream(File(outDir, file)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The JVM has no Android Keystore; a plain in-memory AES one with the same provider name stands in for it. */
    private fun installFakeKeystore() {
        if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(FakeKeystoreProvider())
    }
}

private class FakeKeystoreProvider : Provider("AndroidKeyStore", 1.0, "in-memory test keystore") {
    init {
        put("KeyStore.AndroidKeyStore", FakeKeyStoreSpi::class.java.name)
        put("KeyGenerator.AES", FakeKeyGeneratorSpi::class.java.name)
    }
}

class FakeKeyStoreSpi : java.security.KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): java.security.Key? = FakeKeys.map[alias]
    override fun engineGetCertificateChain(alias: String?) = null
    override fun engineGetCertificate(alias: String?) = null
    override fun engineGetCreationDate(alias: String?) = null
    override fun engineSetKeyEntry(alias: String?, key: java.security.Key?, password: CharArray?, chain: Array<out java.security.cert.Certificate>?) {}
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out java.security.cert.Certificate>?) {}
    override fun engineSetCertificateEntry(alias: String?, cert: java.security.cert.Certificate?) {}
    override fun engineDeleteEntry(alias: String) { FakeKeys.map.remove(alias) }
    override fun engineAliases() = java.util.Collections.enumeration(FakeKeys.map.keys)
    override fun engineContainsAlias(alias: String) = FakeKeys.map.containsKey(alias)
    override fun engineSize() = FakeKeys.map.size
    override fun engineIsKeyEntry(alias: String) = FakeKeys.map.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String?) = false
    override fun engineGetCertificateAlias(cert: java.security.cert.Certificate?) = null
    override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) {}
    override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) {}
}

class FakeKeyGeneratorSpi : javax.crypto.KeyGeneratorSpi() {
    private var alias: String? = null
    override fun engineInit(random: java.security.SecureRandom?) {}
    override fun engineInit(params: java.security.spec.AlgorithmParameterSpec?, random: java.security.SecureRandom?) {
        alias = (params as? android.security.keystore.KeyGenParameterSpec)?.keystoreAlias
    }
    override fun engineInit(keysize: Int, random: java.security.SecureRandom?) {}
    override fun engineGenerateKey(): javax.crypto.SecretKey {
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        alias?.let { FakeKeys.map[it] = key }
        return key
    }
}

object FakeKeys { val map = java.util.concurrent.ConcurrentHashMap<String, javax.crypto.SecretKey>() }
