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

@Composable fun ConversationalJournal(model:JournalViewModel,setup:()->Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val text by model.text.collectAsStateWithLifecycle()
    val recording by model.recorder.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var consent by rememberSaveable { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JournalRecord?>(null) }
    var deleting by remember { mutableStateOf<JournalRecord?>(null) }
    val list=androidx.compose.foundation.lazy.rememberLazyListState()
    val photo=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(model::attach) }
    val audio=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::attachAudio) }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(),model::cameraResult)
    val microphone=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if(it) model.startRecording() }
    DisposableEffect(owner,model) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) model.recorder.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.recorder.stop() }
    }
    LaunchedEffect(recording.path) { if(recording.path!=null && !recording.active) model.keepRecording() }
    val replies=state.messages.map(MealRetention::reply)
    val liveIds=replies.map { it.id }.toSet()
    val history=state.entries.filter { it.messageId !in liveIds }.groupBy { it.messageId }.values.toList().reversed()
    LaunchedEffect(replies.size,state.busy) { if(list.layoutInfo.totalItemsCount>0) list.animateScrollToItem(list.layoutInfo.totalItemsCount-1) }
    fun send() { if(!state.signedIn) setup() else if(state.consentAccepted) model.send() else consent=true }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,contentPadding=PaddingValues(20.dp,12.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            if(state.before!=null) item { TextButton(onClick=model::more,enabled=!state.busy) { Text("Earlier messages") } }
            if(history.isEmpty() && replies.isEmpty()) item { Box(Modifier.fillParentMaxHeight(.65f).fillMaxWidth(),contentAlignment=Alignment.Center) { Text("What's happening?",style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(history,key={ it.first().messageId }) { records ->
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    records.forEach { raw -> val record=raw.copy(fact=MealRetention.redact(raw.fact))
                        EntryLine(record,{ editing=record },{ deleting=record })
                    }
                }
            }
            items(replies,key={ it.id }) { reply ->
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(reply.transcript.isNotBlank()) ChatUser(reply.transcript)
                    Text(reply.message)
                    reply.entries.forEach { raw -> val record=raw.copy(fact=MealRetention.redact(raw.fact)); EntryLine(record,{ editing=record },{ deleting=record }) }
                    reply.insights?.let { InsightTable(it) }
                    if(reply.references.isNotEmpty()) ReferenceTable(reply.references)
                    if(reply.status=="SAVED" && state.reply?.id==reply.id) TextButton(onClick=model::undo,enabled=!state.busy) { Text("Undo") }
                }
            }
            state.error?.let { item { ErrorMessage(it,model::dismissError) } }
            if(state.busy) item { Text("Thinking…",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Surface(color=MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(state.pending) Row(verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=model::send,enabled=!state.busy) { Text("Retry message") }
                    TextButton(onClick=model::discardPending,enabled=!state.busy) { Text("Cancel") }
                } else {
                    state.attachment?.let { Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(if(state.attachmentMime=="audio/wav") "Audio attached" else "Photo attached",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                        IconButton(onClick=model::removeAttachment,enabled=!state.busy) { Icon(Icons.Outlined.Close,"Remove attachment") }
                    } }
                    if(recording.active) Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Recording · "+recording.seconds+"s",Modifier.weight(1f))
                        TextButton(onClick=model.recorder::stop) { Text("Stop") }
                        TextButton(onClick=model.recorder::discard) { Text("Cancel") }
                    }
                    recording.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                    Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(6.dp)) {
                            TextField(text,model::type,placeholder={ Text("Message MetricMe") },modifier=Modifier.fillMaxWidth().heightIn(max=140.dp),enabled=!state.busy&&!recording.active,maxLines=4,colors=TextFieldDefaults.colors(focusedContainerColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedContainerColor=androidx.compose.ui.graphics.Color.Transparent,disabledContainerColor=androidx.compose.ui.graphics.Color.Transparent,focusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent))
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Box {
                                    IconButton(onClick={ attachments=true },enabled=!state.busy&&!recording.active) { Icon(Icons.Outlined.Add,"Add attachment") }
                                    DropdownMenu(attachments,{ attachments=false }) {
                                        DropdownMenuItem(text={ Text("Photo library") },leadingIcon={ Icon(Icons.Outlined.Image,null) },onClick={ attachments=false; photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                                        DropdownMenuItem(text={ Text("Camera") },leadingIcon={ Icon(Icons.Outlined.PhotoCamera,null) },onClick={ attachments=false; try { camera.launch(model.cameraUri()) } catch(_:Exception) { model.cameraResult(false) } })
                                        DropdownMenuItem(text={ Text("Audio file") },leadingIcon={ Icon(Icons.Outlined.AudioFile,null) },onClick={ attachments=false; audio.launch(arrayOf("audio/*")) })
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick={ if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) model.startRecording() else microphone.launch(Manifest.permission.RECORD_AUDIO) },enabled=!state.busy&&!recording.active) { Icon(Icons.Outlined.MicNone,"Record a voice note") }
                                FilledIconButton(onClick=::send,enabled=!state.busy&&!recording.active&&(text.isNotBlank()||state.attachment!=null)) { Icon(Icons.Outlined.ArrowUpward,"Send message") }
                            }
                        }
                    }
                }
            }
        }
    }
    if(consent) AlertDialog(onDismissRequest={ consent=false },title={ Text("Use AI logging?") },text={ Text("Sent messages and attachments go to MetricMe and OpenAI. Recognized events are saved to your account. You can edit or delete them; raw media is not kept in your cloud journal.") },confirmButton={ TextButton(onClick={ consent=false; model.acceptConsentAndSend() }) { Text("Enable & send") } },dismissButton={ TextButton(onClick={ consent=false }) { Text("Cancel") } })
    deleting?.let { entry -> AlertDialog(onDismissRequest={ deleting=null },title={ Text("Delete entry?") },confirmButton={ TextButton(onClick={ deleting=null; model.delete(entry) }) { Text("Delete") } },dismissButton={ TextButton(onClick={ deleting=null }) { Text("Cancel") } }) }
    editing?.let { entry -> JournalEditor(entry,state.busy,{ editing=null }) { fact -> model.edit(entry,fact) { editing=null } } }
}

@Composable private fun EntryLine(record:JournalRecord,edit:()->Unit,delete:()->Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(record.fact.title,style=MaterialTheme.typography.bodyLarge)
            Text(categoryLabel(record.fact.category)+" · "+journalDate(record.fact),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(record.fact.note.isNotBlank()) Text(record.fact.note,style=MaterialTheme.typography.bodySmall)
            factDetails(record.fact).takeIf(String::isNotBlank)?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
        }
        Box {
            IconButton(onClick={ menu=true }) { Icon(Icons.Outlined.MoreHoriz,"Entry options") }
            DropdownMenu(menu,{ menu=false }) {
                if(record.fact.detailsRetained) DropdownMenuItem(text={ Text("Edit") },onClick={ menu=false; edit() })
                DropdownMenuItem(text={ Text("Delete") },onClick={ menu=false; delete() })
            }
        }
    }
}

private fun factDetails(f:JournalFact)=listOfNotNull(f.quantity?.let { "$it ${f.unit.orEmpty()}" },f.dose,f.durationMinutes?.let { "$it minutes" },f.steps?.let { "$it steps" },f.calories?.let { "$it kcal" }).joinToString(" · ")

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
