package com.hbhaq.metricme.backend

import com.hbhaq.metricme.contracts.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

class MemoryJobs:JobStore {
    private val rows=mutableMapOf<Pair<String,String>,StoredJob>()
    override fun ready()=true
    @Synchronized override fun get(user:String,id:String)=rows[user to id]
    @Synchronized override fun claim(user:String,id:String,hash:String):Pair<Boolean,StoredJob> { val old=rows[user to id]; if(old!=null) { if(old.hash!=hash) throw ApiFailure("idempotency_conflict",409); return false to old }; val row=StoredJob(user,id,hash,"RUNNING",null,null,1,System.currentTimeMillis()); rows[user to id]=row; return true to row }
    @Synchronized override fun finish(user:String,id:String,status:String,result:AiResult?,code:String?) { val row=rows[user to id]?:return; if(row.status=="RUNNING") rows[user to id]=row.copy(status=status,content=result?.let(WireJson::encodeToString),code=code) }
    @Synchronized override fun cancel(user:String,id:String) { rows[user to id]?.let { rows[user to id]=it.copy(status="CANCELLED",content=null) } }
    @Synchronized override fun purge(user:String) { rows.keys.filter { it.first==user }.toList().forEach { cancel(it.first,it.second) } }
}
fun photoRequest(context:String=""):EstimateRequest { val out=ByteArrayOutputStream(); ImageIO.write(BufferedImage(32,32,BufferedImage.TYPE_INT_RGB),"png",out); return EstimateRequest(Base64.getEncoder().encodeToString(out.toByteArray()),"image/png",context,CONSENT_VERSION,true) }
class BackendTest {
    private val config=ServerConfig(null,null,null,null,null)
    @Test fun healthIsPublicAndRealRoutesRequireAuthentication()=testApplication {
        application { metricMe(config,MemoryJobs(),null) }
        assertEquals(HttpStatusCode.OK,client.get("/health/live").status)
        assertEquals(HttpStatusCode.Unauthorized,client.post("/v1/food-estimates").status)
        assertEquals(HttpStatusCode.Unauthorized,client.delete("/v1/account/data").status)
    }
    @Test fun retriesAreCachedAndDifferentUsersCannotReadResults()=testApplication {
        var calls=0; val store=MemoryJobs()
        application { metricMe(config,store,object:AiGateway { override suspend fun estimate(kind:String,request:EstimateRequest,media:ByteArray):AiResult { calls++; return DemoEstimates.food().copy(sample=false) } },verify={ if(it in listOf("alice","bob")) it else null }) }
        val key=UUID.randomUUID().toString(); val request=photoRequest()
        suspend fun send(value:EstimateRequest)=client.post("/v1/food-estimates") { bearerAuth("alice"); header("Idempotency-Key",key); contentType(ContentType.Application.Json); setBody(WireJson.encodeToString(value)) }
        repeat(2) { assertEquals(HttpStatusCode.OK,send(request).status) }; assertEquals(1,calls)
        assertEquals(HttpStatusCode.Conflict,send(photoRequest("changed")).status)
        assertEquals(HttpStatusCode.NotFound,client.get("/v1/jobs/$key") { bearerAuth("bob") }.status)
        assertEquals(HttpStatusCode.NoContent,client.delete("/v1/jobs/$key") { bearerAuth("alice") }.status)
        val result=WireJson.decodeFromString<JobResponse>(client.get("/v1/jobs/$key") { bearerAuth("alice") }.bodyAsText()); assertEquals("CANCELLED",result.status); assertNull(result.result)
    }
    @Test fun missingConsentAndMimeSpoofNeverCallAi()=testApplication {
        var calls=0
        application { metricMe(config,MemoryJobs(),object:AiGateway { override suspend fun estimate(kind:String,request:EstimateRequest,media:ByteArray):AiResult { calls++; error("Should never run") } },verify={ "owner" }) }
        for(request in listOf(photoRequest().copy(consented=false),photoRequest().copy(mimeType="image/jpeg"),photoRequest().copy(mediaBase64="!!!"))) {
            val response=client.post("/v1/food-estimates") { bearerAuth("test"); header("Idempotency-Key",UUID.randomUUID().toString()); contentType(ContentType.Application.Json); setBody(WireJson.encodeToString(request)) }; assertEquals(HttpStatusCode.BadRequest,response.status)
        }; assertEquals(0,calls)
    }
    @Test fun malformedModelOutputFailsWithoutHealthPayloadInError()=testApplication {
        application { metricMe(config,MemoryJobs(),object:AiGateway { override suspend fun estimate(kind:String,request:EstimateRequest,media:ByteArray):AiResult { throw IllegalArgumentException("private allergy note") } },verify={ "owner" }) }
        val response=client.post("/v1/food-estimates") { bearerAuth("test"); header("Idempotency-Key",UUID.randomUUID().toString()); contentType(ContentType.Application.Json); setBody(WireJson.encodeToString(photoRequest())) }
        val text=response.bodyAsText(); assertFalse(text.contains("private allergy")); assertEquals("FAILED",WireJson.decodeFromString<JobResponse>(text).status)
    }
    @Test fun canonicalWavBoundsAndInvalidSignaturesAreEnforced() {
        val bytes=java.nio.ByteBuffer.allocate(44+3200).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { put("RIFF".toByteArray()); putInt(capacity()-8); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16); put("data".toByteArray()); putInt(capacity()-44) }.array()
        assertThrows(ApiFailure::class.java) { MediaValidation.audio(bytes,"audio/wav") }; bytes[44]=1
        MediaValidation.audio(bytes,"audio/wav"); bytes[0]=0; assertThrows(ApiFailure::class.java) { MediaValidation.audio(bytes,"audio/wav") }
        assertThrows(ApiFailure::class.java) { MediaValidation.decode(photoRequest().copy(mediaBase64="A".repeat(15*1024*1024)),"FOOD") }
    }
    @Test fun cancellationCannotBeOverwrittenByLateResult() { val store=MemoryJobs(); store.claim("a","id","hash"); store.cancel("a","id"); store.finish("a","id","READY",DemoEstimates.food()); assertEquals("CANCELLED",store.get("a","id")!!.status); assertNull(store.get("a","id")!!.content) }
}
