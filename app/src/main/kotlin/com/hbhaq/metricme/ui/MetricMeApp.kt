package com.hbhaq.metricme.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.hbhaq.metricme.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("today", "Today", Icons.Outlined.WbSunny),
    Destination("journal", "Journal", Icons.AutoMirrored.Outlined.MenuBook),
    Destination("insights", "Insights", Icons.Outlined.BarChart),
    Destination("you", "You", Icons.Outlined.PersonOutline)
)

@Composable
fun MetricMeApp(model: MetricMeViewModel, logging: LoggingViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val form by model.formKind.collectAsStateWithLifecycle()
    val logs by logging.state.collectAsStateWithLifecycle()
    val logEditor by logging.editor.collectAsStateWithLifecycle()
    var picker by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.demo) { logging.setDemo(state.demo) }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "today"
    Scaffold(
        floatingActionButton = {
            if (!state.demo && !state.loading) ExtendedFloatingActionButton(onClick = { picker = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Log") })
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                destinations.forEach { destination ->
                    NavigationBarItem(selected = route == destination.route || (route in setOf("measurements", "routines") && destination.route == "you"),
                        onClick = { nav.navigate(destination.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(destination.icon, contentDescription = null) }, label = { Text(destination.label) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text("m", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(9.dp)); Text("metricme", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                BadgeText(if (state.demo) "SAMPLE DATA" else "ON DEVICE", if (state.demo) Icons.Outlined.Science else Icons.Outlined.Lock)
            }
            if (state.demo) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sample data · explore freely", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { model.setDemo(false) }) { Text("Exit demo") }
                }
            }
            if (state.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading local profile" }) }
            else {
                if (state.error != null && form.isEmpty()) ErrorMessage(state.error!!, model::clearError)
                NavHost(navController = nav, startDestination = "today", modifier = Modifier.weight(1f)) {
                    composable("today") { if (state.demo) TodayScreen(state, model.today(), model::openProfile, { nav.navigate("you") }, { model.setDemo(true) }) else ManualTodayScreen(state, model, logs, logging, { nav.navigate("routines") }) }
                    composable("journal") { if (state.demo) JournalScreen(state, { nav.navigate("measurements") }) else ManualJournalScreen(logs, logging, { nav.navigate("measurements") }) }
                    composable("insights") { InsightsScreen() }
                    composable("you") { YouScreen(state, model, { nav.navigate("measurements") }, { nav.navigate("routines") }) }
                    composable("measurements") { MeasurementScreen(state, model) { nav.popBackStack() } }
                    composable("routines") { RoutinesScreen(logs, logging, state.demo) { nav.popBackStack() } }
                }
            }
        }
    }
    if (form.isNotEmpty()) FormDialog(model, state, form)
    if (logEditor.isNotEmpty() && !state.demo) LoggingEditor(logging, logs, logEditor)
    if (picker && !state.demo) LogPicker(onDismiss = { picker = false }, onLog = { picker = false; logging.openLog(it) }, onRoutine = { picker = false; logging.openRoutine(it) })
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

@Composable private fun TodayScreen(state: AppState, date: LocalDate, setup: () -> Unit, profile: () -> Unit, demo: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val target = HealthRules.historicalTarget(state.targets, date)
    val greeting = state.profile?.name?.takeIf { it.isNotBlank() }?.let { "A little better, $it." } ?: "Small steps. More you."
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { PageTitle(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)), greeting, "A calm place to build your everyday habits.") }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primary) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Spa, null, Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp)); Text("Your daily nourishment", style = MaterialTheme.typography.titleMedium)
                    }
                    if (state.demo) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            val track = MaterialTheme.colorScheme.onPrimary.copy(alpha = .2f)
                            val ink = MaterialTheme.colorScheme.onPrimary
                            Box(Modifier.size(94.dp).semantics { contentDescription = "Sample intake 1,680 calories; illustrative progress only" }, contentAlignment = Alignment.Center) {
                                Canvas(Modifier.fillMaxSize()) { drawArc(track, -90f, 360f, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round)); drawArc(ink, -90f, 260f, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round)) }
                                Icon(Icons.Outlined.Restaurant, null, Modifier.size(28.dp))
                            }
                            Column { Text("1,680", style = MaterialTheme.typography.displaySmall); Text("kcal · sample intake", style = MaterialTheme.typography.bodyMedium) }
                        }
                    } else {
                        Text("A fresh page for today", style = MaterialTheme.typography.headlineMedium)
                        Text("Food is not logged yet. Missing entries never count as zero.", style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .2f))
                    Text(target?.calories?.let { "${it.roundToInt()} kcal ${if (target.manual) "manual target" else "estimated maintenance"}" } ?: "No calorie target set", style = MaterialTheme.typography.labelLarge)
                    Text(if (state.demo) "Sample data · not a record of your day" else "Manual food logging arrives in Stage 2.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniMetric("Movement", if (state.demo) "6,240" else "—", if (state.demo) "steps · sample" else "Steps not logged", Icons.AutoMirrored.Outlined.DirectionsWalk, Modifier.weight(1f))
                MiniMetric("Rest", if (state.demo) "7h 25m" else "—", if (state.demo) "sleep · sample" else "Sleep not logged", Icons.Outlined.Bedtime, Modifier.weight(1f))
            }
        }
        item {
            SectionLabel("Make room for yourself", "START HERE")
            Spacer(Modifier.height(12.dp))
            Panel {
                Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.secondary)
                Text(if (state.profile == null) "Your routine starts with you" else "Your profile, at your pace", style = MaterialTheme.typography.titleLarge)
                Text("Add what you are comfortable sharing. Logging will work even without calorie estimates.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = if (state.demo) profile else setup, modifier = Modifier.fillMaxWidth()) { Text(if (state.demo) "Explore sample profile" else if (state.profile == null) "Set up your profile" else "Edit your profile"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp)) }
            }
        }
        if (!state.demo) item { OutlinedButton(onClick = demo, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Science, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Explore with sample data") } }
        item { Text("Built around progress, not perfection.", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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

@Composable private fun JournalScreen(state: AppState, measurements: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { PageTitle("Your record", "Every day tells a story.", "Read-only sample history. Exit demo to log your own food, sleep, activity and routines.") }
        item { Panel {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(32.dp))
            Text("A place for the little things", style = MaterialTheme.typography.titleLarge)
            Text(if (state.measurements.isEmpty()) "Start with a height or weight entry. You can correct it whenever you need." else "${state.measurements.size} ${if (state.demo) "sample " else ""}measurements in your history.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = measurements) { Text("Open measurement history") }
        } }
        item { PrivacyNote() }
    }
}

@Composable private fun InsightsScreen() {
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { PageTitle("The bigger picture", "Patterns, with perspective.", "Insights and coaching arrive in Stage 4, once logging is in place.") }
        item { Panel {
            Icon(Icons.Outlined.BarChart, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.secondary)
            Text("Your data deserves context", style = MaterialTheme.typography.titleLarge)
            Text("Weekly averages will include logging coverage. Sparse data will never be presented as a trend.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            BadgeText("Not enough data yet")
        } }
    }
}

@Composable private fun YouScreen(state: AppState, model: MetricMeViewModel, history: () -> Unit, routines: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val target = HealthRules.historicalTarget(state.targets, model.today())
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { PageTitle("Your foundation", "A routine that fits you.", "Your information stays on this device. Share only what feels right.") }
        item { OutlinedButton(onClick = routines, enabled = !state.demo, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Checklist, null); Spacer(Modifier.width(8.dp)); Text("Habits, medications & reminders") } }
        item { Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PersonOutline, null, Modifier.size(28.dp)) }
                Column(Modifier.weight(1f)) { Text(state.profile?.name?.takeIf { it.isNotBlank() } ?: "Your profile", style = MaterialTheme.typography.titleLarge); Text(state.profile?.activity?.label ?: "A few details, at your pace", style = MaterialTheme.typography.bodyMedium) }
            }
            state.profile?.goals?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.demo) BadgeText("Read-only sample profile") else OutlinedButton(onClick = model::openProfile, modifier = Modifier.fillMaxWidth()) { Text(if (state.profile == null) "Create profile" else "Edit profile") }
        } }
        item { SectionLabel("Body measurements", "UPDATE MONTHLY") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MeasurementKind.entries.forEach { kind ->
                    val measurement = HealthRules.effectiveMeasurement(state.measurements, kind, Instant.now())
                    Panel {
                        Text(if (kind == MeasurementKind.WEIGHT) "Weight" else "Height", style = MaterialTheme.typography.titleMedium)
                        Text(measurement?.let { displayMeasurement(it, state.profile?.imperialDisplay == true, locale) } ?: "Not recorded", style = MaterialTheme.typography.headlineMedium)
                        if (measurement != null) {
                            Text("${measurement.date} · ${if (measurement.acquisition == Acquisition.MEASURED) "Measured" else "Manually entered"}", style = MaterialTheme.typography.bodySmall)
                            if (HealthRules.stale(measurement, model.today())) BadgeText("Over 30 days old · time for an update")
                        }
                        if (!state.demo) FilledTonalButton(onClick = { model.openMeasurement(kind) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add ${kind.name.lowercase()}") }
                    }
                }
                TextButton(onClick = history, modifier = Modifier.fillMaxWidth()) { Text("View and correct measurement history"); Icon(Icons.Outlined.ChevronRight, null) }
            }
        }
        item { Panel {
            Text("Daily energy target", style = MaterialTheme.typography.titleMedium)
            Text(target?.calories?.let { "${it.roundToInt()} kcal" } ?: "Not available", style = MaterialTheme.typography.headlineMedium)
            BadgeText(if (target?.manual == true) "Manually entered" else "Estimate · not a prescription")
            Text(target?.explanation ?: "Add optional profile inputs, height, and weight for an estimate. You can also set your own target.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            target?.bmr?.let { Text("Estimated BMR ${it.roundToInt()} kcal/day. Activity is already included in maintenance; exercise is not added twice.", style = MaterialTheme.typography.bodySmall) }
            if (!state.demo) OutlinedButton(onClick = model::openTarget, modifier = Modifier.fillMaxWidth()) { Text("Manage target") }
        } }
        item { Panel {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Explore demo mode", style = MaterialTheme.typography.titleMedium); Text("Read-only samples, separate from your records.", style = MaterialTheme.typography.bodySmall) }; Switch(checked = state.demo, onCheckedChange = model::setDemo, modifier = Modifier.semantics { contentDescription = "Explore demo mode" }) }
        } }
        item { PrivacyNote() }
    }
}

@Composable private fun PrivacyNote() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Local storage uses Android’s app sandbox and device encryption. The database is not separately encrypted. No health data leaves this build.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
@Composable private fun ProfileForm(model: MetricMeViewModel) {
    val name by model.name.collectAsStateWithLifecycle(); val birth by model.birthDate.collectAsStateWithLifecycle()
    val gender by model.gender.collectAsStateWithLifecycle(); val equation by model.equation.collectAsStateWithLifecycle()
    val activity by model.activity.collectAsStateWithLifecycle(); val disabled by model.disableAuto.collectAsStateWithLifecycle()
    val goals by model.goals.collectAsStateWithLifecycle(); val restrictions by model.restrictions.collectAsStateWithLifecycle()
    val imperial by model.imperial.collectAsStateWithLifecycle()
    PageTitle("Made for you", "Your profile", "All fields are optional. Adults 18+ only.")
    Field("What should we call you?", name, { model.text("name", it) })
    Field("Birthdate (optional)", birth, { model.text("birthDate", it) }, "YYYY-MM-DD · used to calculate age for estimates")
    Field("Gender identity (optional)", gender, { model.text("gender", it) }, "Never used to choose a calorie equation input")
    SectionLabel("Calorie equation input")
    Text("Mifflin–St Jeor was developed with two study groups. Select a coefficient only if you want an estimate. This is separate from gender identity; a clinician can help if neither fits.", style = MaterialTheme.typography.bodyMedium)
    Choice("No estimate / prefer not to say", equation.isEmpty(), { model.text("equation", "") })
    EquationInput.entries.forEach { input -> Choice(input.label, equation == input.name, { model.text("equation", input.name) }) }
    SectionLabel("Usual activity")
    Text("Includes routine exercise; it will not be counted again.", style = MaterialTheme.typography.bodySmall)
    ActivityLevel.entries.forEach { level -> Choice("${level.label} · ×${level.multiplier}", activity == level.name, { model.text("activity", level.name) }) }
    CheckRow("Disable automatic calorie recommendations", "For pregnancy, breastfeeding, or whenever you prefer manual targets.", disabled) { model.flag("disableAuto", it) }
    Field("What would you like to work on?", goals, { model.text("goals", it) }, "Optional goals; no aggressive deficit is created", multiline = true)
    Field("Allergies, restrictions, or preferences", restrictions, { model.text("restrictions", it) }, "Optional; available for future coaching", multiline = true)
    CheckRow("Display imperial units", "Storage and measurement entry remain in kg and cm.", imperial) { model.flag("imperial", it) }
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
