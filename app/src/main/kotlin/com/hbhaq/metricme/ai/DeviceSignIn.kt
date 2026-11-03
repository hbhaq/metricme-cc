package com.hbhaq.metricme.ai

import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SignInCode(val code: String, val url: String)

/** No redirect activity is involved: the browser approves a short-lived code. */
internal class DeviceSignIn(
    private val post: suspend (String, Map<String, String>) -> JSONObject = ::postForm,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    suspend fun authorize(settings: ConnectionSettings, show: (SignInCode) -> Unit): AuthState {
        val issuer = settings.issuer.trimEnd('/')
        val code = post("$issuer/oauth/device/code", mapOf(
            "client_id" to settings.clientId, "audience" to settings.audience,
            "scope" to "openid profile offline_access"
        ))
        check(!code.has("error")) { "Code sign-in is unavailable. Try browser sign-in or contact support." }
        val verification = Uri.parse(code.getString("verification_uri_complete"))
        require(verification.scheme == "https" && verification.host == Uri.parse(issuer).host && verification.userInfo == null) {
            "The sign-in service returned an unexpected verification address."
        }
        val deviceCode = code.getString("device_code")
        val deadline = now() + code.getLong("expires_in").coerceIn(1, 1800) * 1000
        var interval = code.optLong("interval", 5).coerceAtLeast(5) * 1000
        show(SignInCode(code.getString("user_code"), verification.toString()))
        val config = AuthorizationServiceConfiguration(Uri.parse("$issuer/authorize"), Uri.parse("$issuer/oauth/token"))
        val request = TokenRequest.Builder(config, settings.clientId)
            .setGrantType("urn:ietf:params:oauth:grant-type:device_code").build()
        while (now() < deadline) {
            pause(interval)
            check(now() < deadline) { "That code expired. Get a new sign-in code." }
            val result = post("$issuer/oauth/token", mapOf(
                "grant_type" to request.grantType, "client_id" to settings.clientId, "device_code" to deviceCode
            ))
            when (result.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5000
                "expired_token" -> error("That code expired. Get a new sign-in code.")
                "access_denied" -> error("Sign-in was declined. You can try again.")
                "" -> {
                    require(result.optString("access_token").isNotBlank() && result.optString("id_token").isNotBlank()) {
                        "The sign-in response was incomplete. Please try again."
                    }
                    return AuthState(config).apply { update(TokenResponse.Builder(request).fromResponseJson(result).build(), null) }
                }
                else -> error("Could not finish code sign-in. Please try again.")
            }
        }
        error("That code expired. Get a new sign-in code.")
    }
}

private suspend fun postForm(address: String, fields: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
    val connection = URL(address).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = fields.entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val input = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        check(input != null) { "Sign-in service unavailable. Please try again." }
        val text = input.bufferedReader().use { it.readText().also { value -> require(value.length <= 65536) } }
        JSONObject(text)
    } finally { connection.disconnect() }
}
