package net.pfiers.osmfocus.view

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.getOrElse
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import net.openid.appauth.*
import net.pfiers.osmfocus.R
import net.pfiers.osmfocus.service.*
import net.pfiers.osmfocus.service.oauth.OsmAuthRepository
import net.pfiers.osmfocus.service.oauth.OsmAuthRepository.Companion.osmAuthRepository
import net.pfiers.osmfocus.service.util.createEmailIntent
import net.pfiers.osmfocus.service.util.div
import net.pfiers.osmfocus.view.support.EventReceiver
import net.pfiers.osmfocus.view.support.UncaughtExceptionHandler.Companion.uncaughtExceptionHandler
import net.pfiers.osmfocus.view.support.timberInit
import net.pfiers.osmfocus.viewmodel.support.*
import timber.log.Timber
import kotlin.time.ExperimentalTime

@ExperimentalTime
@Suppress("UnstableApiUsage")
class MainActivity : AppCompatActivity(), EventReceiver {
    private val authService by lazy { AuthorizationService(this@MainActivity) }
    private lateinit var osmAuthorizationResultLauncher: ActivityResultLauncher<Intent>
    private val oAuthScope = CoroutineScope(Job() + Dispatchers.IO)
    private var osmAuthorizationJob: CompletableJob? = null
    private var osmAuthRepo: OsmAuthRepository? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        timberInit()

        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler)

        setContentView(R.layout.activity_main)

        val osmAuthRepository = this.osmAuthRepository
        osmAuthRepo = osmAuthRepository

        osmAuthorizationResultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { activityResult ->
            oAuthScope.launch {
                Timber.d("Activity result received. Checking data...")

                val authResp: AuthorizationResponse =
                    authResponseFromActivityResult(activityResult).getOrElse { ex ->
                        Snackbar.make(
                            window.decorView.rootView,
                            ex.message,
                            Snackbar.LENGTH_LONG
                        ).show()
                        return@launch
                    }

                Timber.d("Auth response received, all checks passed. Getting authState...")

                val authState = osmAuthRepository.getAuthState()
                authState.update(authResp, null)

                val refreshTokenRequest = authResp.createTokenExchangeRequest()

                Timber.d("Performing token request...")
                authService.performTokenRequest(
                    refreshTokenRequest
                ) { refreshResp, refreshEx ->
                    if (refreshEx != null || refreshResp == null) {
                        val description = refreshEx?.errorDescription ?: "unknown error"
                        Snackbar.make(
                            window.decorView.rootView,
                            "Authentication failed: $description",
                            Snackbar.LENGTH_LONG
                        ).show()
                        return@performTokenRequest
                    }
                    authState.update(refreshResp, null)
                    osmAuthRepository.setAuthState(authState)
                    osmAuthorizationJob?.complete()
                }
            }
        }
    }

    override fun onDestroy() {
        authService.dispose()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is OpenUriEvent -> openUri(event.uri)
            is SendEmailEvent -> startActivity(
                createEmailIntent(
                    this,
                    cacheDir / "attachments",
                    event.address,
                    event.subject,
                    event.body,
                    event.attachments
                )
            )
            is RunWithOsmAccessTokenEvent -> oAuthScope.launch {
                val authState = osmAuthRepository.getAuthState()
                if (!authState.isAuthorized) {
                    if (!osmAuthorize(event.reason)) return@launch
                val authState = osmAuthRepo?.getAuthState()
                if (authState != null) {
                    if (!authState.isAuthorized) {
                        if (!osmAuthorize(event.reason)) return@launch
                    }
                }

                if (authState != null) {
                    authState.performActionWithFreshTokens(authService) { accessToken, _, ex ->
                        if (ex != null || accessToken == null) {
                            val description = ex?.errorDescription ?: "unknown error"
                            Snackbar.make(
                                window.decorView.rootView,
                                "Failed to refresh OSM access token: $description",
                                Snackbar.LENGTH_LONG
                            ).show()
                            return@performActionWithFreshTokens
                        }
                        event.action(accessToken)
                    }
                }
            }
            else -> Timber.w("Unhandled event: $event")
        }
    }

    /**
     * @return true if authorization launched, false if cancelled
     */
    private suspend fun osmAuthorize(@StringRes reason: Int): Boolean {
        val confirmJob = CompletableDeferred<Boolean>()
        lifecycleScope.launch {
            MaterialAlertDialogBuilder(this@MainActivity).apply {
                setTitle(R.string.osm_login_confirm_dialog_title)
                setMessage(getString(R.string.osm_login_confirm_dialog_message, getString(reason)))
                setPositiveButton(R.string.osm_login_confirm_dialog_log_in) { dialog, _ ->
                    dialog.dismiss()
                    confirmJob.complete(true)
                }
                setNegativeButton(R.string.osm_login_confirm_dialog_cancel) { dialog, _ ->
                    dialog.dismiss()
                    confirmJob.complete(false)
                }
            }.show()
        }
        if (!confirmJob.await()) return false
        if (osmAuthorizationJob == null) osmAuthorizationJob = Job()
        val authIntent = authService.getAuthorizationRequestIntent(
            osmAuthRepository.createAuthorizationRequest()
        )
        osmAuthorizationResultLauncher.launch(authIntent)
        osmAuthorizationJob!!.join()
        return true
    }

    private fun authResponseFromActivityResult(result: ActivityResult): Result<AuthorizationResponse, AuthResponseException> {
        val data = result.data
        if (result.resultCode == Activity.RESULT_CANCELED) {
            return Result.error(AuthResponseException("Authentication cancelled"))
        } else if (result.resultCode != Activity.RESULT_OK || data == null) {
            return Result.error(AuthResponseException("Authentication failed"))
        }
        val authResp = AuthorizationResponse.fromIntent(data)
        val authEx = AuthorizationException.fromIntent(data)
        if (authResp == null) {
            val description = authEx?.errorDescription ?: "unknown error"
            return Result.error(AuthResponseException("Authentication failed: $description"))
        }

        return Result.success(authResp)
    }

    private fun openUri(uri: Uri) = startActivity(Intent(Intent.ACTION_VIEW, uri))

    companion object {
        const val ARG_PREVIOUS_THROWABLE_INFO = "previous_throwable_info"
        const val EMAIL_ATTACHMENTS_URI_BASE =
            "content://net.pfiers.osmfocus.email_attachments_fileprovider"
        const val LOGGING_TAG = "net.pfiers.osmfocus"
    }

    private class AuthResponseException(override val message: String) : Exception()
}
