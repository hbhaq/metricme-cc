package com.hbhaq.metricme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hbhaq.metricme.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("journal", "Log", Icons.Outlined.ChatBubbleOutline),
    Destination("insights", "Insights", Icons.Outlined.BarChart),
    Destination("you", "You", Icons.Outlined.PersonOutline),
    Destination("setup", "Setup", Icons.Outlined.Settings)
)

@Composable
fun MetricMeApp(model: MetricMeViewModel, logging: LoggingViewModel, ai: com.hbhaq.metricme.ai.AiViewModel, journal: com.hbhaq.metricme.journal.JournalViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val form by model.formKind.collectAsStateWithLifecycle()
    val logs by logging.state.collectAsStateWithLifecycle()
    val logEditor by logging.editor.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("journal") }
    var picker by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.demo) { logging.setDemo(state.demo) }
    androidx.activity.compose.BackHandler(screen !in destinations.map { it.route }) { screen="setup" }
    Scaffold(floatingActionButton={ if(screen=="offline" && !state.demo) FloatingActionButton(onClick={ picker=true }) { Icon(Icons.Outlined.Add,"Add offline entry") } },bottomBar = {
        NavigationBar(containerColor=MaterialTheme.colorScheme.background, tonalElevation=0.dp) {
            destinations.forEach { destination ->
                NavigationBarItem(selected=screen==destination.route,
                    modifier=Modifier.testTag("nav-"+destination.route),
                    onClick={ screen=destination.route },
                    icon={ Icon(destination.icon,null) },label={ Text(destination.label) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(destinations.firstOrNull { it.route==screen }?.label ?: "Saved records",style=MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if(state.demo) TextButton(onClick={ model.setDemo(false) }) { Text("Exit demo") }
            }
            if(state.demo) Text("SAMPLE DATA",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.labelSmall)
            if(state.loading) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
            else Box(Modifier.weight(1f)) {
                when(screen) {
                    "journal" -> com.hbhaq.metricme.journal.ConversationalJournal(journal, { screen="setup" },state.demo)
                    "insights" -> com.hbhaq.metricme.journal.CloudInsightsScreen(journal,state.demo) { screen="setup" }
                    "you" -> YouScreen(state,model,{ screen="measurements" })
                    "setup" -> SetupScreen(state,model,ai,journal,{ screen=it })
                    "measurements" -> MeasurementScreen(state,model) { screen="you" }
                    "offline" -> ManualJournalScreen(logs,logging,{ screen="measurements" })
                    "routines" -> RoutinesScreen(logs,logging,state.demo) { screen="setup" }
                    "assist" -> com.hbhaq.metricme.ai.AiScreen(ai,state.demo,{ screen="setup" },{ screen="setup" }) { kind -> logging.openLog(LogKind.valueOf(kind)) }
                }
            }
        }
    }
    if(picker) LogPicker({ picker=false },{ picker=false; logging.openLog(it) },{ picker=false; logging.openRoutine(it) },{ picker=false; screen="assist" })
    if(form.isNotEmpty()) FormDialog(model,state,form)
    if(logEditor.isNotEmpty() && !state.demo) LoggingEditor(logging,logs,logEditor)
}

@Composable private fun SetupScreen(state:AppState,model:MetricMeViewModel,ai:com.hbhaq.metricme.ai.AiViewModel,journal:com.hbhaq.metricme.journal.JournalViewModel,open:(String)->Unit) {
    val error by ai.error.collectAsStateWithLifecycle()
    val cloud by journal.state.collectAsStateWithLifecycle()
    var more by rememberSaveable { mutableStateOf(false) }
    var deleteCloud by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        error?.let { item { ErrorMessage(it) { ai.error.value=null } } }
        item { com.hbhaq.metricme.ai.AiSetupPanel(ai) }
        item { TextButton(onClick={ more=!more }) { Text(if(more) "Close data settings" else "Data settings") } }
        if(more) {
            item { Text("Meal details: 3 months. Nutrition summaries stay. Your profile stays on this device.",style=MaterialTheme.typography.bodySmall) }
            item { TextButton(onClick={ open("offline") }) { Text("Offline records") } }
            item { TextButton(onClick={ open("assist") }) { Text("Older AI drafts") } }
            item { TextButton(onClick={ open("routines") }) { Text("Reminders") } }
            item { Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Sample mode",Modifier.weight(1f))
                Switch(state.demo,model::setDemo,modifier=Modifier.semantics { contentDescription="Explore demo mode" })
            } }
            if(cloud.signedIn) {
                if(cloud.consentAccepted) item { TextButton(onClick=journal::revokeConsent,enabled=!cloud.busy) { Text("Reset AI consent") } }
                item { TextButton(onClick={ deleteCloud=true },enabled=!cloud.busy) { Text("Delete cloud data",color=MaterialTheme.colorScheme.error) } }
            }
            cloud.error?.let { item { ErrorMessage(it,journal::dismissError) } }
        }
    }
    if(deleteCloud) AlertDialog(onDismissRequest={ deleteCloud=false },title={ Text("Delete cloud data?") },text={ Text("All cloud entries and AI results for this account will be permanently deleted. This cannot be undone.") },confirmButton={ TextButton(onClick={ deleteCloud=false; journal.clearCloudData() }) { Text("Delete") } },dismissButton={ TextButton(onClick={ deleteCloud=false }) { Text("Cancel") } })
}

@Composable internal fun PageTitle(eyebrow: String, title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(eyebrow.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable internal fun BadgeText(label: String, icon: ImageVector? = null) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(13.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}
@Composable internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable internal fun SectionLabel(title: String, caption: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (caption != null) Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun MiniMetric(title: String, value: String, detail: String, icon: ImageVector, modifier: Modifier) {
    Panel(modifier) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.secondary)
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun YouScreen(state:AppState,model:MetricMeViewModel,history:()->Unit) {
    val profile=state.profile
    val locale=LocalLocale.current.platformLocale
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Panel {
            Text(profile?.name?.takeIf(String::isNotBlank) ?: "Your name",style=MaterialTheme.typography.titleLarge)
            profile?.birthDate?.let { Text(java.time.Period.between(it,model.today()).years.toString()+" years") }
            profile?.genderIdentity?.takeIf(String::isNotBlank)?.let { Text(it) }
            TextButton(onClick=model::openProfile,enabled=!state.demo) { Text(if(profile==null) "Add details" else "Edit details") }
        } }
        items(MeasurementKind.entries.toList()) { kind ->
            val value=HealthRules.effectiveMeasurement(state.measurements,kind,Instant.now())
            OutlinedCard(onClick={ model.openMeasurement(kind) },enabled=!state.demo,modifier=Modifier.fillMaxWidth()) {
                Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if(kind==MeasurementKind.HEIGHT) "Height" else "Weight",style=MaterialTheme.typography.labelLarge)
                        Text(value?.let { displayMeasurement(it,profile?.imperialDisplay==true,locale) } ?: "Add",style=MaterialTheme.typography.titleLarge)
                    }
                    Icon(Icons.Outlined.Edit,"Edit "+kind.name.lowercase())
                }
            }
        }
        item { TextButton(onClick=history) { Text("Measurement history") } }
    }
}

@Composable private fun PrivacyNote() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Local storage uses Android’s app sandbox and device encryption. The database is not separately encrypted. AI uploads require sign-in and explicit consent; manual logging stays offline.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun displayMeasurement(m: Measurement, imperial: Boolean, locale: Locale): String {
    val value = if (!imperial) m.value else if (m.kind == MeasurementKind.WEIGHT) m.value * 2.2046226218 else m.value / 2.54
    val unit = if (!imperial) m.kind.unit else if (m.kind == MeasurementKind.WEIGHT) "lb" else "in"
    return String.format(locale, "%.1f %s", value, unit)
}

@Composable private fun MeasurementScreen(state: AppState, model: MetricMeViewModel, back: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { TextButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("Your profile") } }
        item { PageTitle("The full picture", "Measurement history", "Latest timestamp wins. Past target snapshots stay unchanged when you make a correction today.") }
        if (state.measurements.isEmpty()) item { Panel { Text("Nothing recorded yet", style = MaterialTheme.typography.titleLarge); Text("Add a measurement from your profile to begin.") } }
        items(state.measurements, key = { it.id }) { record ->
            Panel {
                Text(record.kind.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                Text(displayMeasurement(record, state.profile?.imperialDisplay == true, locale), style = MaterialTheme.typography.headlineMedium)
                Text("${record.date} · ${record.instant.atZone(ZoneId.of(record.zoneId)).format(DateTimeFormatter.ofPattern("HH:mm z", locale))}", style = MaterialTheme.typography.bodySmall)
                BadgeText(if (record.acquisition == Acquisition.MEASURED) "Measured" else "Manually entered")
                if (!state.demo) Row {
                    TextButton(onClick = { model.openMeasurement(record.kind, record) }, enabled = !state.saving) { Text("Edit") }
                    TextButton(onClick = { deleting = record.id }, enabled = !state.saving) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (deleting != null) AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this measurement?") }, text = { Text("This removes the measurement from your history. Stored historical target snapshots remain unchanged.") }, confirmButton = { TextButton(onClick = { deleting?.let(model::deleteMeasurement); deleting = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep it") } })
}

@Composable internal fun ErrorMessage(message: String, dismiss: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(16.dp)) { Text(message, color = MaterialTheme.colorScheme.onErrorContainer); if (dismiss != null) TextButton(onClick = dismiss) { Text("Dismiss") } }
    }
}

@Composable private fun FormDialog(model: MetricMeViewModel, state: AppState, kind: String) {
    Dialog(onDismissRequest = model::closeForm, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !state.saving, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = { Row(Modifier.statusBarsPadding().fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = model::closeForm, enabled = !state.saving) { Icon(Icons.Outlined.Close, null); Spacer(Modifier.width(5.dp)); Text("Cancel") }; Spacer(Modifier.weight(1f)); Text("SAVED ON DEVICE", style = MaterialTheme.typography.labelSmall) } },
                bottomBar = { Surface(shadowElevation = 4.dp) { Column(Modifier.navigationBarsPadding().imePadding().padding(20.dp).fillMaxWidth()) {
                    Button(onClick = { when (kind) { "profile" -> model.saveProfile(); "measurement" -> model.saveMeasurement(); "target" -> model.saveTarget() } }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        if (state.saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                        Text(if (state.saving) "Saving…" else "Save ${if (kind == "measurement") "measurement" else kind}")
                    }
                } } }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (state.error != null) ErrorMessage(state.error)
                    when (kind) { "profile" -> ProfileForm(model); "measurement" -> MeasurementForm(model, state); "target" -> TargetForm(model) }
                }
            }
        }
    }
}

@Composable internal fun Field(label: String, value: String, change: (String) -> Unit, hint: String? = null, numeric: Boolean = false, multiline: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, supportingText = hint?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(), singleLine = !multiline, minLines = if (multiline) 2 else 1, keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text), shape = RoundedCornerShape(14.dp))
}
@Composable private fun ProfileForm(model:MetricMeViewModel) {
    val name by model.name.collectAsStateWithLifecycle()
    val birth by model.birthDate.collectAsStateWithLifecycle()
    val gender by model.gender.collectAsStateWithLifecycle()
    Text("Your details",style=MaterialTheme.typography.headlineMedium)
    Field("Name",name,{ model.text("name",it) })
    Field("Date of birth",birth,{ model.text("birthDate",it) },"YYYY-MM-DD · used to calculate age")
    Field("Gender",gender,{ model.text("gender",it) })
}
@Composable internal fun Choice(label: String, selected: Boolean, click: () -> Unit) {
    Surface(onClick = click, shape = RoundedCornerShape(14.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton; this.selected = selected }) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = selected, onClick = null); Spacer(Modifier.width(8.dp)); Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
    }
}
@Composable internal fun CheckRow(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = change, modifier = Modifier.semantics { contentDescription = title })
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyMedium); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable private fun MeasurementForm(model: MetricMeViewModel, state: AppState) {
    val kind by model.measurementKind.collectAsStateWithLifecycle(); val value by model.measurementValue.collectAsStateWithLifecycle()
    val date by model.measurementDate.collectAsStateWithLifecycle(); val acquisition by model.acquisition.collectAsStateWithLifecycle()
    val unusual by model.unusual.collectAsStateWithLifecycle()
    val type = MeasurementKind.valueOf(kind)
    val previous = HealthRules.effectiveMeasurement(state.measurements, type, Instant.now())
    PageTitle("A moment in your journey", "Record ${type.name.lowercase()}", "Corrections are welcome. Cancelling keeps your previous values.")
    Field("${type.name.lowercase().replaceFirstChar { it.uppercase() }} (${type.unit})", value, { model.text("measurementValue", it); model.flag("unusual", false) }, "Stored in ${type.unit}", numeric = true)
    Field("Measurement date", date, { model.text("measurementDate", it) }, "YYYY-MM-DD · no future dates")
    Text("How was this value obtained?", style = MaterialTheme.typography.titleMedium)
    Choice("Measured with a scale or measuring tool", acquisition == Acquisition.MEASURED.name, { model.text("acquisition", Acquisition.MEASURED.name) })
    Choice("Manually entered from memory or notes", acquisition == Acquisition.MANUALLY_ENTERED.name, { model.text("acquisition", Acquisition.MANUALLY_ENTERED.name) })
    val numeric = value.toDoubleOrNull()
    if (numeric != null && HealthRules.validateMeasurement(type, numeric) == null && HealthRules.requiresConfirmation(type, numeric, previous?.value)) {
        Panel {
            Text("Let’s double-check that value", style = MaterialTheme.typography.titleMedium)
            Text("This is outside the usual range or differs substantially from the previous measurement. Check the units and decimal point.")
            CheckRow("I checked this measurement", "Save this value as entered.", unusual) { model.flag("unusual", it) }
        }
    }
    Text("Same-day entries use their recorded time; the latest entry is used for current estimates. Backdated entries use noon in your local timezone.", style = MaterialTheme.typography.bodySmall)
}
@Composable private fun TargetForm(model: MetricMeViewModel) {
    val target by model.manualTarget.collectAsStateWithLifecycle()
    PageTitle("Your own pace", "Daily energy target", "Automatic estimates use maintenance, with no weight-loss deficit.")
    Field("Manual target (kcal/day)", target, { model.text("manualTarget", it) }, "Your own target; not a recommendation", numeric = true)
    Text("Targets are saved from today onward. Historical dashboards will use the snapshot in effect on that day.", style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = { model.saveTarget(automatic = true) }, modifier = Modifier.fillMaxWidth()) { Text("Use automatic estimate") }
    Text("If required inputs are missing or automatic recommendations are disabled, the estimate remains unavailable. Logging stays available.", style = MaterialTheme.typography.bodySmall)
}
