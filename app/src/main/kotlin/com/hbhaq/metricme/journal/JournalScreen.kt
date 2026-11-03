package com.hbhaq.metricme.journal

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.*
import com.hbhaq.metricme.contracts.*
import com.hbhaq.metricme.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun categoryLabel(category:String)=category.lowercase().replaceFirstChar(Char::uppercaseChar)

@Composable fun ConversationalJournal(model:JournalViewModel,setup:()->Unit,manual:()->Unit,legacy:()->Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val text by model.text.collectAsStateWithLifecycle()
    val recording by model.recorder.state.collectAsStateWithLifecycle()
    val context=LocalContext.current; val owner=LocalLifecycleOwner.current
    var consent by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JournalRecord?>(null) }
    var deleting by remember { mutableStateOf<JournalRecord?>(null) }
    val photo=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(model::attach) }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(),model::cameraResult)
    val microphone=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if(it) model.startRecording() }
    DisposableEffect(owner,model) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) model.recorder.stop() }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer); model.recorder.stop() }
    }
    LaunchedEffect(recording.path) { if(recording.path!=null && !recording.active) model.keepRecording() }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp,16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { PageTitle("Your everyday memory","Journal","Speak, type or add a photo. MetricMe sorts it for you.") }
            if(!state.signedIn) item { Panel {
                Text("One journal, remembered",style=MaterialTheme.typography.titleLarge)
                Text("Sign in to save categorized entries to your private cloud journal and retrieve them later.")
                Button(onClick=setup,modifier=Modifier.fillMaxWidth()) { Text("Connect your journal") }
                TextButton(onClick=manual) { Text("Open offline manual journal") }
            } }
            item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("Your entries",style=MaterialTheme.typography.titleMedium)
                Row { TextButton(onClick=model::refresh,enabled=state.signedIn&&!state.busy) { Text("Refresh") }; TextButton(onClick=model::insights,enabled=state.signedIn&&!state.busy) { Text("Insights") } }
            } }
            item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=state.filter==null,onClick={ model.filter(null) },label={ Text("All") })
                JOURNAL_CATEGORIES.forEach { category -> FilterChip(selected=state.filter==category,onClick={ model.filter(category) },label={ Text(categoryLabel(category)) }) }
            } }
            state.error?.let { message -> item { ErrorMessage(message,model::dismissError) } }
            if(state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.reply?.let { reply -> item { Panel {
                Text(if(reply.status=="SAVED") "Journalled" else if(reply.status=="CLARIFY") "A quick question" else "MetricMe",style=MaterialTheme.typography.titleMedium)
                if(reply.transcript.isNotBlank()) Text("You: ${reply.transcript}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(reply.message)
                if(reply.status=="CLARIFY") Text("Reply below. Nothing from that message has been saved yet.",style=MaterialTheme.typography.bodySmall)
                if(reply.status=="SAVED") TextButton(onClick=model::undo,enabled=!state.busy) { Text("Undo these entries") }
                reply.insights?.let { s ->
                    Text("${s.fromDate} – ${s.toDate}",style=MaterialTheme.typography.labelLarge)
                    Text("${s.totalEntries} entries · ${s.daysLogged}/${s.daysInPeriod} days logged")
                    s.categories.forEach { Text("${categoryLabel(it.category)} · ${it.entries} entries on ${it.daysLogged} days",style=MaterialTheme.typography.bodyMedium) }
                    s.averageSleepMinutes?.let { Text("Reported sleep: ${"%.1f".format(it/60)} h/day on logged days") }
                    s.foodCalories?.let { Text("Logged food subtotal: ${it.toLong()} kcal · ${s.foodWithCalories} entries with calories") }
                    s.reportedSteps?.let { Text("Reported activity steps: $it") }
                    Text(s.note,style=MaterialTheme.typography.bodySmall)
                }
            } } }
            if(state.entries.isEmpty() && !state.busy && state.signedIn) item { Panel {
                Text(if(state.filter==null) "Tell me about your day" else "No ${categoryLabel(state.filter!!).lowercase()} entries yet",style=MaterialTheme.typography.titleLarge)
                Text("“I had eggs for breakfast, took my usual tablet, and slept seven hours.”")
                Text("Clear facts become separate entries. Missing or ambiguous details get a short question.",style=MaterialTheme.typography.bodySmall)
            } }
            items(state.entries,key={ it.id }) { entry -> Panel {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    BadgeText(categoryLabel(entry.fact.category))
                    Text(runCatching { Instant.parse(entry.fact.occurredAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm")) }.getOrDefault(entry.fact.occurredAt),style=MaterialTheme.typography.bodySmall)
                }
                Text(entry.fact.title,style=MaterialTheme.typography.titleMedium)
                if(entry.fact.note.isNotBlank()) Text(entry.fact.note)
                factDetails(entry.fact).takeIf(String::isNotBlank)?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                if(entry.fact.estimated) Text("AI estimate · editable",style=MaterialTheme.typography.labelSmall)
                Row { TextButton(onClick={ editing=entry },enabled=!state.busy) { Text("Edit") }; TextButton(onClick={ deleting=entry },enabled=!state.busy) { Text("Delete") } }
            } }
            if(state.before!=null) item { TextButton(onClick=model::more,enabled=!state.busy) { Text("Earlier entries") } }
            item { Row { TextButton(onClick=manual) { Text("Offline manual records") }; TextButton(onClick=legacy) { Text("Older AI drafts") } } }
        }
        Surface(shadowElevation=4.dp,color=MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(state.pending) {
                    Text("Message kept for retry",style=MaterialTheme.typography.labelLarge)
                    Row { Button(onClick=model::send,enabled=!state.busy) { Text("Retry same message") }; TextButton(onClick=model::discardPending,enabled=!state.busy) { Text("Cancel message") } }
                } else {
                    state.attachment?.let { Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(if(state.attachmentMime=="audio/wav") Icons.Outlined.MicNone else Icons.Outlined.Image,null)
                        Text(if(state.attachmentMime=="audio/wav") "Voice note attached" else "Photo attached",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                        TextButton(onClick=model::removeAttachment,enabled=!state.busy) { Text("Remove") }
                    } }
                    if(recording.active) Row(verticalAlignment=Alignment.CenterVertically) { Text("Listening · ${recording.seconds}s / 80s",Modifier.weight(1f)); Button(onClick=model.recorder::stop) { Text("Stop") }; TextButton(onClick=model.recorder::discard) { Text("Cancel") } }
                    recording.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                    OutlinedTextField(text,model::type,placeholder={ Text("Tell MetricMe…") },modifier=Modifier.fillMaxWidth().heightIn(max=140.dp),enabled=!state.busy&&!recording.active,maxLines=4)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick={ try { camera.launch(model.cameraUri()) } catch(_:Exception) { model.cameraResult(false) } },enabled=!state.busy&&!recording.active) { Icon(Icons.Outlined.PhotoCamera,"Take a photo") }
                        IconButton(onClick={ photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },enabled=!state.busy&&!recording.active) { Icon(Icons.Outlined.Image,"Attach a photo") }
                        IconButton(onClick={ if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) model.startRecording() else microphone.launch(Manifest.permission.RECORD_AUDIO) },enabled=!state.busy&&!recording.active) { Icon(Icons.Outlined.MicNone,"Record a voice note") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick={ if(!state.signedIn) setup() else if(state.consentAccepted) model.send() else consent=true },enabled=!state.busy&&!recording.active&&(text.isNotBlank()||state.attachment!=null)) { Text("Send") }
                    }
                    Text("Sending uses AI and saves clear entries to your cloud journal.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if(consent) AlertDialog(onDismissRequest={ consent=false },title={ Text("Enable your AI journal?") },text={ Text("Messages you send and attached media go to MetricMe and OpenAI. Clear events are saved automatically to your account's cloud journal until you delete them. Unclear details get a question. You can edit or undo entries. Raw media is not stored in your cloud journal. This applies to future messages you choose to send from this account; recording or attaching alone never uploads.") },confirmButton={ TextButton(onClick={ consent=false; model.acceptConsentAndSend() }) { Text("Enable & send") } },dismissButton={ TextButton(onClick={ consent=false }) { Text("Keep editing") } })
    deleting?.let { entry -> AlertDialog(onDismissRequest={ deleting=null },title={ Text("Delete entry?") },text={ Text("Remove this entry from your cloud journal. It will no longer count toward insights.") },confirmButton={ TextButton(onClick={ deleting=null; model.delete(entry) }) { Text("Delete") } },dismissButton={ TextButton(onClick={ deleting=null }) { Text("Keep") } }) }
    editing?.let { entry -> JournalEditor(entry,state.busy,{ editing=null }) { fact -> model.edit(entry,fact) { editing=null } } }
}

private fun factDetails(f:JournalFact)=listOfNotNull(f.quantity?.let { "$it ${f.unit.orEmpty()}" },f.dose,f.durationMinutes?.let { "$it minutes" },f.steps?.let { "$it steps" },f.calories?.let { "$it kcal" }).joinToString(" · ")

@Composable fun CloudInsightsScreen(model:JournalViewModel,demo:Boolean,setup:()->Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedIn,demo) { if(state.signedIn && !demo) model.insights() }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { PageTitle("From your saved records","Insights","Last 30 days. Ask about another period in your journal.") }
        if(demo) item { Text("Sample mode does not read your cloud records.") }
        else if(!state.signedIn) item { Panel { Text("Connect your journal to see your history."); Button(onClick=setup) { Text("Open Setup") } } }
        else {
            item { OutlinedButton(onClick=model::insights,enabled=!state.busy) { Text("Refresh insights") } }
            if(state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { ErrorMessage(it,model::dismissError) } }
            state.insights?.let { s ->
                item { Panel { Text("${s.fromDate} – ${s.toDate}",style=MaterialTheme.typography.labelLarge); Text("${s.totalEntries} entries",style=MaterialTheme.typography.headlineMedium); Text("${s.daysLogged} of ${s.daysInPeriod} days have recorded entries.") } }
                items(s.categories,key={ it.category }) { row -> Panel { Text(categoryLabel(row.category),style=MaterialTheme.typography.titleLarge); Text("${row.entries} entries · ${row.daysLogged} days logged") } }
                s.averageSleepMinutes?.let { item { Panel { Text("Reported sleep",style=MaterialTheme.typography.titleLarge); Text("${"%.1f".format(it/60)} hours per logged day"); Text("${s.sleepWithDuration} sleep entries with a duration. Missing days are excluded.") } } }
                s.foodCalories?.let { item { Panel { Text("Food logged",style=MaterialTheme.typography.titleLarge); Text("${it.toLong()} kcal subtotal"); Text("${s.foodWithCalories} entries with known or estimated calories. This is not a complete measure of intake.") } } }
                s.reportedSteps?.let { item { Panel { Text("Reported activity steps",style=MaterialTheme.typography.titleLarge); Text(it.toString()); Text("${s.activityWithSteps} activity entries with explicitly reported steps.") } } }
                item { Text(s.note,style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable private fun JournalEditor(entry:JournalRecord,busy:Boolean,close:()->Unit,save:(JournalFact)->Unit) {
    var title by rememberSaveable(entry.id) { mutableStateOf(entry.fact.title) }
    var note by rememberSaveable(entry.id) { mutableStateOf(entry.fact.note) }
    var category by rememberSaveable(entry.id) { mutableStateOf(entry.fact.category) }
    var time by rememberSaveable(entry.id) { mutableStateOf(entry.fact.occurredAt) }
    var dose by rememberSaveable(entry.id) { mutableStateOf(entry.fact.dose.orEmpty()) }
    var quantity by rememberSaveable(entry.id) { mutableStateOf(entry.fact.quantity?.toString().orEmpty()) }
    var unit by rememberSaveable(entry.id) { mutableStateOf(entry.fact.unit.orEmpty()) }
    var duration by rememberSaveable(entry.id) { mutableStateOf(entry.fact.durationMinutes?.toString().orEmpty()) }
    var calories by rememberSaveable(entry.id) { mutableStateOf(entry.fact.calories?.toString().orEmpty()) }
    var steps by rememberSaveable(entry.id) { mutableStateOf(entry.fact.steps?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest={ if(!busy) close() },title={ Text("Edit journal entry") },text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        JOURNAL_CATEGORIES.forEach { value -> FilterChip(selected=category==value,onClick={ category=value },label={ Text(categoryLabel(value)) }) }
        OutlinedTextField(title,{ title=it },label={ Text("Title") })
        OutlinedTextField(note,{ note=it },label={ Text("Notes") })
        OutlinedTextField(time,{ time=it },label={ Text("Event time (UTC)") },supportingText={ Text("YYYY-MM-DDTHH:mm:ssZ") })
        OutlinedTextField(quantity,{ quantity=it },label={ Text("Quantity (optional)") })
        OutlinedTextField(unit,{ unit=it },label={ Text("Unit (optional)") })
        if(category=="MEDICATION") OutlinedTextField(dose,{ dose=it },label={ Text("Dose actually taken (optional)") })
        if(category in listOf("SLEEP","ACTIVITY")) OutlinedTextField(duration,{ duration=it },label={ Text("Duration in minutes (optional)") })
        if(category=="FOOD") OutlinedTextField(calories,{ calories=it },label={ Text("Calories (optional)") })
        if(category=="ACTIVITY") OutlinedTextField(steps,{ steps=it },label={ Text("Reported steps (optional)") })
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    } },confirmButton={ TextButton(enabled=!busy,onClick={
        try {
            fun number(s:String):Double?=if(s.isBlank()) null else requireNotNull(s.toDoubleOrNull()) { "Use numbers or leave optional values blank." }
            val fact=entry.fact.copy(category=category,title=title.trim(),note=note,occurredAt=time.trim(),dose=if(category=="MEDICATION") dose.ifBlank { null } else null,quantity=number(quantity),unit=unit.ifBlank { null },durationMinutes=if(category in listOf("SLEEP","ACTIVITY")) number(duration) else null,calories=if(category=="FOOD") number(calories) else null,protein=if(category=="FOOD") entry.fact.protein else null,carbs=if(category=="FOOD") entry.fact.carbs else null,fat=if(category=="FOOD") entry.fact.fat else null,steps=if(category=="ACTIVITY") steps.takeIf(String::isNotBlank)?.let { requireNotNull(it.toIntOrNull()) } else null)
            JournalRules.fact(fact); save(fact)
        } catch(_:Exception) { error="Check the category, date and numeric values." }
    }) { Text("Save changes") } },dismissButton={ TextButton(onClick=close,enabled=!busy) { Text("Cancel") } })
}
