package com.example.data.drive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.IntentSenderRequest
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface DriveAuthOutcome {
    data class Authorized(val accessToken: String, val email: String?) : DriveAuthOutcome
    data class NeedsUserConsent(val intentSenderRequest: IntentSenderRequest) : DriveAuthOutcome
    data class Failed(val message: String) : DriveAuthOutcome
}

data class AppSigningDiagnostics(
    val packageName: String,
    val sha1Fingerprint: String?
)

class GoogleDriveAuthManager(private val context: Context) {

    private val httpClient = OkHttpClient()

    companion object {
        private val DRIVE_SCOPES = listOf(
            Scope("openid"),
            Scope("https://www.googleapis.com/auth/drive"),
            Scope("https://www.googleapis.com/auth/userinfo.email")
        )

        fun readSigningDiagnostics(context: Context): AppSigningDiagnostics {
            val pkg = context.packageName
            val sha1 = runCatching {
                val pm = context.packageManager
                val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo
                        ?.apkContentsSigners
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
                }
                val certBytes = signatures?.firstOrNull()?.toByteArray() ?: return@runCatching null
                MessageDigest.getInstance("SHA-1")
                    .digest(certBytes)
                    .joinToString(":") { "%02X".format(it) }
            }.getOrNull()
            return AppSigningDiagnostics(packageName = pkg, sha1Fingerprint = sha1)
        }
    }

    fun getAppSigningDiagnostics(): AppSigningDiagnostics = readSigningDiagnostics(context)

    suspend fun authorizeDrive(activity: Activity? = null): DriveAuthOutcome {
        return try {
            val authClient = if (activity != null) {
                Identity.getAuthorizationClient(activity)
            } else {
                Identity.getAuthorizationClient(context)
            }
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(DRIVE_SCOPES)
                .build()

            val result: AuthorizationResult = suspendCancellableCoroutine { cont ->
                authClient.authorize(request)
                    .addOnSuccessListener { res -> cont.resume(res) }
                    .addOnFailureListener { err -> cont.resumeWithException(err) }
            }

            if (result.hasResolution()) {
                val pendingIntent = result.pendingIntent
                    ?: return DriveAuthOutcome.Failed("Google authorization requires user consent, but no consent intent was provided.")
                DriveAuthOutcome.NeedsUserConsent(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            } else {
                val token = result.accessToken
                    ?: return DriveAuthOutcome.Failed("Google authorization succeeded without an access token.")
                val email = fetchUserEmail(token)
                DriveAuthOutcome.Authorized(accessToken = token, email = email)
            }
        } catch (e: ApiException) {
            DriveAuthOutcome.Failed(mapGoogleAuthError(e))
        } catch (e: Exception) {
            DriveAuthOutcome.Failed(e.message ?: "Google Drive authorization failed.")
        }
    }

    suspend fun handleConsentResult(data: Intent?): DriveAuthOutcome {
        return try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            val token = result.accessToken
                ?: return DriveAuthOutcome.Failed("Drive authorization was cancelled or returned no token.")
            val email = fetchUserEmail(token)
            DriveAuthOutcome.Authorized(accessToken = token, email = email)
        } catch (e: ApiException) {
            DriveAuthOutcome.Failed(mapGoogleAuthError(e))
        } catch (e: Exception) {
            DriveAuthOutcome.Failed(e.message ?: "Failed to complete Google Drive sign-in.")
        }
    }

    suspend fun fetchFreshAccessTokenSilently(): Result<String> = runCatching {
        val outcome = authorizeDrive(null)
        when (outcome) {
            is DriveAuthOutcome.Authorized -> outcome.accessToken
            is DriveAuthOutcome.NeedsUserConsent ->
                throw IllegalStateException("Google Drive session expired. Please reconnect in the Sync tab.")
            is DriveAuthOutcome.Failed ->
                throw IllegalStateException(outcome.message)
        }
    }

    suspend fun signOut() {
        runCatching {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }
    }

    private suspend fun fetchUserEmail(accessToken: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://www.googleapis.com/oauth2/v3/userinfo")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val json = JSONObject(resp.body?.string().orEmpty())
                json.optString("email").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    internal fun mapGoogleAuthError(e: ApiException): String {
        val code = e.statusCode
        val raw = e.message.orEmpty()
        val isUnregistered = code == 10 ||
            raw.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true) ||
            raw.contains("DEVELOPER_ERROR", ignoreCase = true)
        return when {
            isUnregistered ->
                "This APK's package name and SHA-1 are not registered on Google Cloud Console yet " +
                    "(Code $code: UNREGISTERED_ON_API_CONSOLE). Use the Package Name & SHA-1 below to create an Android OAuth 2.0 Client ID."
            code == 12500 ->
                "Google Drive sign-in failed (Code 12500). Verify that Google Drive API is enabled, the OAuth consent screen is configured, and your Google account is listed under Test users."
            code == 12501 -> "Google Drive sign-in was cancelled."
            code == 7 -> "Network unavailable while contacting Google Identity."
            else -> "Google sign-in error (code $code): ${e.localizedMessage ?: "Unknown error"}"
        }
    }
}
