package com.hbhaq.metricme.backend

import com.hbhaq.metricme.contracts.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class PostgresTest {
    @Test fun durableConcurrentClaimsIsolationAndCancellation() {
        val url=System.getenv("TEST_DATABASE_URL"); assumeTrue("TEST_DATABASE_URL required; CI provides real PostgreSQL",!url.isNullOrBlank())
        val user=sha256(UUID.randomUUID().toString().toByteArray()); val id=UUID.randomUUID().toString()
        PostgresJobs(url!!).use { store ->
            val executor=Executors.newFixedThreadPool(4)
            try { val results=executor.invokeAll((1..8).map { Callable { store.claim(user,id,"hash").first } }); assertEquals(1,results.count { it.get() }) } finally { executor.shutdown() }
            store.finish(user,id,"READY",DemoEstimates.food().copy(sample=false)); assertEquals("READY",store.get(user,id)!!.status)
            assertNull(store.get("other",id)); assertThrows(ApiFailure::class.java) { store.claim(user,id,"changed") }
            val deferred=UUID.randomUUID().toString(); store.claim(user,deferred,"retry-hash"); store.finish(user,deferred,"RETRYABLE",code="upstream_rate_limited",retryAfterSeconds=120)
            val replay=store.claim(user,deferred,"retry-hash"); assertFalse(replay.first); assertEquals(1,replay.second.attempt); assertTrue(replay.second.response().retryAfterSeconds!!>0); store.cancel(user,deferred)
        }
        PostgresJobs(url).use { reopened -> assertFalse(reopened.claim(user,id,"hash").first); reopened.cancel(user,id); reopened.finish(user,id,"READY",DemoEstimates.food()); assertEquals("CANCELLED",reopened.get(user,id)!!.status); assertNull(reopened.get(user,id)!!.content) }
    }
}
