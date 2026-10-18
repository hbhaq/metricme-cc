package com.hbhaq.metricme.data

import androidx.room.withTransaction
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

class LocalMutations @Inject constructor(private val db: MetricMeDatabase, private val clock: Clock) {
    suspend fun execute(key: String, payload: String, block: suspend () -> String): String = db.withTransaction {
        UUID.fromString(key)
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val dao = db.profileDao()
        val previous = dao.receipt(key)
        if (previous != null) {
            require(previous.payloadHash == hash) { "This save key was already used for different data. Reopen the form." }
            return@withTransaction previous.resultId
        }
        if (dao.profile() == null) dao.saveProfile(ProfileEntity(stamp = Stamp(createdAt = clock.millis())))
        val result = block()
        dao.insertReceipt(ReceiptEntity(key, "local", hash, result, clock.millis()))
        result
    }
}
