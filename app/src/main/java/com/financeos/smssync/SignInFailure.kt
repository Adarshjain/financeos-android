package com.financeos.smssync

/** What went wrong on a sign-in call; the screen maps each to its own words. */
enum class SignInFailure { WRONG_CREDENTIALS, GOOGLE_REJECTED, NO_ACCOUNT, TOO_MANY, GOOGLE_NOT_SET_UP, SERVER }

object SignInFailures {
    /** Maps the HTTP status of a failed sign-in call. [google] is true for the Google endpoint. */
    fun forStatus(google: Boolean, status: Int): SignInFailure = when {
        status == 429 -> SignInFailure.TOO_MANY
        status == 401 -> if (google) SignInFailure.GOOGLE_REJECTED else SignInFailure.WRONG_CREDENTIALS
        google && status == 404 -> SignInFailure.NO_ACCOUNT
        google && status == 503 -> SignInFailure.GOOGLE_NOT_SET_UP
        else -> SignInFailure.SERVER
    }
}

/** Sign-in settings that tests can override; production reads the build config. */
object SignInConfig {
    @JvmStatic var googleClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID
    @JvmStatic var showSiteField: Boolean = BuildConfig.DEBUG
}
