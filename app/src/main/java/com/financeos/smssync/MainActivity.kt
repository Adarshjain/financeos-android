package com.financeos.smssync

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var store: LocalStore

    private val connectPanel by lazy { findViewById<View>(R.id.connectPanel) }
    private val permissionPanel by lazy { findViewById<View>(R.id.permissionPanel) }
    private val statusPanel by lazy { findViewById<View>(R.id.statusPanel) }
    private val noticeView by lazy { findViewById<TextView>(R.id.notice) }
    private val connectError by lazy { findViewById<TextView>(R.id.connectError) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = LocalStore(this)

        findViewById<Button>(R.id.connectButton).setOnClickListener { connect() }
        findViewById<Button>(R.id.permissionButton).setOnClickListener {
            // once asked and no longer shown a prompt, the system will not ask again: send to app settings
            if (store.permissionAsked && !shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS)) {
                openAppSettings()
            } else {
                store.permissionAsked = true
                requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS), 1)
            }
        }
        findViewById<Button>(R.id.appSettingsButton).setOnClickListener { openAppSettings() }
        findViewById<Button>(R.id.syncButton).setOnClickListener {
            SyncRunner.enqueueNow(this)
            noticeView.text = "Syncing"
            noticeView.visibility = View.VISIBLE
        }
        findViewById<Button>(R.id.openSiteButton).setOnClickListener {
            val site = store.site ?: return@setOnClickListener
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$site/accounts/sms-sync")))
        }
        findViewById<Button>(R.id.pauseButton).setOnClickListener {
            store.pausedLocally = !store.pausedLocally
            render()
        }
        findViewById<Button>(R.id.disconnectButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("This phone stops syncing. If it cannot reach the site now, also remove it on the SMS sync page.")
                .setPositiveButton("Disconnect") { _, _ ->
                    val site = store.site
                    val token = store.token
                    SyncRunner.cancelAll(this)
                    thread {
                        if (site != null && token != null) HttpApi(site, token).revoke()
                        store.clearAll()
                        runOnUiThread { render() }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { openAppSettings() }
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(SyncRunner.ONE_OFF).observe(this) { infos ->
            if (infos.any { it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED }) render()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        if (store.isConnected && hasSmsPermission()) {
            SyncRunner.schedulePeriodic(this)
            // opening the app syncs only when the last successful sync is older than 5 minutes
            if (System.currentTimeMillis() - store.lastSyncAt > 5 * 60_000L) SyncRunner.enqueueNow(this)
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasSmsPermission()) {
            SyncRunner.schedulePeriodic(this)
            SyncRunner.enqueueNow(this)
        }
        render()
    }

    private fun hasSmsPermission() =
        checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        val notice = store.notice
        noticeView.visibility = if (notice != null) View.VISIBLE else View.GONE
        noticeView.text = notice ?: ""
        if (notice != null && !store.isConnected) store.notice = null // shown once

        connectPanel.visibility = View.GONE
        permissionPanel.visibility = View.GONE
        statusPanel.visibility = View.GONE
        when {
            !store.isConnected -> {
                connectPanel.visibility = View.VISIBLE
                if (findViewById<EditText>(R.id.siteInput).text.isEmpty()) {
                    findViewById<EditText>(R.id.siteInput).setText(store.site ?: "")
                }
            }
            !hasSmsPermission() -> permissionPanel.visibility = View.VISIBLE
            else -> {
                statusPanel.visibility = View.VISIBLE
                renderStatus()
            }
        }
    }

    private fun renderStatus() {
        val cfg = store.cachedConfig()
        val sk = store.skipped
        val last = if (store.lastSyncAt == 0L) "never"
        else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(store.lastSyncAt))
        findViewById<TextView>(R.id.statusText).text = listOf(
            "Last sync: $last",
            "Accounts on: ${cfg?.lines?.size ?: 0}",
            "Messages sent: ${store.sentCount()}",
            "Waiting to send: ${store.waiting}",
            "Skipped: personal ${sk.personal}, OTP ${sk.otp}, promo ${sk.promo}, unknown sender ${sk.unknownSender}" +
                (if (store.tooLong > 0) ", too long ${store.tooLong}" else "") +
                (if (store.failedPermanently > 0) ", could not send ${store.failedPermanently}" else ""),
        ).joinToString("\n")

        val banner = findViewById<TextView>(R.id.pausedBanner)
        when {
            store.rulesError -> { banner.text = "Could not read the rules from the site"; banner.visibility = View.VISIBLE }
            store.pausedLocally -> { banner.text = "Paused on this phone"; banner.visibility = View.VISIBLE }
            store.pausedByServer || cfg?.paused == true -> { banner.text = "Paused from the web app"; banner.visibility = View.VISIBLE }
            else -> banner.visibility = View.GONE
        }
        findViewById<Button>(R.id.pauseButton).text = if (store.pausedLocally) "Resume" else "Pause"

        val restricted = Build.VERSION.SDK_INT >= 28 &&
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
        val vis = if (restricted) View.VISIBLE else View.GONE
        findViewById<View>(R.id.batteryText).visibility = vis
        findViewById<View>(R.id.batteryButton).visibility = vis
    }

    private fun connect() {
        val site = SiteUrl.normalize(findViewById<EditText>(R.id.siteInput).text.toString(), BuildConfig.DEBUG)
        val code = findViewById<EditText>(R.id.codeInput).text.toString().trim()
        if (site == null) return showConnectError(
            if (BuildConfig.DEBUG) "Enter the site address, for example https://example.test"
            else "Enter the site address. It must start with https://",
        )
        if (code.isEmpty()) return showConnectError("Enter the code from the web app")
        val button = findViewById<Button>(R.id.connectButton)
        button.isEnabled = false
        connectError.visibility = View.GONE
        thread {
            val message: String? = try {
                val r = HttpApi(site, null).pair(code, Build.MODEL ?: "Android phone")
                when {
                    r.code in 200..299 -> {
                        val token = org.json.JSONObject(r.body).getString("token")
                        store.site = site
                        store.token = token
                        store.pausedLocally = false
                        store.notice = null
                        null
                    }
                    r.code == 429 -> "Too many tries, wait 15 minutes"
                    r.code == 400 || r.code == 401 || r.code == 404 -> "That code did not work. Check it or make a new one in the web app."
                    else -> "The server could not pair this phone (error ${r.code}). Try again later."
                }
            } catch (e: Exception) {
                "Could not reach the site. Check the address and your connection."
            }
            runOnUiThread {
                button.isEnabled = true
                if (message != null) showConnectError(message) else {
                    findViewById<EditText>(R.id.codeInput).setText("")
                    render()
                    if (hasSmsPermission()) {
                        SyncRunner.schedulePeriodic(this)
                        SyncRunner.enqueueNow(this)
                    }
                }
            }
        }
    }

    private fun showConnectError(m: String) {
        connectError.text = m
        connectError.visibility = View.VISIBLE
    }
}
