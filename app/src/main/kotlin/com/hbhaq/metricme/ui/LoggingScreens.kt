package com.hbhaq.metricme.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hbhaq.metricme.data.*
import com.hbhaq.metricme.domain.*
import com.hbhaq.metricme.reminders.notificationsAllowed
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlin.math.roundToInt

@Composable private fun RefreshOnResume(model: LoggingViewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refresh() }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
@Composable private fun DayNavigator(state: LoggingState, model: LoggingViewModel) {
    val locale = LocalLocale.current.platformLocale
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = model::previousDay) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous day") }
        Text(state.date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { model.chooseDate(model.today()) }) { Text("Today") }
        IconButton(onClick = model::nextDay, enabled = state.date < model.today()) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next day") }
    }
}
@Composable private fun LoggingError(state: LoggingState, model: LoggingViewModel) {
    state.error?.let { ErrorMessage(it, model::clearError); TextButton(onClick = model::retry) { Text("Retry local records") } }
}

@Composable fun ManualTodayScreen(profile: AppState, profileModel: MetricMeViewModel, state: LoggingState, model: LoggingViewModel, routines: () -> Unit) {
    RefreshOnResume(model)
    val day = state.date; val entries = state.logs.on(day); val calories = state.logs.calories(day)
    val steps = state.logs.steps(day); val sleep = state.logs.sleepMinutes(day)
    val target = HealthRules.historicalTarget(profile.targets, day)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageTitle("One day at a time", profile.profile?.name?.takeIf { it.isNotBlank() }?.let { "Make room for you, $it." } ?: "Small steps. More you.", "Your journal works offline. Every entry stays on this device.") }
        item { DayNavigator(state, model) }
        item { LoggingError(state, model) }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading local logs" }) }
        item {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Spa, null); Spacer(Modifier.width(8.dp)); Text("Your daily nourishment", style = MaterialTheme.typography.titleMedium) }
                    Text(calories?.let { "${it.roundToInt()} kcal logged" } ?: "A fresh page for today", style = MaterialTheme.typography.headlineMedium)
                    val food = entries.filter { it.kind == LogKind.FOOD }
                    Text(if (food.isEmpty()) "Food is not logged yet. Missing entries never count as zero." else "${food.size} food ${if (food.size == 1) "entry" else "entries"} · a logged subtotal, not proof of a complete day.", style = MaterialTheme.typography.bodyMedium)
                    val unknown = food.count { (it.input as FoodLog).consumedCalories == null }
                    if (unknown > 0) Text("$unknown food entries have unknown calories.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .2f))
                    Text(target?.calories?.let { "${it.roundToInt()} kcal ${if (target.manual) "manual target" else "estimated maintenance"}" } ?: "No calorie target set", style = MaterialTheme.typography.labelLarge)
                    Text("Manually entered · confirmed entries only", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniMetric("Movement", steps.count?.toString() ?: "—", if (steps.count == null) "Steps not logged" else "reported steps", Icons.AutoMirrored.Outlined.DirectionsWalk, Modifier.weight(1f))
            MiniMetric("Rest", sleep?.let { "${it / 60}h ${it % 60}m" } ?: "—", if (sleep == null) "Sleep not logged" else "logged sleep", Icons.Outlined.Bedtime, Modifier.weight(1f))
        } }
        if (steps.count != null) item { Text(steps.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { SectionLabel("Your routines", "${state.routines.on(day).size} SCHEDULED") }
        val occurrences = state.routines.on(day)
        if (occurrences.isEmpty()) item { Panel { Text("A little structure, your way", style = MaterialTheme.typography.titleLarge); Text("Add a habit, vitamin or medication schedule. An unrecorded occurrence stays unrecorded, even if an alert is missed."); FilledTonalButton(onClick = routines) { Text("Manage routines") } } }
        else {
            items(occurrences, key = { it.id }) { OccurrenceRow(it, model, state.busy) }
            item { TextButton(onClick = routines, modifier = Modifier.fillMaxWidth()) { Text("Manage habits, medications & reminders") } }
        }
        item { Panel {
            Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.secondary)
            Text("Your profile, at your pace", style = MaterialTheme.typography.titleLarge)
            Text("Optional details support estimates. You can keep logging without them.", style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = profileModel::openProfile, modifier = Modifier.fillMaxWidth()) { Text(if (profile.profile == null) "Set up your profile" else "Edit your profile") }
        } }
        item { OutlinedButton(onClick = { profileModel.setDemo(true) }, modifier = Modifier.fillMaxWidth()) { Text("Explore with sample data") } }
    }
}

@Composable fun ManualJournalScreen(state: LoggingState, model: LoggingViewModel, measurements: () -> Unit) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var deletePrn by rememberSaveable { mutableStateOf<String?>(null) }
    val locale = LocalLocale.current.platformLocale
    val records = state.logs.on(state.date)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageTitle("Your record", "Every day tells a story.", "Correct a detail whenever you need. Cancel keeps the saved entry.") }
        item { DayNavigator(state, model) }
        item { LoggingError(state, model) }
        if (records.isEmpty()) item { Panel { Icon(Icons.AutoMirrored.Outlined.MenuBook, null); Text("No logs for this day", style = MaterialTheme.typography.titleLarge); Text("Use Log to add food, activity, steps or sleep. Unlogged data is unknown, not zero.") } }
        items(records, key = { it.id }) { row -> Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { BadgeText(row.kind.label); Text(row.instant.atZone(ZoneId.of(row.zone)).format(DateTimeFormatter.ofPattern("HH:mm XXX", locale)), style = MaterialTheme.typography.bodySmall) }
            Text(row.title, style = MaterialTheme.typography.titleLarge)
            Text(row.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (row.input as? FoodLog)?.let { food -> Text(food.consumedCalories?.let { "${it.roundToInt()} kcal consumed" } ?: "Calories not recorded", style = MaterialTheme.typography.bodyMedium); Text("Protein ${food.consumedProtein?.roundToInt()?.toString() ?: "unknown"} · Carbs ${food.consumedCarbs?.roundToInt()?.toString() ?: "unknown"} · Fat ${food.consumedFat?.roundToInt()?.toString() ?: "unknown"} (g)", style = MaterialTheme.typography.bodySmall) }
            BadgeText("Manually entered · confirmed")
            Row { TextButton(onClick = { model.openLog(row.kind, row) }, enabled = !state.busy) { Text("Edit ${row.kind.label.lowercase()}") }; TextButton(onClick = { deleting = row.id }, enabled = !state.busy) { Text("Delete", color = MaterialTheme.colorScheme.error) } }
        } }
        val prn = state.routines.prn.filter { Instant.ofEpochMilli(it.instant).atZone(ZoneId.of(it.zoneId)).toLocalDate() == state.date }
        items(prn, key = { it.id }) { row -> Panel { BadgeText("As needed · not scheduled"); Text(row.labelSnapshot, style = MaterialTheme.typography.titleMedium); Text("Recorded taken · ${Instant.ofEpochMilli(row.instant).atZone(ZoneId.of(row.zoneId)).format(DateTimeFormatter.ofPattern("HH:mm", locale))}"); if (row.notes.isNotBlank()) Text(row.notes); TextButton(onClick = { deletePrn = row.id }) { Text("Delete as-needed record") } } }
        if (state.routines.on(state.date).isNotEmpty()) item { SectionLabel("Scheduled occurrences") }
        items(state.routines.on(state.date), key = { "occ:${it.id}" }) { OccurrenceRow(it, model, state.busy) }
        item { OutlinedButton(onClick = measurements, modifier = Modifier.fillMaxWidth()) { Text("Measurement history") } }
    }
    if (deleting != null || deletePrn != null) AlertDialog(onDismissRequest = { deleting = null; deletePrn = null }, title = { Text("Delete this record?") }, text = { Text("This removes the saved record and its components from this device.") }, confirmButton = { TextButton(onClick = { records.firstOrNull { it.id == deleting }?.let(model::delete); deletePrn?.let(model::deletePrn); deleting = null; deletePrn = null }) { Text("Delete record") } }, dismissButton = { TextButton(onClick = { deleting = null; deletePrn = null }) { Text("Keep record") } })
}

@Composable private fun OccurrenceRow(card: OccurrenceCard, model: LoggingViewModel, busy: Boolean) {
    val locale = LocalLocale.current.platformLocale
    val terminal = card.status in setOf(OccurrenceStatus.COMPLETED, OccurrenceStatus.TAKEN, OccurrenceStatus.SKIPPED)
    Panel {
        Text(card.label, style = MaterialTheme.typography.titleMedium)
        Text(card.scheduled.atZone(ZoneId.of(card.zone)).format(DateTimeFormatter.ofPattern("HH:mm XXX", locale)), style = MaterialTheme.typography.bodySmall)
        BadgeText(when (card.status) { OccurrenceStatus.NOT_RECORDED -> "Not recorded"; OccurrenceStatus.COMPLETED -> "Completed"; OccurrenceStatus.TAKEN -> "Taken"; OccurrenceStatus.SKIPPED -> "Skipped"; OccurrenceStatus.SNOOZED -> "Snoozed"; OccurrenceStatus.CANCELLED -> "Cancelled" })
        card.snoozedUntil?.let { Text("Reminder snoozed until ${it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", locale))}", style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (terminal) TextButton(onClick = { model.action(card, OccurrenceAction.UNDO) }, enabled = !busy) { Text("Undo status") }
            else {
                FilledTonalButton(onClick = { model.action(card, OccurrenceAction.DONE) }, enabled = !busy) { Text(if (card.kind == RoutineKind.MEDICATION) "Taken" else "Done") }
                TextButton(onClick = { model.action(card, OccurrenceAction.SKIP) }, enabled = !busy) { Text("Skip") }
                if (card.kind == RoutineKind.MEDICATION) TextButton(onClick = { model.action(card, OccurrenceAction.SNOOZE) }, enabled = !busy) { Text("Snooze 10 min") }
            }
        }
    }
}

@Composable fun RoutinesScreen(state: LoggingState, model: LoggingViewModel, demo: Boolean, back: () -> Unit) {
    if (demo) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { PageTitle("Sample mode", "Routines stay private", "Exit demo to view or change personal routines and reminders."); TextButton(onClick = back) { Text("Back") } }
        return
    }
    RefreshOnResume(model)
    var archive by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var permissionTick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) { val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissionTick++ }; owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> model.enableReminders(granted); permissionTick++ }
    val allowed = remember(permissionTick) { notificationsAllowed(context) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { TextButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Text("Back") } }
        item { PageTitle("Small, steady rituals", "Your routines", "Habit and vitamin check-ins, medication occurrences, and private local reminders.") }
        item { LoggingError(state, model) }
        item { Panel {
            Icon(Icons.Outlined.NotificationsNone, null, tint = MaterialTheme.colorScheme.secondary)
            Text("Reminders, with privacy", style = MaterialTheme.typography.titleLarge)
            Text(if (state.routines.remindersEnabled && allowed) "Enabled · timing is best effort" else if (!allowed) "Notifications are off or blocked. Logging and status tracking still work." else "Reminders are off. Enable only if you want local notifications.")
            Text("Names and dose text stay out of notification previews. Android may delay alerts, especially with battery restrictions. After force-stop, open MetricMe to restore scheduling.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                if (state.routines.remindersEnabled) model.enableReminders(false)
                else if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else model.enableReminders(true)
            }, enabled = !demo && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(if (state.routines.remindersEnabled) "Turn reminders off" else "Enable reminders") }
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }, modifier = Modifier.fillMaxWidth(), enabled = !demo) { Text("Android notification settings") }
            Text("No exact-alarm access is required. A missed alert never marks a medication missed or taken.", style = MaterialTheme.typography.bodySmall)
        } }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilledTonalButton(onClick = { model.openRoutine(RoutineKind.HABIT) }, enabled = !demo) { Text("Add habit or vitamin") }; FilledTonalButton(onClick = { model.openRoutine(RoutineKind.MEDICATION) }, enabled = !demo) { Text("Add medication") } } }
        if (state.routines.definitions.isEmpty()) item { Panel { Text("Start with one small routine", style = MaterialTheme.typography.titleLarge); Text("Choose the weekdays and times that fit your day. No schedule or dose is suggested for you.") } }
        items(state.routines.definitions.sortedWith(compareBy<RoutineCard> { it.archived }.thenBy { it.name }), key = { it.id }) { card -> Panel {
            BadgeText(if (card.archived) "Archived / course ended" else if (card.asNeeded) "As needed · no adherence denominator" else if (card.kind == RoutineKind.HABIT) "Habit or vitamin" else "Medication")
            Text(card.name, style = MaterialTheme.typography.titleLarge)
            if (card.dose.isNotBlank()) Text(card.dose)
            if (card.instructions.isNotBlank()) Text(card.instructions, style = MaterialTheme.typography.bodyMedium)
            if (card.notes.isNotBlank()) Text(card.notes, style = MaterialTheme.typography.bodySmall)
            if (!card.asNeeded) Text("${card.weekdays.sortedBy { it.value }.joinToString { it.name.take(3) }} · ${card.times.joinToString()}", style = MaterialTheme.typography.bodySmall)
            if (!card.archived) FlowRow {
                TextButton(onClick = { model.openRoutine(card.kind, card) }, enabled = !demo && !state.busy) { Text("Edit schedule") }
                if (card.asNeeded) TextButton(onClick = { model.openPrn(card) }, enabled = !demo && !state.busy) { Text("Record as-needed use") }
                TextButton(onClick = { archive = card.id }, enabled = !demo && !state.busy) { Text("Archive") }
            }
        } }
    }
    if (archive != null) AlertDialog(onDismissRequest = { archive = null }, title = { Text("Archive this routine?") }, text = { Text("Future pending occurrences and reminders will be cancelled; past history stays. Archiving in MetricMe does not change medication instructions.") }, confirmButton = { TextButton(onClick = { state.routines.definitions.firstOrNull { it.id == archive }?.let(model::archive); archive = null }) { Text("Archive routine") } }, dismissButton = { TextButton(onClick = { archive = null }) { Text("Keep routine") } })
}

@Composable fun LogPicker(onDismiss: () -> Unit, onLog: (LogKind) -> Unit, onRoutine: (RoutineKind) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("What would you like to log?") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LogKind.entries.forEach { kind -> OutlinedButton(onClick = { onLog(kind) }, modifier = Modifier.fillMaxWidth()) { Text(kind.label) } }
        OutlinedButton(onClick = { onRoutine(RoutineKind.HABIT) }, modifier = Modifier.fillMaxWidth()) { Text("Habit or vitamin") }
        OutlinedButton(onClick = { onRoutine(RoutineKind.MEDICATION) }, modifier = Modifier.fillMaxWidth()) { Text("Medication") }
    } }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable fun LoggingEditor(model: LoggingViewModel, state: LoggingState, mode: String) {
    val fields by model.fields.collectAsStateWithLifecycle()
    fun v(key: String) = fields[key].orEmpty()
    Dialog(onDismissRequest = model::close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !state.busy, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(topBar = { Row(Modifier.statusBarsPadding().fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = model::close, enabled = !state.busy) { Icon(Icons.Outlined.Close, null); Text("Cancel") }; Spacer(Modifier.weight(1f)); Text("ON DEVICE", style = MaterialTheme.typography.labelSmall) } },
                bottomBar = { Surface(shadowElevation = 4.dp) { Button(onClick = model::save, enabled = !state.busy, modifier = Modifier.navigationBarsPadding().imePadding().padding(20.dp).fillMaxWidth().heightIn(min = 52.dp)) { if (state.busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }; Text(if (state.busy) "Saving…" else "Save entry") } } }
            ) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    state.error?.let { ErrorMessage(it) }
                    when {
                        mode.startsWith("log:") -> {
                            val kind = LogKind.valueOf(mode.substringAfter(':'))
                            PageTitle("A little detail, a clearer day", "Log ${kind.label.lowercase()}", "Blank optional fields mean unknown. Save confirms only what you enter.")
                            when (kind) {
                                LogKind.FOOD -> {
                                    EditorField(model, fields, "name", "Food name")
                                    SectionLabel("Meal")
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Breakfast", "Lunch", "Dinner", "Snack", "Other").forEach { meal -> FilterChip(selected = v("meal") == meal, onClick = { model.change("meal", meal) }, label = { Text(meal) }) } }
                                    EditorField(model, fields, "quantity", "Quantity", numeric = true)
                                    EditorField(model, fields, "unit", "Unit", "For example: g, bowl, slice or serving")
                                    EditorField(model, fields, "portion", "Portion consumed (%)", "0 means none consumed; 100 means the full quantity", numeric = true)
                                    SectionLabel("Nutrition for the full quantity")
                                    EditorField(model, fields, "calories", "Calories (optional)", "kcal for the full quantity; consumed portion scales this", numeric = true)
                                    EditorField(model, fields, "protein", "Protein (g, optional)", numeric = true)
                                    EditorField(model, fields, "carbs", "Carbohydrates (g, optional)", numeric = true)
                                    EditorField(model, fields, "fat", "Fat (g, optional)", numeric = true)
                                }
                                LogKind.ACTIVITY -> {
                                    EditorField(model, fields, "name", "Activity name", "For example: walk, strength workout, cycling")
                                    EditorField(model, fields, "minutes", "Duration (minutes, optional)", numeric = true)
                                    EditorField(model, fields, "steps", "Reported steps (optional)", "Only steps you actually measured or reported; no inferred steps", numeric = true)
                                    EditorField(model, fields, "intensity", "Perceived intensity (optional)", "Your own description, such as easy or moderate")
                                    EditorField(model, fields, "exercises", "Workout exercises (optional)", "One per line: name | sets | reps | load in kg. Example: Squat | 3 | 8 | 40. Leave unknown fields blank.", multiline = true)
                                    EditorField(model, fields, "notes", "Activity notes (optional)", multiline = true)
                                }
                                LogKind.STEPS -> {
                                    EditorField(model, fields, "steps", "Daily step total", "0 is a recorded zero. A daily total replaces walk subtotals, rather than adding to them.", numeric = true)
                                }
                                LogKind.SLEEP -> {
                                    EditorField(model, fields, "bed", "Bedtime", "YYYY-MM-DDTHH:mm")
                                    EditorField(model, fields, "bedZone", "Bedtime timezone")
                                    EditorField(model, fields, "bedOffset", "Bedtime offset (if repeated time)", "Leave blank normally; use +01:00 or +00:00 for a repeated clock time")
                                    EditorField(model, fields, "wake", "Wake time", "YYYY-MM-DDTHH:mm · sleep belongs to this date")
                                    EditorField(model, fields, "wakeZone", "Wake timezone")
                                    EditorField(model, fields, "wakeOffset", "Wake offset (if repeated time)")
                                    EditorField(model, fields, "quality", "Sleep quality (optional)")
                                    CheckRow("Confirm overlap if sessions intersect", "If this overlaps another session, daily duration counts shared minutes once.", v("overlap") == "true") { model.change("overlap", it.toString()) }
                                }
                            }
                            if (kind != LogKind.SLEEP) EventFields(model, fields)
                        }
                        mode == "prn" -> {
                            PageTitle("Your own instructions", "Record as-needed use", "${v("name")} · ${v("dose")}")
                            if (v("instructions").isNotBlank()) Text(v("instructions"))
                            Text("This records a taken event. It does not create a scheduled dose or affect scheduled adherence.", style = MaterialTheme.typography.bodyMedium)
                            EventFields(model, fields)
                            EditorField(model, fields, "notes", "Notes (optional)", multiline = true)
                        }
                        else -> RoutineFields(model, fields, RoutineKind.valueOf(mode.substringAfter(':')))
                    }
                }
            }
        }
    }
}
@Composable private fun EditorField(model: LoggingViewModel, fields: Map<String, String>, key: String, label: String, hint: String? = null, numeric: Boolean = false, multiline: Boolean = false) = Field(label, fields[key].orEmpty(), { model.change(key, it) }, hint, numeric, multiline)
@Composable private fun EventFields(model: LoggingViewModel, fields: Map<String, String>) {
    SectionLabel("When it happened")
    EditorField(model, fields, "time", "Event date and time", "YYYY-MM-DDTHH:mm · no future entries")
    EditorField(model, fields, "zone", "Event timezone", "IANA name, for example Asia/Karachi")
    EditorField(model, fields, "offset", "UTC offset (if repeated time)", "Leave blank normally. Repeated clock times need an offset such as +01:00.")
}
@Composable private fun RoutineFields(model: LoggingViewModel, fields: Map<String, String>, kind: RoutineKind) {
    val prn = fields["asNeeded"] == "true"
    val locale = LocalLocale.current.platformLocale
    PageTitle("Make it fit your day", if (kind == RoutineKind.HABIT) "Habit or vitamin" else "Medication schedule", "Schedule edits affect future pending occurrences. Completed history stays.")
    EditorField(model, fields, "name", if (kind == RoutineKind.HABIT) "Habit or vitamin name" else "Medication name")
    if (kind == RoutineKind.MEDICATION) {
        EditorField(model, fields, "dose", "Dose text (as instructed)", "Copy the instructions you already have; MetricMe does not choose a dose")
        EditorField(model, fields, "instructions", "Medication instructions (optional)", multiline = true)
        CheckRow("As-needed medication (PRN)", "No scheduled occurrences or adherence denominator.", prn) { model.change("asNeeded", it.toString()) }
        Text("For a missed-dose question, use your medication instructions or ask a pharmacist or clinician. MetricMe does not recommend changing, stopping or doubling a dose.", style = MaterialTheme.typography.bodySmall)
    }
    EditorField(model, fields, "notes", "Routine notes (optional)", multiline = true)
    EditorField(model, fields, "start", "Start date", "YYYY-MM-DD · edits begin no earlier than now")
    EditorField(model, fields, "lastDay", "Last day (optional)", "YYYY-MM-DD · no new occurrences after this date")
    if (!prn) {
        SectionLabel("Selected weekdays")
        val days = RoutineRepository.days(fields["weekdays"].orEmpty())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) { DayOfWeek.entries.forEach { day -> FilterChip(selected = day in days, onClick = { model.change("weekdays", RoutineRepository.encodeDays(if (day in days) days - day else days + day)) }, label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) }, modifier = Modifier.semantics { contentDescription = day.getDisplayName(TextStyle.FULL, locale) }) } }
        EditorField(model, fields, "times", "Local reminder times", "HH:mm, separated by commas · up to 8, e.g. 09:00, 21:00")
        Text("Reminders follow the device's local wall clock. On clock changes, nonexistent times shift forward and repeated times occur once, using the earlier offset. Delivery is best effort.", style = MaterialTheme.typography.bodySmall)
    }
}
