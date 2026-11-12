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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import android.os.CancellationSignal
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val scroll = findViewById<View>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        store = LocalStore(this)

        findViewById<Button>(R.id.connectButton).setOnClickListener { signInWithPassword() }
        findViewById<Button>(R.id.googleButton).setOnClickListener { signInWithGoogle() }
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
            showNotice(getString(R.string.notice_syncing))
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
            val dialog = MaterialAlertDialogBuilder(this)
                .setMessage(R.string.dialog_disconnect_message)
                .setPositiveButton(R.string.button_disconnect) { _, _ ->
                    val site = store.site
                    val token = store.token
                    SyncRunner.cancelAll(this)
                    thread {
                        if (site != null && token != null) HttpApi(site, token).revoke()
                        store.clearAll()
                        runOnUiThread { render() }
                    }
                }
                .setNegativeButton(R.string.button_cancel, null)
                .show()
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setTextColor(ContextCompat.getColor(this, R.color.ds_danger))
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
        if (notice != null) showNotice(notice) else hideNotice()
        if (notice != null && !store.isConnected) store.notice = null // shown once

        connectPanel.visibility = View.GONE
        permissionPanel.visibility = View.GONE
        statusPanel.visibility = View.GONE
        findViewById<TextView>(R.id.screenTitle).setText(
            when {
                !store.isConnected -> R.string.title_sign_in
                !hasSmsPermission() -> R.string.title_permission
                else -> R.string.title_status
            },
        )
        when {
            !store.isConnected -> {
                connectPanel.visibility = View.VISIBLE
                val hasGoogle = SignInConfig.googleClientId.isNotBlank()
                findViewById<View>(R.id.googleButton).visibility = if (hasGoogle) View.VISIBLE else View.GONE
                findViewById<View>(R.id.orDivider).visibility = if (hasGoogle) View.VISIBLE else View.GONE
                findViewById<View>(R.id.debugBlock).visibility = if (SignInConfig.showSiteField) View.VISIBLE else View.GONE
                if (SignInConfig.showSiteField && findViewById<EditText>(R.id.siteInput).text.isEmpty()) {
                    findViewById<EditText>(R.id.siteInput).setText(store.site ?: BuildConfig.SITE_URL)
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
        val last = if (store.lastSyncAt == 0L) getString(R.string.value_never)
        else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(store.lastSyncAt))
        val stats = findViewById<LinearLayout>(R.id.statsContainer)
        stats.removeAllViews()
        addRow(stats, R.layout.row_stat, getString(R.string.row_last_sync), last)
        addRow(stats, R.layout.row_stat, getString(R.string.row_accounts_on), "${cfg?.lines?.size ?: 0}")
        addRow(stats, R.layout.row_stat, getString(R.string.row_messages_sent), "${store.sentCount()}")
        addRow(stats, R.layout.row_stat, getString(R.string.row_waiting), "${store.waiting}")
        val skips = findViewById<LinearLayout>(R.id.skipContainer)
        skips.removeAllViews()
        addRow(skips, R.layout.row_skip, getString(R.string.skip_personal), "${sk.personal}")
        addRow(skips, R.layout.row_skip, getString(R.string.skip_otp), "${sk.otp}")
        addRow(skips, R.layout.row_skip, getString(R.string.skip_promo), "${sk.promo}")
        addRow(skips, R.layout.row_skip, getString(R.string.skip_unknown_sender), "${sk.unknownSender}")
        if (store.tooLong > 0) addRow(skips, R.layout.row_skip, getString(R.string.skip_too_long), "${store.tooLong}")
        if (store.failedPermanently > 0) addRow(skips, R.layout.row_skip, getString(R.string.skip_failed), "${store.failedPermanently}")

        val banner = findViewById<TextView>(R.id.pausedBanner)
        val bannerCard = findViewById<MaterialCardView>(R.id.pausedCard)
        when {
            store.rulesError -> showBanner(bannerCard, banner, R.string.banner_rules_error, danger = true)
            store.pausedLocally -> showBanner(bannerCard, banner, R.string.banner_paused_phone, danger = false)
            store.pausedByServer || cfg?.paused == true -> showBanner(bannerCard, banner, R.string.banner_paused_web, danger = false)
            else -> bannerCard.visibility = View.GONE
        }
        findViewById<Button>(R.id.pauseButton).setText(if (store.pausedLocally) R.string.button_resume else R.string.button_pause)

        val restricted = Build.VERSION.SDK_INT >= 28 &&
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
        findViewById<View>(R.id.batteryCard).visibility = if (restricted) View.VISIBLE else View.GONE
    }

    private fun addRow(parent: LinearLayout, layout: Int, label: String, value: String) {
        val row = layoutInflater.inflate(layout, parent, false)
        row.findViewById<TextView>(R.id.rowLabel).text = label
        row.findViewById<TextView>(R.id.rowValue).text = value
        parent.addView(row)
    }

    private fun showBanner(card: MaterialCardView, text: TextView, message: Int, danger: Boolean) {
        text.setText(message)
        text.setTextColor(ContextCompat.getColor(this, if (danger) R.color.ds_danger_ink else R.color.ds_warning_ink))
        card.setCardBackgroundColor(ContextCompat.getColor(this, if (danger) R.color.ds_danger_tint else R.color.ds_warning_tint))
        card.visibility = View.VISIBLE
    }

    private fun showNotice(text: String) {
        findViewById<View>(R.id.noticeBlock).visibility = View.VISIBLE
        noticeView.text = text
        findViewById<LinearProgressIndicator>(R.id.syncProgress).visibility =
            if (text == getString(R.string.notice_syncing)) View.VISIBLE else View.GONE
    }

    private fun hideNotice() {
        findViewById<View>(R.id.noticeBlock).visibility = View.GONE
        noticeView.text = ""
    }

    /** The site to sign in to: the typed one in a debug build, the built-in one otherwise. Null when it is not acceptable. */
    private fun signInSite(): String? =
        if (SignInConfig.showSiteField) {
            SiteUrl.normalize(findViewById<EditText>(R.id.siteInput).text.toString(), BuildConfig.DEBUG)
        } else {
            SiteUrl.normalize(BuildConfig.SITE_URL, false)
        }

    private fun setSigningIn(busy: Boolean, google: Boolean = false) {
        val signIn = findViewById<Button>(R.id.connectButton)
        val googleButton = findViewById<Button>(R.id.googleButton)
        signIn.isEnabled = !busy
        googleButton.isEnabled = !busy
        findViewById<View>(R.id.emailInput).isEnabled = !busy
        findViewById<View>(R.id.passwordInput).isEnabled = !busy
        signIn.setText(if (busy && !google) R.string.button_signing_in else R.string.button_sign_in)
        googleButton.setText(if (busy && google) R.string.button_google_busy else R.string.button_google)
    }

    private fun site(): String? {
        val site = signInSite()
        if (site == null) showConnectError(getString(if (BuildConfig.DEBUG) R.string.error_site_debug else R.string.error_site))
        return site
    }

    private fun signInWithPassword() {
        val email = findViewById<EditText>(R.id.emailInput).text.toString().trim()
        val passwordField = findViewById<EditText>(R.id.passwordInput)
        val password = passwordField.text.toString()
        passwordField.setText("")
        if (email.isEmpty() || password.isEmpty()) return showConnectError(getString(R.string.error_required))
        val site = site() ?: return
        setSigningIn(true)
        connectError.visibility = View.GONE
        thread {
            val outcome = callSignIn(site, google = false) { HttpApi(site, null).login(email, password, deviceName(), InstallId.get(applicationContext)) }
            runOnUiThread { finishSignIn(outcome) }
        }
    }

    private fun signInWithGoogle() {
        val clientId = SignInConfig.googleClientId
        if (clientId.isBlank()) return
        val site = site() ?: return
        findViewById<EditText>(R.id.passwordInput).setText("")
        setSigningIn(true, google = true)
        connectError.visibility = View.GONE
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        CredentialManager.create(this).getCredentialAsync(
            this, request, CancellationSignal(), ContextCompat.getMainExecutor(this),
            object : androidx.credentials.CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val idToken = idTokenOf(result)
                    if (idToken == null) {
                        setSigningIn(false)
                        return showConnectError(getString(R.string.error_google_phone))
                    }
                    thread {
                        val outcome = callSignIn(site, google = true) { HttpApi(site, null).loginWithGoogle(idToken, deviceName(), InstallId.get(applicationContext)) }
                        runOnUiThread { finishSignIn(outcome) }
                    }
                }

                override fun onError(e: GetCredentialException) {
                    setSigningIn(false)
                    if (e is GetCredentialCancellationException) return
                    showConnectError(getString(R.string.error_google_phone))
                }
            },
        )
    }

    private fun idTokenOf(result: GetCredentialResponse): String? {
        val credential = result.credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) return null
        return try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: GoogleIdTokenParsingException) {
            null
        }
    }

    private fun deviceName(): String = Build.MODEL ?: "Android phone"

    private class SignInOutcome(val site: String, val token: String?, val message: String?)

    /** Runs a sign-in call off the main thread and turns the answer into a token or a message. */
    private fun callSignIn(site: String, google: Boolean, call: () -> HttpApi.Response): SignInOutcome = try {
        val r = call()
        if (r.code in 200..299) {
            SignInOutcome(site, org.json.JSONObject(r.body).getString("token"), null)
        } else {
            val message = when (SignInFailures.forStatus(google, r.code)) {
                SignInFailure.WRONG_CREDENTIALS -> getString(R.string.error_wrong_credentials)
                SignInFailure.GOOGLE_REJECTED -> getString(R.string.error_google_rejected)
                SignInFailure.NO_ACCOUNT -> getString(R.string.error_no_account)
                SignInFailure.TOO_MANY -> getString(R.string.error_too_many)
                SignInFailure.GOOGLE_NOT_SET_UP -> getString(R.string.error_google_not_set_up)
                SignInFailure.SERVER -> getString(R.string.error_server, r.code)
            }
            SignInOutcome(site, null, message)
        }
    } catch (e: Exception) {
        SignInOutcome(site, null, getString(if (BuildConfig.DEBUG) R.string.error_unreachable_debug else R.string.error_unreachable))
    }

    private fun finishSignIn(outcome: SignInOutcome) {
        setSigningIn(false)
        findViewById<EditText>(R.id.passwordInput).setText("")
        if (outcome.token == null) return showConnectError(outcome.message ?: "")
        store.site = outcome.site
        store.token = outcome.token
        store.pausedLocally = false
        store.notice = null
        render()
        if (hasSmsPermission()) {
            SyncRunner.schedulePeriodic(this)
            SyncRunner.enqueueNow(this)
        }
    }

    private fun showConnectError(m: String) {
        connectError.text = m
        connectError.visibility = View.VISIBLE
    }
}
