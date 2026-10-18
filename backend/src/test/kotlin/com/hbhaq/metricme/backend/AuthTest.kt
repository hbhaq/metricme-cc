package com.hbhaq.metricme.backend

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.junit.Test
import org.junit.Assert.*
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date

class AuthTest {
    @Test fun issuerAudienceSignatureExpiryAndSubjectAreRequired() {
        val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val public=pair.public as RSAPublicKey; val private=pair.private as RSAPrivateKey
        fun unsigned(value:java.math.BigInteger)=Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray().let { if(it[0]==0.toByte()) it.copyOfRange(1,it.size) else it })
        val jwk=Jwk.fromValues(mapOf("kid" to "test","kty" to "RSA","alg" to "RS256","n" to unsigned(public.modulus),"e" to unsigned(public.publicExponent)))
        val verify=verifier(ServerConfig(null,null,"https://issuer.test/","metricme","https://issuer.test/jwks"),JwkProvider { jwk })
        fun token(issuer:String="https://issuer.test/",audience:String="metricme",expiry:Instant?=Instant.now().plusSeconds(120),subject:String?="user")=JWT.create().withKeyId("test").withIssuer(issuer).withAudience(audience).apply { if(expiry!=null) withExpiresAt(Date.from(expiry)); if(subject!=null) withSubject(subject) }.sign(Algorithm.RSA256(public,private))
        assertNotNull(verify(token())); assertEquals(64,verify(token())!!.length)
        assertNull(verify(token(issuer="https://other.test/"))); assertNull(verify(token(audience="other"))); assertNull(verify(token(expiry=Instant.now().minusSeconds(120)))); assertNull(verify(token(expiry=null))); assertNull(verify(token(subject=null)))
        val wrong=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val forged=JWT.create().withKeyId("test").withIssuer("https://issuer.test/").withAudience("metricme").withSubject("user").withExpiresAt(Date.from(Instant.now().plusSeconds(120))).sign(Algorithm.RSA256(wrong.public as RSAPublicKey,wrong.private as RSAPrivateKey))
        assertNull(verify(forged)); assertNull(verify("not-a-token"))
    }
    @Test fun unconfiguredAuthenticationRejectsEveryToken() { assertNull(verifier(ServerConfig(null,null,null,null,null))("static-shared-secret")) }
}
