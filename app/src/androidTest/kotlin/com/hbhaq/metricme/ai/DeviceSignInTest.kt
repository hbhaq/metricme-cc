package com.hbhaq.metricme.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceSignInTest {
    private fun code()=JSONObject("""{"device_code":"private-device-code","user_code":"ABCD-EFGH","verification_uri_complete":"https://dev-sjboqyjugly8goce.us.auth0.com/activate?user_code=ABCD-EFGH","expires_in":60,"interval":5}""")

    @Test fun pendingAndSlowDownRespectIntervalsAndRetainRefreshToken()=runBlocking {
        var clock=0L
        val waits=mutableListOf<Long>()
        var poll=0
        val flow=DeviceSignIn(post={ address,fields ->
            if(address.endsWith("/device/code")) {
                assertEquals(ConnectionSettings.MetricMe.audience,fields["audience"])
                code()
            } else {
                assertEquals("private-device-code",fields["device_code"])
                when(poll++) {
                    0 -> JSONObject("""{"error":"authorization_pending"}""")
                    1 -> JSONObject("""{"error":"slow_down"}""")
                    else -> JSONObject("""{"access_token":"access","id_token":"id","refresh_token":"refresh","token_type":"Bearer","expires_in":3600}""")
                }
            }
        },pause={ waits+=it; clock+=it },now={ clock })
        var displayed:SignInCode?=null
        val state=flow.authorize(ConnectionSettings.MetricMe) { displayed=it }
        assertEquals("ABCD-EFGH",displayed!!.code)
        assertEquals(listOf(5000L,5000L,10000L),waits)
        assertTrue(state.isAuthorized)
        val restored=net.openid.appauth.AuthState.jsonDeserialize(state.jsonSerializeString())
        assertEquals("refresh",restored.refreshToken)
        assertEquals(ConnectionSettings.MetricMe.clientId,restored.createTokenRefreshRequest().clientId)
    }

    @Test fun deniedCodeStopsPolling()=runBlocking {
        var calls=0
        val flow=DeviceSignIn(post={ _,_ -> if(calls++==0) code() else JSONObject("""{"error":"access_denied"}""") },pause={},now={ 0L })
        try { flow.authorize(ConnectionSettings.MetricMe) {}; fail("Expected declined sign-in") }
        catch(e:IllegalStateException) { assertTrue(e.message!!.contains("declined")) }
        assertEquals(2,calls)
    }

    @Test fun expiredCodeAndCancellationDoNotRequestTokens()=runBlocking {
        var clock=0L; var calls=0
        val flow=DeviceSignIn(post={ _,_ -> calls++; code() },pause={ clock=61000 },now={ clock })
        try { flow.authorize(ConnectionSettings.MetricMe) {}; fail("Expected expiry") }
        catch(e:IllegalStateException) { assertTrue(e.message!!.contains("expired")) }
        assertEquals(1,calls)
        val cancelled=DeviceSignIn(post={ _,_ -> code() },pause={ throw CancellationException() })
        try { cancelled.authorize(ConnectionSettings.MetricMe) {}; fail("Expected cancellation") }
        catch(_:CancellationException) { }
    }

    @Test fun verificationLinkMustStayOnConfiguredIssuer()=runBlocking {
        val flow=DeviceSignIn(post={ _,_ -> code().put("verification_uri_complete","https://other.example/activate") })
        try { flow.authorize(ConnectionSettings.MetricMe) {}; fail("Expected issuer check") }
        catch(_:IllegalArgumentException) { }
    }
}
