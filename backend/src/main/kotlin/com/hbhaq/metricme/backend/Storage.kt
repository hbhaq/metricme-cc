package com.hbhaq.metricme.backend

import com.hbhaq.metricme.contracts.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant

fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
class ApiFailure(val code: String, val status: Int, val retryable: Boolean = false) : RuntimeException(code)
data class StoredJob(val user: String, val id: String, val hash: String, val status: String, val content: String?, val code: String?, val attempt: Int, val updated: Long) {
    fun response() = JobResponse(id, status, content?.let { WireJson.decodeFromString<AiResult>(it) }, code, status == "RETRYABLE")
}
interface JobStore : AutoCloseable {
    fun ready(): Boolean
    fun claim(user: String, id: String, hash: String): Pair<Boolean, StoredJob>
    fun get(user: String, id: String): StoredJob?
    fun finish(user: String, id: String, status: String, result: AiResult? = null, code: String? = null)
    fun cancel(user: String, id: String)
    fun purge(user: String)
    override fun close() {}
}
class PostgresJobs(url: String) : JobStore {
    private val pool = HikariDataSource(HikariConfig().apply {
        if (url.startsWith("jdbc:")) jdbcUrl = url else {
            val uri = URI(url); require(uri.scheme in listOf("postgres", "postgresql"))
            jdbcUrl = "jdbc:postgresql://${uri.host}:${if (uri.port < 0) 5432 else uri.port}${uri.path}${uri.rawQuery?.let { "?$it" }.orEmpty()}"
            val credentials = uri.userInfo.orEmpty().split(':', limit = 2)
            username = java.net.URLDecoder.decode(credentials[0], Charsets.UTF_8)
            password = java.net.URLDecoder.decode(credentials.getOrElse(1) { "" }, Charsets.UTF_8)
        }
        maximumPoolSize = 4; connectionTimeout = 10000; initializationFailTimeout = 10000
    })
    init { pool.connection.use { c -> c.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS ai_jobs (owner_hash VARCHAR(64) NOT NULL, id VARCHAR(36) NOT NULL, payload_hash VARCHAR(64) NOT NULL, status VARCHAR(20) NOT NULL, content TEXT, code VARCHAR(40), attempt INTEGER NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, PRIMARY KEY(owner_hash,id))"); it.execute("CREATE INDEX IF NOT EXISTS ai_jobs_retention ON ai_jobs(updated_at)"); it.execute("CREATE INDEX IF NOT EXISTS ai_jobs_quota ON ai_jobs(owner_hash,created_at)") } } }
    override fun ready() = runCatching { pool.connection.use { it.isValid(2) } }.getOrDefault(false)
    private fun read(c: Connection, user: String, id: String): StoredJob? = c.prepareStatement("SELECT * FROM ai_jobs WHERE owner_hash=? AND id=?").use { s -> s.setString(1,user); s.setString(2,id); s.executeQuery().use { r -> if (!r.next()) null else StoredJob(user,id,r.getString("payload_hash"),r.getString("status"),r.getString("content"),r.getString("code"),r.getInt("attempt"),r.getLong("updated_at")) } }
    override fun get(user: String, id: String) = pool.connection.use { read(it,user,id) }
    override fun claim(user: String, id: String, hash: String): Pair<Boolean,StoredJob> = pool.connection.use { c ->
        c.autoCommit = false
        try {
            val now = Instant.now().toEpochMilli()
            c.prepareStatement("DELETE FROM ai_jobs WHERE updated_at < ?").use { it.setLong(1,now-7*86400000L); it.executeUpdate() }
            // Serialize new claims per subject, across replicas, before checking the quota.
            c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { it.setString(1,user); it.execute() }
            val existing = read(c,user,id)
            if (existing != null && existing.hash != hash) throw ApiFailure("idempotency_conflict",409)
            var owned = false
            if (existing == null) {
                val count = c.prepareStatement("SELECT COUNT(*) FROM ai_jobs WHERE owner_hash=? AND created_at>?").use { it.setString(1,user); it.setLong(2,now-3600000); it.executeQuery().use { r -> r.next(); r.getInt(1) } }
                if (count >= 10) throw ApiFailure("rate_limited",429,true)
                c.prepareStatement("INSERT INTO ai_jobs VALUES (?,?,?,'RUNNING',NULL,NULL,1,?,?)").use { it.setString(1,user); it.setString(2,id); it.setString(3,hash); it.setLong(4,now); it.setLong(5,now); it.executeUpdate() }; owned = true
            } else if (existing.status == "RETRYABLE" && existing.attempt < 5) {
                c.prepareStatement("UPDATE ai_jobs SET status='RUNNING',code=NULL,attempt=attempt+1,updated_at=? WHERE owner_hash=? AND id=? AND status='RETRYABLE'").use { it.setLong(1,now); it.setString(2,user); it.setString(3,id); owned = it.executeUpdate()==1 }
            } else if (existing.status == "RUNNING" && existing.updated < now-300000) {
                c.prepareStatement("UPDATE ai_jobs SET status='UNCERTAIN',code='upstream_uncertain',updated_at=? WHERE owner_hash=? AND id=? AND status='RUNNING'").use { it.setLong(1,now); it.setString(2,user); it.setString(3,id); it.executeUpdate() }
            }
            val value = requireNotNull(read(c,user,id)); c.commit(); owned to value
        } catch (e: Exception) { c.rollback(); throw e }
    }
    override fun finish(user: String,id: String,status: String,result: AiResult?,code: String?) { pool.connection.use { c -> c.prepareStatement("UPDATE ai_jobs SET status=?,content=?,code=?,updated_at=? WHERE owner_hash=? AND id=? AND status='RUNNING'").use { it.setString(1,status); it.setString(2,result?.let(WireJson::encodeToString)); it.setString(3,code); it.setLong(4,System.currentTimeMillis()); it.setString(5,user); it.setString(6,id); it.executeUpdate() } } }
    override fun cancel(user: String,id: String) { pool.connection.use { c -> c.prepareStatement("UPDATE ai_jobs SET status='CANCELLED',content=NULL,code=NULL,updated_at=? WHERE owner_hash=? AND id=?").use { it.setLong(1,System.currentTimeMillis()); it.setString(2,user); it.setString(3,id); it.executeUpdate() } } }
    override fun purge(user: String) { pool.connection.use { c -> c.prepareStatement("UPDATE ai_jobs SET status='CANCELLED',content=NULL,code=NULL,updated_at=? WHERE owner_hash=?").use { it.setLong(1,System.currentTimeMillis()); it.setString(2,user); it.executeUpdate() } } }
    override fun close() = pool.close()
}
