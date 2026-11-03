package com.hbhaq.metricme.backend

import com.hbhaq.metricme.contracts.*
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID

interface JournalStore {
    fun begin(owner:String,id:String,hash:String):Pair<Boolean,JournalReply>
    fun finish(owner:String,id:String,reply:JournalReply):JournalReply
    fun page(owner:String,before:String?,category:String?):JournalPage
    fun edit(owner:String,id:String,edit:JournalEdit):JournalRecord
    fun remove(owner:String,id:String)
    fun undo(owner:String,message:String)
    fun insights(owner:String,from:LocalDate,to:LocalDate,zone:ZoneId):JournalInsights
    fun purge(owner:String)
    fun compact(owner:String?=null) {}
    fun search(owner:String,from:LocalDate,to:LocalDate,zone:ZoneId,category:String?,term:String?,latest:Boolean):List<JournalRecord>
}

class PostgresJournal(private val pool:HikariDataSource):JournalStore {
    override fun compact(owner:String?) {
        val owners=if(owner!=null) listOf(owner) else pool.connection.use { c -> c.prepareStatement("SELECT DISTINCT owner_hash FROM journal_entries WHERE category='FOOD' AND occurred_at<? AND COALESCE(content::jsonb->>'detailsRetained','true')<>'false' UNION SELECT DISTINCT owner_hash FROM journal_messages WHERE result IS NOT NULL AND created_at<?").use { s -> val cutoff=MealRetention.cutoff().toEpochMilli(); s.setLong(1,cutoff); s.setLong(2,cutoff); s.executeQuery().use { r -> buildList { while(r.next()) add(r.getString(1)) } } } }
        for(user in owners) transaction { c ->
            lock(c,user)
            val rows=c.prepareStatement("SELECT * FROM journal_entries WHERE owner_hash=? AND category='FOOD' AND occurred_at<? AND COALESCE(content::jsonb->>'detailsRetained','true')<>'false'").use { s -> s.setString(1,user); s.setLong(2,MealRetention.cutoff().toEpochMilli()); s.executeQuery().use { r -> buildList { while(r.next()) add(Triple(r.getString("id"),r.getString("message_id"),WireJson.decodeFromString<JournalFact>(r.getString("content")))) } } }
            rows.forEach { (id,message,fact) ->
                val redacted=MealRetention.redact(fact)
                c.prepareStatement("UPDATE journal_entries SET content=?,occurred_at=?,revision=revision+1 WHERE owner_hash=? AND id=?").use { s -> s.setString(1,WireJson.encodeToString(redacted)); s.setLong(2,Instant.parse(redacted.occurredAt).toEpochMilli()); s.setString(3,user); s.setString(4,id); s.executeUpdate() }
                c.prepareStatement("UPDATE journal_messages SET result=NULL WHERE owner_hash=? AND id=?").use { s -> s.setString(1,user); s.setString(2,message); s.executeUpdate() }
            }
            c.prepareStatement("UPDATE journal_messages SET result=NULL WHERE owner_hash=? AND result IS NOT NULL AND (created_at<? OR status='ANSWER')").use { s -> s.setString(1,user); s.setLong(2,MealRetention.cutoff().toEpochMilli()); if(rows.isNotEmpty()) s.executeUpdate() else {
                c.prepareStatement("UPDATE journal_messages SET result=NULL WHERE owner_hash=? AND created_at<?").use { old -> old.setString(1,user); old.setLong(2,MealRetention.cutoff().toEpochMilli()); old.executeUpdate() }
            } }
        }
    }
    override fun search(owner:String,from:LocalDate,to:LocalDate,zone:ZoneId,category:String?,term:String?,latest:Boolean):List<JournalRecord> = pool.connection.use { c ->
        require(category==null || category in JOURNAL_CATEGORIES); require(term==null || term.length<=100)
        val text=term?.replace("\\","\\\\")?.replace("%","\\%")?.replace("_","\\_")
        val sql="SELECT * FROM journal_entries WHERE owner_hash=? AND occurred_at>=? AND occurred_at<?"+(if(category!=null) " AND category=?" else "")+(if(text!=null) " AND ((content::jsonb->>'title')||' '||(content::jsonb->>'note')) ILIKE ? ESCAPE E'\\\\'" else "")+(if(text!=null || latest && category=="FOOD") " AND COALESCE(content::jsonb->>'detailsRetained','true')<>'false'" else "")+" ORDER BY occurred_at DESC,id DESC LIMIT "+if(latest) "1" else "50"
        c.prepareStatement(sql).use { s -> var i=1; s.setString(i++,owner); s.setLong(i++,from.atStartOfDay(zone).toInstant().toEpochMilli()); s.setLong(i++,to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()); if(category!=null) s.setString(i++,category); if(text!=null) s.setString(i,"%$text%")
            s.executeQuery().use { r -> buildList { while(r.next()) add(JournalRecord(r.getString("id"),r.getString("message_id"),r.getInt("revision"),MealRetention.redact(WireJson.decodeFromString<JournalFact>(r.getString("content"))))) } }
        }
    }
    init { pool.connection.use { c -> c.createStatement().use {
        it.execute("CREATE TABLE IF NOT EXISTS journal_messages (owner_hash VARCHAR(64) NOT NULL,id VARCHAR(36) NOT NULL,payload_hash VARCHAR(64) NOT NULL,status VARCHAR(20) NOT NULL,result TEXT,created_at BIGINT NOT NULL,PRIMARY KEY(owner_hash,id))")
        it.execute("CREATE TABLE IF NOT EXISTS journal_entries (owner_hash VARCHAR(64) NOT NULL,id VARCHAR(36) NOT NULL,message_id VARCHAR(36) NOT NULL,revision INTEGER NOT NULL,category VARCHAR(20) NOT NULL,occurred_at BIGINT NOT NULL,content TEXT NOT NULL,PRIMARY KEY(owner_hash,id))")
        it.execute("CREATE INDEX IF NOT EXISTS journal_entries_history ON journal_entries(owner_hash,occurred_at DESC,id DESC)")
        it.execute("CREATE INDEX IF NOT EXISTS journal_entries_category ON journal_entries(owner_hash,category,occurred_at)")
        it.execute("CREATE INDEX IF NOT EXISTS journal_messages_quota ON journal_messages(owner_hash,created_at)")
    } } }
    private fun <T> transaction(block:(Connection)->T):T=pool.connection.use { c -> c.autoCommit=false; try { val value=block(c); c.commit(); value } catch(e:Exception) { c.rollback(); throw e } }
    private fun lock(c:Connection,owner:String) { c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { it.setString(1,"journal:$owner"); it.execute() } }
    override fun begin(owner:String,id:String,hash:String):Pair<Boolean,JournalReply> = transaction { c ->
        lock(c,owner)
        c.prepareStatement("SELECT * FROM journal_messages WHERE owner_hash=? AND id=?").use { s -> s.setString(1,owner); s.setString(2,id); s.executeQuery().use { r -> if(r.next()) {
            if(r.getString("payload_hash")!=hash) throw ApiFailure("idempotency_conflict",409)
            val status=r.getString("status")
            val content=r.getString("result")
            val expired=status=="PROCESSING" && r.getLong("created_at")<System.currentTimeMillis()-300000
            if(expired) invalidate(c,owner,id,"UNCERTAIN")
            return@transaction false to if(content!=null) WireJson.decodeFromString<JournalReply>(content) else JournalReply(id,if(expired) "UNCERTAIN" else status,message="No additional entries were saved. Start a new message only after checking your journal.")
        } } }
        val count=c.prepareStatement("SELECT COUNT(*) FROM journal_messages WHERE owner_hash=? AND created_at>?").use { it.setString(1,owner); it.setLong(2,System.currentTimeMillis()-3600000); it.executeQuery().use { r -> r.next(); r.getInt(1) } }
        if(count>=30) throw ApiFailure("rate_limited",429,true)
        c.prepareStatement("INSERT INTO journal_messages VALUES (?,?,?,'PROCESSING',NULL,?)").use { it.setString(1,owner); it.setString(2,id); it.setString(3,hash); it.setLong(4,System.currentTimeMillis()); it.executeUpdate() }
        true to JournalReply(id,"PROCESSING")
    }
    override fun finish(owner:String,id:String,reply:JournalReply):JournalReply = transaction { c ->
        lock(c,owner)
        val active=c.prepareStatement("SELECT status FROM journal_messages WHERE owner_hash=? AND id=?").use { it.setString(1,owner); it.setString(2,id); it.executeQuery().use { r -> r.next() && r.getString(1)=="PROCESSING" } }
        if(!active) return@transaction JournalReply(id,"DELETED",message="This message was removed.")
        val records=reply.entries.mapIndexed { index,row ->
            JournalRules.fact(row.fact)
            val record=JournalRecord(UUID.nameUUIDFromBytes("$owner:$id:$index".toByteArray()).toString(),id,0,MealRetention.redact(row.fact.copy(detailsRetained=true)))
            c.prepareStatement("INSERT INTO journal_entries VALUES (?,?,?,?,?,?,?)").use { s -> s.setString(1,owner); s.setString(2,record.id); s.setString(3,id); s.setInt(4,0); s.setString(5,record.fact.category); s.setLong(6,Instant.parse(record.fact.occurredAt).toEpochMilli()); s.setString(7,WireJson.encodeToString(record.fact)); s.executeUpdate() }
            record
        }
        val saved=reply.copy(entries=records,transcript=if(records.any { !it.fact.detailsRetained }) "" else reply.transcript)
        c.prepareStatement("UPDATE journal_messages SET status=?,result=? WHERE owner_hash=? AND id=?").use { it.setString(1,saved.status); it.setString(2,WireJson.encodeToString(saved)); it.setString(3,owner); it.setString(4,id); it.executeUpdate() }
        saved
    }
    override fun page(owner:String,before:String?,category:String?):JournalPage = pool.connection.use { c ->
        val cursor=before?.split('|')
        if(cursor!=null && (cursor.size!=2 || cursor[0].toLongOrNull()==null || runCatching { UUID.fromString(cursor[1]) }.isFailure)) throw ApiFailure("invalid_cursor",400)
        if(category!=null && category !in JOURNAL_CATEGORIES) throw ApiFailure("invalid_category",400)
        val rows=c.prepareStatement("SELECT * FROM journal_entries WHERE owner_hash=?"+(if(category!=null) " AND category=?" else "")+(if(cursor!=null) " AND (occurred_at,id)<(?,?)" else "")+" ORDER BY occurred_at DESC,id DESC LIMIT 51").use { s ->
            var i=1; s.setString(i++,owner); if(category!=null) s.setString(i++,category); if(cursor!=null) { s.setLong(i++,cursor[0].toLong()); s.setString(i,cursor[1]) }
            s.executeQuery().use { r -> buildList { while(r.next()) add(JournalRecord(r.getString("id"),r.getString("message_id"),r.getInt("revision"),WireJson.decodeFromString<JournalFact>(r.getString("content")))) } }
        }
        val page=rows.take(50); JournalPage(page,if(rows.size>50) page.last().let { "${Instant.parse(it.fact.occurredAt).toEpochMilli()}|${it.id}" } else null)
    }
    override fun edit(owner:String,id:String,edit:JournalEdit):JournalRecord=transaction { c ->
        lock(c,owner); JournalRules.fact(edit.fact)
        val retained=MealRetention.redact(edit.fact.copy(detailsRetained=true))
        val message=c.prepareStatement("SELECT message_id,revision,content FROM journal_entries WHERE owner_hash=? AND id=?").use { it.setString(1,owner); it.setString(2,id); it.executeQuery().use { r -> if(!r.next()) throw ApiFailure("not_found",404); if(r.getInt(2)!=edit.revision) throw ApiFailure("revision_conflict",409); if(!WireJson.decodeFromString<JournalFact>(r.getString(3)).detailsRetained) throw ApiFailure("meal_details_expired",409); r.getString(1) } }
        c.prepareStatement("UPDATE journal_entries SET revision=revision+1,category=?,occurred_at=?,content=? WHERE owner_hash=? AND id=?").use { it.setString(1,retained.category); it.setLong(2,Instant.parse(retained.occurredAt).toEpochMilli()); it.setString(3,WireJson.encodeToString(retained)); it.setString(4,owner); it.setString(5,id); it.executeUpdate() }
        invalidate(c,owner,message,"EDITED")
        JournalRecord(id,message,edit.revision+1,retained)
    }
    private fun invalidate(c:Connection,owner:String,message:String,status:String) {
        c.prepareStatement("UPDATE journal_messages SET status=?,result=NULL WHERE owner_hash=? AND id=?").use { it.setString(1,status); it.setString(2,owner); it.setString(3,message); it.executeUpdate() }
        // Answers can contain copies of referenced records. Edits/deletes invalidate those snapshots too.
        c.prepareStatement("UPDATE journal_messages SET result=NULL WHERE owner_hash=? AND status='ANSWER'").use { it.setString(1,owner); it.executeUpdate() }
    }
    override fun remove(owner:String,id:String) { transaction { c -> lock(c,owner)
        val message=c.prepareStatement("SELECT message_id FROM journal_entries WHERE owner_hash=? AND id=?").use { it.setString(1,owner); it.setString(2,id); it.executeQuery().use { r -> if(r.next()) r.getString(1) else null } }
        c.prepareStatement("DELETE FROM journal_entries WHERE owner_hash=? AND id=?").use { it.setString(1,owner); it.setString(2,id); it.executeUpdate() }
        if(message!=null) invalidate(c,owner,message,"EDITED")
    } }
    override fun undo(owner:String,message:String) { transaction { c -> lock(c,owner)
        // Tombstone even a not-yet-received submission, preventing late responses from resurrecting it.
        c.prepareStatement("INSERT INTO journal_messages VALUES (?,?,?,'DELETED',NULL,?) ON CONFLICT(owner_hash,id) DO UPDATE SET status='DELETED',result=NULL").use { it.setString(1,owner); it.setString(2,message); it.setString(3,"deleted"); it.setLong(4,System.currentTimeMillis()); it.executeUpdate() }
        c.prepareStatement("DELETE FROM journal_entries WHERE owner_hash=? AND message_id=?").use { it.setString(1,owner); it.setString(2,message); it.executeUpdate() }
        c.prepareStatement("UPDATE journal_messages SET result=NULL WHERE owner_hash=? AND status='ANSWER'").use { it.setString(1,owner); it.executeUpdate() }
    } }
    override fun purge(owner:String) { transaction { c -> lock(c,owner)
        c.prepareStatement("DELETE FROM journal_entries WHERE owner_hash=?").use { it.setString(1,owner); it.executeUpdate() }
        c.prepareStatement("UPDATE journal_messages SET status='DELETED',result=NULL WHERE owner_hash=?").use { it.setString(1,owner); it.executeUpdate() }
    } }
    override fun insights(owner:String,from:LocalDate,to:LocalDate,zone:ZoneId):JournalInsights=pool.connection.use { c ->
        val days=ChronoUnit.DAYS.between(from,to)+1
        if(days !in 1..3660 || to>LocalDate.now(zone)) throw ApiFailure("invalid_date_range",400)
        val start=from.atStartOfDay(zone).toInstant().toEpochMilli(); val end=to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        fun java.sql.PreparedStatement.bounds(offset:Int=1) { setString(offset,owner); setLong(offset+1,start); setLong(offset+2,end) }
        val categories=c.prepareStatement("SELECT category,COUNT(*),COUNT(DISTINCT (to_timestamp(occurred_at/1000.0) AT TIME ZONE ?)::date) FROM journal_entries WHERE owner_hash=? AND occurred_at>=? AND occurred_at<? GROUP BY category ORDER BY category").use { s -> s.setString(1,zone.id); s.bounds(2); s.executeQuery().use { r -> buildList { while(r.next()) add(CategorySummary(r.getString(1),r.getInt(2),r.getInt(3))) } } }
        c.prepareStatement("SELECT COUNT(DISTINCT (to_timestamp(occurred_at/1000.0) AT TIME ZONE ?)::date),SUM((content::jsonb->>'calories')::double precision) FILTER(WHERE category='FOOD'),COUNT(*) FILTER(WHERE category='FOOD' AND content::jsonb->>'calories' IS NOT NULL),COUNT(*) FILTER(WHERE category='SLEEP' AND content::jsonb->>'durationMinutes' IS NOT NULL),SUM((content::jsonb->>'steps')::bigint) FILTER(WHERE category='ACTIVITY'),COUNT(*) FILTER(WHERE category='ACTIVITY' AND content::jsonb->>'steps' IS NOT NULL) FROM journal_entries WHERE owner_hash=? AND occurred_at>=? AND occurred_at<?").use { s -> s.setString(1,zone.id); s.bounds(2); s.executeQuery().use { r -> r.next()
            val coverage=r.getInt(1); val kcal=(r.getObject(2) as? Number)?.toDouble(); val food=r.getInt(3); val sleep=r.getInt(4); val steps=(r.getObject(5) as? Number)?.toLong(); val activity=r.getInt(6)
            val average=c.prepareStatement("SELECT AVG(minutes) FROM (SELECT SUM((content::jsonb->>'durationMinutes')::double precision) AS minutes FROM journal_entries WHERE owner_hash=? AND occurred_at>=? AND occurred_at<? AND category='SLEEP' AND content::jsonb->>'durationMinutes' IS NOT NULL GROUP BY (to_timestamp(occurred_at/1000.0) AT TIME ZONE ?)::date) daily").use { a -> a.bounds(); a.setString(4,zone.id); a.executeQuery().use { row -> row.next(); (row.getObject(1) as? Number)?.toDouble() } }
            JournalInsights(from.toString(),to.toString(),days.toInt(),coverage,categories.sumOf { it.entries },categories,kcal,food,average,sleep,steps,activity)
        } }
    }
}
