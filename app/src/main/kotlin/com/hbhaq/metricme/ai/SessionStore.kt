package com.hbhaq.metricme.ai

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.*
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ConnectionSettings(val backend:String="",val issuer:String="",val clientId:String="",val audience:String="") {
    val complete get()=listOf(backend,issuer,clientId,audience).all(String::isNotBlank)
}
@Singleton class SessionStore @Inject constructor(@param:ApplicationContext private val context:Context) {
    private val prefs=context.getSharedPreferences("ai-connection",Context.MODE_PRIVATE)
    private val mutex=Mutex()
    val changes=MutableStateFlow(0)
    val message=MutableStateFlow<String?>(null)
    fun settings()=ConnectionSettings(prefs.getString("backend","").orEmpty(),prefs.getString("issuer","").orEmpty(),prefs.getString("client","").orEmpty(),prefs.getString("audience","").orEmpty())
    fun configure(value:ConnectionSettings) {
        listOf(value.backend,value.issuer).forEach { val uri=java.net.URI(it); require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.query==null && uri.fragment==null) { "Backend and issuer must be HTTPS URLs without credentials, query or fragment." } }
        require(value.clientId.isNotBlank() && value.clientId.length<=300 && value.audience.isNotBlank() && value.audience.length<=500)
        if(value!=settings()) signOut()
        prefs.edit().putString("backend",value.backend.trimEnd('/')+"/").putString("issuer",value.issuer.trimEnd('/')).putString("client",value.clientId).putString("audience",value.audience).apply(); changes.value++
    }
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("metricme-session",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder("metricme-session",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey()
    }
    private fun read():AuthState?=runCatching {
        val encoded=prefs.getString("session",null)?:return null
        val bytes=Base64.decode(encoded,Base64.NO_WRAP); require(bytes.size>12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        AuthState.jsonDeserialize(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
    }.getOrNull()
    fun store(state:AuthState) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key())
        val bytes=cipher.iv+cipher.doFinal(state.jsonSerializeString().toByteArray())
        prefs.edit().putString("session",Base64.encodeToString(bytes,Base64.NO_WRAP)).apply(); message.value=null; changes.value++
    }
    fun signOut() { prefs.edit().remove("session").apply(); changes.value++; message.value="Signed out on this device. Pending uploads wait for sign-in. Provider browser sign-in may remain active." }
    fun signedIn()=read()?.isAuthorized==true
    fun binding():String?=runCatching {
        val token=read()?.idToken?:return null
        val payload=JSONObject(String(Base64.decode(token.split('.')[1],Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
        // This is only a local account-change guard. The server validates the access token.
        val raw="${settings().issuer}|${payload.getString("sub")}|${settings().clientId}"
        java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()
    suspend fun accessToken(expected:String):String=mutex.withLock {
        check(binding()==expected) { "Sign in with the account that queued this draft." }
        val state=read()?:error("Sign in to continue.")
        val service=AuthorizationService(context)
        try { suspendCancellableCoroutine { continuation -> state.performActionWithFreshTokens(service) { access,_,exception ->
            if(continuation.isActive) { if(exception!=null || access.isNullOrBlank()) continuation.resumeWithException(IllegalStateException("Sign in again to resume uploads.")) else { store(state); continuation.resume(access) } }
        } } } finally { service.dispose() }
    }
    fun signIn() {
        val s=settings(); require(s.complete) { "Configure your connection first." }
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(s.issuer)) { config,exception ->
            if(config==null || exception!=null) { message.value="Could not load secure sign-in. Check your issuer and connection."; return@fetchFromIssuer }
            val request=AuthorizationRequest.Builder(config,s.clientId,ResponseTypeValues.CODE,Uri.parse("com.hbhaq.metricme:/oauth2redirect")).setScopes("openid","profile","offline_access").setAdditionalParameters(mapOf("audience" to s.audience)).build()
            val result=PendingIntent.getActivity(context,0,Intent(context,AuthCompleteActivity::class.java),PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val service=AuthorizationService(context)
            try { service.performAuthorizationRequest(request,result,result) } catch(_: Exception) { message.value="No browser is available for sign-in." } finally { service.dispose() }
        }
    }
    fun complete(intent:Intent,done:()->Unit) {
        val response=AuthorizationResponse.fromIntent(intent); val exception=AuthorizationException.fromIntent(intent)
        if(response==null || exception!=null) { message.value="Sign-in was cancelled or failed. Local logging is still available."; done(); return }
        val state=AuthState(response,null); val service=AuthorizationService(context)
        service.performTokenRequest(response.createTokenExchangeRequest()) { token,error ->
            try { state.update(token,error); if(token!=null && error==null) store(state) else message.value="Could not complete sign-in. Try again." } finally { service.dispose(); done() }
        }
    }
}
@AndroidEntryPoint class AuthCompleteActivity:ComponentActivity() {
    @Inject lateinit var sessions:SessionStore
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); sessions.complete(intent) { finish() } }
}
