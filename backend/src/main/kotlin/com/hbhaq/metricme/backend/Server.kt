package com.hbhaq.metricme.backend

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.RSAKeyProvider
import com.hbhaq.metricme.contracts.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.readByteArray
import java.net.URI
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ServerConfig(val databaseUrl:String?,val key:String?,val issuer:String?,val audience:String?,val jwks:String?,val visionModel:String="gpt-4.1-mini",val transcriptionModel:String="gpt-4o-mini-transcribe") {
    val authConfigured get()=!issuer.isNullOrBlank() && !audience.isNullOrBlank() && !jwks.isNullOrBlank()
    companion object { fun environment():ServerConfig {
        require(System.getenv("DEV_AUTH_ENABLED")!="true") { "Development bearer authentication is not supported by this server." }
        return ServerConfig(System.getenv("DATABASE_URL"),System.getenv("OPENAI_API_KEY"),System.getenv("OIDC_ISSUER"),System.getenv("OIDC_AUDIENCE"),System.getenv("OIDC_JWKS_URL"),System.getenv("OPENAI_VISION_MODEL")?:"gpt-4.1-mini",System.getenv("OPENAI_TRANSCRIPTION_MODEL")?:"gpt-4o-mini-transcribe")
    } }
}
data class SubjectPrincipal(val hash:String)
fun verifier(config:ServerConfig,injected:com.auth0.jwk.JwkProvider?=null):(String)->String? {
    if(!config.authConfigured) return { null }
    require(URI(config.issuer!!).scheme=="https" && URI(config.jwks!!).scheme=="https")
    val provider=injected?:JwkProviderBuilder(URI(config.jwks).toURL()).cached(10,24,TimeUnit.HOURS).rateLimited(10,1,TimeUnit.MINUTES).build()
    val algorithm=Algorithm.RSA256(object:RSAKeyProvider {
        override fun getPublicKeyById(keyId:String?)=provider.get(keyId).publicKey as RSAPublicKey
        override fun getPrivateKey():RSAPrivateKey?=null
        override fun getPrivateKeyId():String?=null
    })
    val jwt=JWT.require(algorithm).withIssuer(config.issuer).withAudience(config.audience).acceptLeeway(10).build()
    return { token -> runCatching { val decoded=jwt.verify(token); require(decoded.expiresAt!=null && !decoded.subject.isNullOrBlank()); sha256("${config.issuer}|${decoded.subject}".toByteArray()) }.getOrNull() }
}
fun main() {
    val config=ServerConfig.environment()
    val store=config.databaseUrl?.let(::PostgresJobs)
    val gateway=config.key?.takeIf { it.isNotBlank() }?.let { OpenAiGateway(it,config.visionModel,config.transcriptionModel) }
    embeddedServer(Netty,host="0.0.0.0",port=System.getenv("PORT")?.toIntOrNull()?:8080) { metricMe(config,store,gateway) }.start(wait=true)
}
fun Application.metricMe(config:ServerConfig,store:JobStore?,gateway:AiGateway?,verify:(String)->String?=verifier(config)) {
    install(ContentNegotiation) { json(WireJson) }
    install(StatusPages) {
        exception<ApiFailure> { call,e -> if(e.status==429) call.response.header(HttpHeaders.RetryAfter,"60"); call.respond(HttpStatusCode.fromValue(e.status),ApiError(e.code,e.retryable,UUID.randomUUID().toString())) }
        exception<Exception> { call,e -> if(e is CancellationException) throw e; call.respond(HttpStatusCode.InternalServerError,ApiError("internal_error",false,UUID.randomUUID().toString())) }
    }
    install(Authentication) { bearer("oidc") { authenticate { credential -> verify(credential.token)?.let(::SubjectPrincipal) } } }
    val slots=Semaphore(2)
    routing {
        get("/") { call.respondText("MetricMe API · native Android companion. Manual logging remains on your device. /health/live",ContentType.Text.Plain) }
        get("/health/live") { call.respond(ServiceInfo("live",gateway!=null,config.authConfigured)) }
        get("/health/ready") { if(store?.ready()!=true) call.respond(HttpStatusCode.ServiceUnavailable,ServiceInfo("database_unavailable",gateway!=null,config.authConfigured)) else call.respond(ServiceInfo("ready",gateway!=null,config.authConfigured)) }
        authenticate("oidc") {
            for((route,kind) in listOf("food-estimates" to "FOOD","activity-transcriptions" to "ACTIVITY")) post("/v1/$route") {
                val user=call.principal<SubjectPrincipal>()!!.hash
                val jobs=store?:throw ApiFailure("not_configured",503); val ai=gateway?:throw ApiFailure("not_configured",503)
                val id=call.request.headers["Idempotency-Key"]?:throw ApiFailure("idempotency_required",400)
                try { require(UUID.fromString(id).toString()==id) } catch(_: Exception) { throw ApiFailure("invalid_idempotency_key",400) }
                val limit=15L*1024*1024
                if((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?:0)>limit) throw ApiFailure("upload_too_large",413)
                val bytes=call.receiveChannel().readRemaining(limit+1).readByteArray()
                if(bytes.size>limit) throw ApiFailure("upload_too_large",413)
                val request=try { WireJson.decodeFromString<EstimateRequest>(String(bytes,Charsets.UTF_8)) } catch(_: Exception) { throw ApiFailure("invalid_request",400) }
                val media=MediaValidation.decode(request,kind)
                if(!slots.tryAcquire()) throw ApiFailure("busy",429,true)
                try {
                    val (owned,job)=withContext(Dispatchers.IO) { jobs.claim(user,id,sha256((kind+WireJson.encodeToString(request)).toByteArray())) }
                    if(!owned) { call.respond(if(job.status=="RUNNING") HttpStatusCode.Accepted else HttpStatusCode.OK,job.response()); return@post }
                    try { val result=ai.estimate(kind,request,media); EstimateRules.validate(result); withContext(Dispatchers.IO) { jobs.finish(user,id,"READY",result) } }
                    catch(e: CancellationException) { withContext(NonCancellable+Dispatchers.IO) { jobs.finish(user,id,"UNCERTAIN",code="upstream_uncertain") }; throw e }
                    catch(e: Exception) { val safe=e as? ApiFailure?:ApiFailure("invalid_ai_output",502); withContext(Dispatchers.IO) { jobs.finish(user,id,if(safe.retryable && job.attempt<5) "RETRYABLE" else if(safe.code=="upstream_uncertain") "UNCERTAIN" else "FAILED",code=safe.code) } }
                    call.respond(withContext(Dispatchers.IO) { requireNotNull(jobs.get(user,id)).response() })
                } finally { slots.release() }
            }
            get("/v1/jobs/{id}") { val user=call.principal<SubjectPrincipal>()!!.hash; val job=withContext(Dispatchers.IO) { store?.get(user,call.parameters["id"].orEmpty()) }?:throw ApiFailure("not_found",404); call.respond(job.response()) }
            delete("/v1/jobs/{id}") { val user=call.principal<SubjectPrincipal>()!!.hash; withContext(Dispatchers.IO) { store?.cancel(user,call.parameters["id"].orEmpty()) }; call.respond(HttpStatusCode.NoContent) }
            delete("/v1/account/data") { val user=call.principal<SubjectPrincipal>()!!.hash; withContext(Dispatchers.IO) { store?.purge(user) }; call.respond(HttpStatusCode.NoContent) }
        }
    }
}
