package com.hbhaq.metricme.ai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import com.hbhaq.metricme.contracts.*
import com.hbhaq.metricme.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun AiScreen(model:AiViewModel,demo:Boolean,back:()->Unit,manual:(String)->Unit) {
    val drafts by model.drafts.collectAsStateWithLifecycle(); val selected by model.selected.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle(); val error by model.error.collectAsStateWithLifecycle()
    val recording by model.recorder.state.collectAsStateWithLifecycle()
    val sessionRevision by model.sessions.changes.collectAsStateWithLifecycle(); val authMessage by model.sessions.message.collectAsStateWithLifecycle()
    val context=LocalContext.current; val owner=LocalLifecycleOwner.current
    var sample by rememberSaveable { mutableStateOf("") }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { uri -> model.importPhoto(uri) } }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(),model::cameraResult)
    val microphone=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if(granted) model.startAudio() else model.error.value="Microphone permission was denied. Manual activity logging remains available." }
    DisposableEffect(owner,model) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) model.recorder.stop(); if(event==Lifecycle.Event.ON_RESUME) model.refresh() }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer); model.recorder.stop() }
    }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp,12.dp,24.dp,110.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { TextButton(onClick=back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null); Text("Back") }; PageTitle("Your judgment comes first","A little help. Your final say.","Capture, review, then confirm. AI estimates stay separate from your journal until you save them.") }
        item { Panel {
            Icon(Icons.Outlined.PhotoCamera,null); Text("A photo, a starting point",style=MaterialTheme.typography.titleLarge)
            Text("Take or choose a meal photo. We remove image metadata and show a local preview before any upload.")
            if(demo) Button(onClick={ sample="FOOD" }) { Text("Try sample food review") } else {
                Button(onClick={ try { camera.launch(model.cameraUri()) } catch(_:Exception) { model.cameraResult(false); model.error.value="Camera unavailable. Choose a photo or log manually." } },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Take a photo") }
                OutlinedButton(onClick={ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Choose a photo") }
            }
        } }
        item { Panel {
            Icon(Icons.Outlined.MicNone,null); Text("Tell your activity story",style=MaterialTheme.typography.titleLarge)
            Text("Record up to two minutes. Only stated activity details are extracted; missing steps stay unknown. Recording stops when you leave this screen.")
            if(demo) Button(onClick={ sample="ACTIVITY" }) { Text("Try sample activity review") } else {
                Text(if(recording.active) "Recording · ${recording.seconds}s / 120s" else recording.path?.let { "Recording ready · ${recording.seconds}s" }?:"Microphone is off")
                if(recording.active) Button(onClick=model.recorder::stop) { Text("Stop recording") }
                else if(recording.path!=null) { Button(onClick=model::keepAudio,enabled=!busy) { Text("Keep audio draft") }; TextButton(onClick=model.recorder::discard) { Text("Discard recording") } }
                else Button(onClick={ if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) model.startAudio() else microphone.launch(Manifest.permission.RECORD_AUDIO) },enabled=!busy) { Text("Record activity") }
                recording.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            }
        } }
        item { OutlinedButton(onClick={ manual("FOOD") },modifier=Modifier.fillMaxWidth()) { Text("Use manual logging instead") } }
        if(demo) item { Text("SAMPLE DATA · These examples run locally without an account or paid API. They do not read your media or write personal records.",style=MaterialTheme.typography.bodyMedium) }
        else {
            item { error?.let { ErrorMessage(it) }; if(busy) LinearProgressIndicator(Modifier.fillMaxWidth()); SectionLabel("Your private drafts") }
            val pending=drafts.filter { it.row.stamp.processing !in listOf("CONFIRMED","DELETED") }
            if(pending.isEmpty()) item { Text("No pending drafts. Photos, recordings and edits can be kept offline.") }
            pending.forEach { row -> item(key=row.row.id) { Panel {
                BadgeText(if(row.row.kind=="FOOD") "Food draft" else "Activity draft")
                Text(statusLabel(row.row.stamp.processing),style=MaterialTheme.typography.titleMedium)
                Text("Not included in totals · private media expires after 7 days",style=MaterialTheme.typography.bodySmall)
                row.value.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                Button(onClick={ model.select(row) },enabled=!busy) { Text(if(row.value.result==null) "Open draft" else "Review estimate") }
            } } }
            item { ConnectionPanel(model,sessionRevision,authMessage) }
            item { Text("On-device drafts stay in the app sandbox, not a separately encrypted database. Uploads send the selected media and context to your configured server and OpenAI only after explicit consent. Provider retention is separate from our temporary upload handling. Successful media is removed within 24 hours of confirmation; cancelled/deleted media is removed immediately.",style=MaterialTheme.typography.bodySmall) }
        }
    }
    if(!demo) drafts.firstOrNull { it.row.id==selected }?.let { DraftReview(model,it,manual) }
    if(sample.isNotEmpty()) SampleReview(if(sample=="FOOD") DemoEstimates.food() else DemoEstimates.activity()) { sample="" }
}
private fun statusLabel(status:String)=when(status) {
    "LOCAL" -> "Saved on this device"; "QUEUED" -> "Queued · waiting for connection"; "RUNNING" -> "Processing your draft"; "RETRYABLE" -> "Waiting to retry"; "AUTH_REQUIRED" -> "Sign-in needed"; "READY" -> "Ready for your review"; "CANCELLED" -> "Upload cancelled · draft kept"; "FAILED" -> "Needs your attention"; else -> status.lowercase().replace('_',' ')
}
@Composable private fun ConnectionPanel(model:AiViewModel,revision:Int,message:String?) {
    val settings=remember(revision) { model.sessions.settings() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var backend by rememberSaveable { mutableStateOf(settings.backend) }; var issuer by rememberSaveable { mutableStateOf(settings.issuer) }
    var client by rememberSaveable { mutableStateOf(settings.clientId) }; var audience by rememberSaveable { mutableStateOf(settings.audience) }
    Panel {
        SectionLabel("AI connection")
        Text(if(model.sessions.signedIn()) "Signed in for AI requests" else "Local logging needs no account",style=MaterialTheme.typography.titleMedium)
        Text(if(settings.complete) "Service: ${runCatching { java.net.URI(settings.backend).host }.getOrDefault("configured")}" else "Real AI is locked until your server and sign-in provider are configured. Sample mode works without them.")
        message?.let { Text(it) }
        if(settings.complete) {
            if(model.sessions.signedIn()) TextButton(onClick=model.sessions::signOut) { Text("Sign out on this device") }
            else Button(onClick=model::signIn) { Text("Sign in securely") }
            TextButton(onClick=model::refresh) { Text("Resume pending uploads") }
        }
        TextButton(onClick={ expanded=!expanded }) { Text(if(expanded) "Hide connection setup" else "Set up AI connection") }
        if(expanded) {
            Text("Use the public configuration from your deployed MetricMe service. Never paste an OpenAI key or client secret here.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(backend,{ backend=it },label={ Text("Backend HTTPS URL") },modifier=Modifier.fillMaxWidth())
            OutlinedTextField(issuer,{ issuer=it },label={ Text("Sign-in issuer URL") },modifier=Modifier.fillMaxWidth())
            OutlinedTextField(client,{ client=it },label={ Text("Android public client ID") },modifier=Modifier.fillMaxWidth())
            OutlinedTextField(audience,{ audience=it },label={ Text("API audience") },modifier=Modifier.fillMaxWidth())
            Button(onClick={ model.configure(ConnectionSettings(backend.trim(),issuer.trim(),client.trim(),audience.trim())) }) { Text("Save connection") }
        }
    }
}
@Composable private fun DraftReview(model:AiViewModel,row:AiDraft,manual:(String)->Unit) {
    val sessionRevision by model.sessions.changes.collectAsStateWithLifecycle()
    val signedIn=remember(sessionRevision) { model.sessions.signedIn() }
    val fields by model.fields.collectAsStateWithLifecycle(); val dirty by model.dirty.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle(); val error by model.error.collectAsStateWithLifecycle()
    var consent by rememberSaveable(row.row.id) { mutableStateOf(false) }; var deleting by rememberSaveable(row.row.id) { mutableStateOf(false) }
    val value=row.value; val result=value.result
    val locked=row.row.stamp.processing in listOf("QUEUED","RUNNING","RETRYABLE","AUTH_REQUIRED")
    LaunchedEffect(row.row.content) { model.sync(row) }
    @Composable fun input(key:String,label:String,old:String?,hint:String?=null,multiline:Boolean=false) {
        OutlinedTextField(fields[key]?:old.orEmpty(),{ model.change(key,it) },label={ Text(label) },supportingText=hint?.let { { Text(it) } },singleLine=!multiline,enabled=!busy&&!locked,modifier=Modifier.fillMaxWidth())
    }
    Dialog(onDismissRequest=model::close,properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnClickOutside=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding().fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween) { TextButton(onClick=model::close,enabled=!busy) { Text("Close draft") }; Text("ON DEVICE",style=MaterialTheme.typography.labelSmall) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    PageTitle("Draft · not in your totals",if(row.row.kind=="FOOD") "A closer look at your meal" else "Your activity, in your words",statusLabel(row.row.stamp.processing))
                    error?.let { ErrorMessage(it) }; value.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }; if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(row.row.kind=="FOOD" && row.row.localMediaPath!=null) PhotoPreview(row.row.localMediaPath)
                    if(row.row.kind=="ACTIVITY") Text("Audio draft stored privately. You can review and correct the transcript and extracted fields after processing.")
                    input("context","Optional context",value.context,"Serving size, ingredients, shared plate or details you want considered",true)
                    if(result!=null) {
                        BadgeText("AI-estimated · needs your confirmation")
                        result.food?.let { food ->
                            food.confidence?.let { Text("Model-reported confidence: ${(it*100).toInt()}% · this is not a guarantee of accuracy.") }
                            food.assumptions.forEach { Text("Assumption: $it") }; food.question?.let { Text(it,style=MaterialTheme.typography.titleMedium) }
                            EstimateRules.warnings(food).forEach { Text(it,color=MaterialTheme.colorScheme.error) }
                            food.items.forEachIndexed { i,item -> Panel {
                                Text("Food ${i+1}",style=MaterialTheme.typography.titleLarge)
                                input("f$i.name","Food name",item.name); input("f$i.quantity","Quantity",item.quantity.toString()); input("f$i.unit","Unit",item.unit)
                                input("f$i.calories","Calories for full quantity (optional)",item.calories?.toString())
                                input("f$i.protein","Protein grams (optional)",item.protein?.toString()); input("f$i.carbs","Carbohydrate grams (optional)",item.carbs?.toString()); input("f$i.fat","Fat grams (optional)",item.fat?.toString())
                            } }
                            input("portion","Portion consumed (%)",(food.portionConsumed*100).toString(),"Applies to each reviewed food item. 0 means none consumed.")
                            input("meal","Meal type",value.meal,"Breakfast, Lunch, Dinner, Snack or Other")
                            Text("Confirmation saves each recognized food as an independently editable journal entry, together in one transaction.",style=MaterialTheme.typography.bodySmall)
                        }
                        result.activity?.let { a ->
                            SectionLabel("Transcript · untrusted input")
                            Text(a.transcript.ifBlank { "No readable transcript" }); Text("Correct the extracted fields below. The original transcript is retained for context and cannot issue instructions.",style=MaterialTheme.typography.bodySmall)
                            a.assumptions.forEach { Text("Assumption: $it") }; a.question?.let { Text(it,style=MaterialTheme.typography.titleMedium) }
                            input("type","Activity name",a.type); input("minutes","Duration in minutes (optional)",a.durationMinutes?.toString()); input("steps","Explicitly reported steps (optional)",a.steps?.toString(),"Leave blank when steps were not stated or measured.")
                            input("intensity","Perceived intensity (optional)",a.intensity)
                            input("exercises","Workout exercises (optional)",a.exercises.joinToString("\n") { "${it.name} | ${it.sets?:""} | ${it.reps?:""} | ${it.loadKg?:""}" },"One per line: name | sets | reps | load in kg. Leave unknown values blank.",true)
                        }
                        val clarification=result.food?.needsClarification?:result.activity?.needsClarification?:false
                        if(clarification) Row { Checkbox(checked=(fields["clarification"]?:"true")=="false",onCheckedChange={ model.change("clarification",(!it).toString()) },enabled=!busy&&!locked); Text("I resolved the question and reviewed the entered facts.",Modifier.padding(top=12.dp).weight(1f)) }
                    }
                    SectionLabel("When it happened")
                    input("time","Event date and time",value.time,"YYYY-MM-DDTHH:mm · starts at draft creation; correct it when needed")
                    input("zone","Event timezone",value.zone); input("offset","UTC offset for repeated clock time",value.offset,"Normally blank; repeated times need an explicit offset")
                    if(dirty&&!locked) OutlinedButton(onClick={ model.save(row.row.id) },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Save review changes") }
                    if(locked) Button(onClick={ model.cancel(row.row.id,false) },enabled=!busy) { Text("Cancel upload · keep draft") }
                    else if(result==null) Button(onClick={ consent=true },enabled=!busy&&signedIn,modifier=Modifier.fillMaxWidth()) { Text("Review upload consent") }
                    if(result==null&&!signedIn) Text("Close this draft and sign in from AI connection to upload. It stays available offline.")
                    if(result!=null) Button(onClick={ model.confirm(row.row.id) },enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text("Confirm journal entries") }
                    OutlinedButton(onClick={ model.close(); manual(row.row.kind) },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Use manual entry") }
                    TextButton(onClick={ deleting=true },enabled=!busy) { Text("Delete draft and private media",color=MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if(consent) AlertDialog(onDismissRequest={ consent=false },title={ Text("Send this media for AI processing?") },text={ Text("Your selected ${if(row.row.kind=="FOOD") "photo" else "audio"} and optional context will be sent to ${runCatching { java.net.URI(model.sessions.settings().backend).host }.getOrDefault("your configured server")} and OpenAI. Camera or microphone permission alone is not upload consent. Uploads may wait offline and retry up to five times. The server processes media in memory; provider retention may differ. Cancel or delete this draft to withdraw pending work. Results remain drafts until you confirm.") },confirmButton={ TextButton(onClick={ consent=false; model.upload(row.row.id) }) { Text("Consent and queue upload") } },dismissButton={ TextButton(onClick={ consent=false }) { Text("Keep on device") } })
    if(deleting) AlertDialog(onDismissRequest={ deleting=false },title={ Text("Delete this draft?") },text={ Text("Private media is removed now. Pending upload work is cancelled; server result deletion waits for the matching account and a connection.") },confirmButton={ TextButton(onClick={ deleting=false; model.cancel(row.row.id,true) }) { Text("Delete draft") } },dismissButton={ TextButton(onClick={ deleting=false }) { Text("Keep draft") } })
}
@Composable private fun PhotoPreview(path:String) {
    val bitmap by produceState<android.graphics.Bitmap?>(null,path) { value=withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path,BitmapFactory.Options().apply { inSampleSize=2 }) } }
    bitmap?.let { Image(it.asImageBitmap(),"Private photo preview",Modifier.fillMaxWidth().heightIn(max=260.dp)) }?:Text("Photo preview unavailable. Keep the draft or choose another image.")
}
@Composable private fun SampleReview(result:AiResult,close:()->Unit) {
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) { Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) { Column(Modifier.statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        TextButton(onClick=close) { Text("Close sample") }; BadgeText("SAMPLE DATA · LOCAL EXAMPLE")
        PageTitle("Review before saving",if(result.kind=="FOOD") "A sample meal estimate" else "A sample activity note","This is a local demonstration, not analysis of your media.")
        result.food?.let { f -> f.items.forEach { Panel { Text(it.name,style=MaterialTheme.typography.titleLarge); Text("${it.quantity} ${it.unit} · ${it.calories?.toInt()} kcal estimated"); Text("Protein ${it.protein}g · Carbs ${it.carbs}g · Fat ${it.fat}g") } }; f.assumptions.forEach { Text(it) } }
        result.activity?.let { a -> Panel { Text(a.transcript); Text("${a.type} · ${a.durationMinutes?.toInt()} minutes"); Text("Steps not recorded") }; a.assumptions.forEach { Text(it) } }
        Text("Real results can be edited and need explicit confirmation. These sample entries cannot be saved to your personal journal.")
    } } }
}
