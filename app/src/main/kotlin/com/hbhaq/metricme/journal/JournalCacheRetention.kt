package com.hbhaq.metricme.journal

import android.content.Context
import android.util.AtomicFile
import com.hbhaq.metricme.contracts.*
import java.io.File

internal fun trimMealCache(context:Context) {
    File(context.filesDir,"journal-cache").listFiles().orEmpty().filter { it.name.endsWith("-entries.json") }.forEach { file ->
        runCatching {
            val target=AtomicFile(file)
            val page=target.openRead().bufferedReader().use { WireJson.decodeFromString<JournalPage>(it.readText()) }
            val clean=page.copy(entries=page.entries.map { it.copy(fact=MealRetention.redact(it.fact)) })
            if(clean!=page) { val output=target.startWrite(); try { output.write(WireJson.encodeToString(clean).toByteArray()); target.finishWrite(output) } catch(e:Exception) { target.failWrite(output); throw e } }
        }
    }
}
