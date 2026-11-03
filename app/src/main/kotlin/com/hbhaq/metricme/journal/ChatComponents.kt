package com.hbhaq.metricme.journal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hbhaq.metricme.contracts.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable internal fun ChatUser(text:String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
        Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.widthIn(max=300.dp)) {
            Text(text,Modifier.padding(horizontal=16.dp,vertical=12.dp))
        }
    }
}

internal fun journalDate(fact:JournalFact):String=runCatching {
    Instant.parse(fact.occurredAt).atZone(ZoneId.of(fact.zone)).format(DateTimeFormatter.ofPattern(if(fact.detailsRetained) "d MMM · HH:mm" else "d MMM yyyy"))
}.getOrDefault("Date unavailable")

@Composable internal fun InsightTable(summary:JournalInsights) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(summary.fromDate+" – "+summary.toDate,style=MaterialTheme.typography.labelLarge)
        TableRow("Category","Entries")
        HorizontalDivider()
        summary.categories.forEach { TableRow(categoryLabel(it.category),it.entries.toString()) }
        TableRow("Days logged",summary.daysLogged.toString()+" / "+summary.daysInPeriod)
        summary.foodCalories?.let { TableRow("Food subtotal",it.toLong().toString()+" kcal") }
        summary.averageSleepMinutes?.let { TableRow("Sleep / logged day",String.format(java.util.Locale.ROOT,"%.1f h",it/60)) }
        summary.dailyCalorieTarget?.let { TableRow("Daily target",it.toString()+" kcal"); Text("Applies from "+summary.targetEffectiveFrom,style=MaterialTheme.typography.bodySmall) }
        summary.reportedSteps?.let { TableRow("Reported steps",it.toString()) }
        Text("Recorded entries only; unlogged days are unknown.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun TableRow(label:String,value:String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        Text(value,style=MaterialTheme.typography.bodyMedium)
    }
}
@Composable internal fun ReferenceTable(records:List<JournalRecord>) {
    Column(Modifier.horizontalScroll(rememberScrollState()).widthIn(min=320.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row { Text("When",Modifier.width(130.dp),style=MaterialTheme.typography.labelLarge); Text("Record",Modifier.width(210.dp),style=MaterialTheme.typography.labelLarge) }
        HorizontalDivider()
        records.forEach { raw -> val fact=MealRetention.redact(raw.fact)
            Row { Text(journalDate(fact),Modifier.width(130.dp),style=MaterialTheme.typography.bodySmall); Column(Modifier.width(210.dp)) { Text(fact.title,style=MaterialTheme.typography.bodyMedium); if(fact.note.isNotBlank()) Text(fact.note,style=MaterialTheme.typography.bodySmall) } }
        }
    }
}
