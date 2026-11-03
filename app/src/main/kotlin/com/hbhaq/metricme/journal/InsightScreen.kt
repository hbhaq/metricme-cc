package com.hbhaq.metricme.journal

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hbhaq.metricme.ui.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun CloudInsightsScreen(model:JournalViewModel,demo:Boolean,setup:()->Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val question by model.questionText.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var consent by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.signedIn,demo) { if(state.signedIn && !demo) model.insights() }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp,16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { PageTitle("Your saved history","Insights","A day, a week, a month — or just ask.") }
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("day" to "Daily","week" to "Weekly","month" to "Monthly").forEach { (key,label) -> FilterChip(selected=state.period==key,onClick={ model.choosePeriod(key) },enabled=!state.busy,label={ Text(label) }) }
            } }
            item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick={ model.shiftPeriod(-1) },enabled=!state.busy) { Text("Previous") }
                TextButton(onClick={ val date=LocalDate.parse(state.insightDate); DatePickerDialog(context,{ _,y,m,d -> model.chooseInsightDate(LocalDate.of(y,m+1,d)) },date.year,date.monthValue-1,date.dayOfMonth).apply { datePicker.maxDate=System.currentTimeMillis() }.show() },enabled=!state.busy) { Text(LocalDate.parse(state.insightDate).format(DateTimeFormatter.ofPattern("d MMM yyyy"))) }
                TextButton(onClick={ model.shiftPeriod(1) },enabled=!state.busy && LocalDate.parse(state.insightDate)<LocalDate.now()) { Text("Next") }
            } }
            if(demo) item { Text("Sample mode does not read your cloud records.") }
            else if(!state.signedIn) item { Panel { Text("Connect your journal to ask about saved history."); Button(onClick=setup) { Text("Open Setup") } } }
            if(state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { message -> item { ErrorMessage(message,model::dismissError) } }
            if(!demo && state.signedIn) state.insights?.let { s -> item { Panel {
                Text("${s.fromDate} – ${s.toDate}",style=MaterialTheme.typography.labelLarge)
                Text("${s.totalEntries} entries · ${s.daysLogged}/${s.daysInPeriod} days logged",style=MaterialTheme.typography.titleMedium)
                s.categories.forEach { Text("${categoryLabel(it.category)} · ${it.entries} entries") }
                s.foodCalories?.let { Text("Food logged: ${it.toLong()} kcal subtotal") }
                s.averageSleepMinutes?.let { Text("Reported sleep: ${"%.1f".format(it/60)} h per logged day") }
                s.reportedSteps?.let { Text("Reported steps: $it") }
                Text(s.note,style=MaterialTheme.typography.bodySmall)
            } } }
            if(state.questions.isEmpty()) item { Panel { Text("Ask your journal",style=MaterialTheme.typography.titleMedium); Text("“When did I last have a burger?”\n“How was my sleep last week?”\n“Show my medication entries this month.”"); Text("Meal names and exact times are kept for three months. Nutrition summaries remain.",style=MaterialTheme.typography.bodySmall) } }
            items(state.questions,key={ it.id }) { reply ->
                Panel {
                    Text("You",style=MaterialTheme.typography.labelLarge)
                    Text(reply.transcript)
                    HorizontalDivider()
                    Text("MetricMe",style=MaterialTheme.typography.labelLarge)
                    Text(reply.message)
                    reply.references.forEach { record ->
                        Text(record.fact.title,style=MaterialTheme.typography.titleMedium)
                        Text(Instant.parse(record.fact.occurredAt).atZone(ZoneId.of(record.fact.zone)).format(DateTimeFormatter.ofPattern(if(record.fact.detailsRetained) "d MMM yyyy · HH:mm" else "d MMM yyyy")),style=MaterialTheme.typography.bodySmall)
                        if(record.fact.note.isNotBlank()) Text(record.fact.note,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Surface(shadowElevation=4.dp,color=MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(question,model::typeQuestion,modifier=Modifier.fillMaxWidth().heightIn(max=120.dp),placeholder={ Text("Ask about your history…") },enabled=!state.busy&&!state.questionPending,maxLines=3)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                Button(onClick={ if(!state.signedIn) setup() else if(!state.consentAccepted) consent=true else model.ask() },enabled=!demo&&!state.busy&&(question.isNotBlank()||state.questionPending)) { Text(if(state.questionPending) "Retry question" else "Ask") }
            }
        } }
    }
    if(consent) AlertDialog(onDismissRequest={ consent=false },title={ Text("Use your AI journal?") },text={ Text("Questions you send go to MetricMe and OpenAI to identify what to retrieve. Answers use your saved database records. Insights questions never create journal entries. This also enables future Journal messages you choose to send to be categorized and saved to your account. You can reset consent in Setup.") },confirmButton={ TextButton(onClick={ consent=false; model.acceptConsentAndAsk() }) { Text("Enable & ask") } },dismissButton={ TextButton(onClick={ consent=false }) { Text("Cancel") } })
}
