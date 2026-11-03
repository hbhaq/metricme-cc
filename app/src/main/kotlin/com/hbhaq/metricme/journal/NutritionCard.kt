package com.hbhaq.metricme.journal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hbhaq.metricme.ui.Panel
import com.hbhaq.metricme.ui.ErrorMessage

@Composable fun NutritionCard(model:NutritionViewModel,setup:()->Unit,demo:Boolean) {
    val state by model.state.collectAsStateWithLifecycle()
    val profile by model.profile.collectAsStateWithLifecycle()
    val text by model.text.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var consent by rememberSaveable { mutableStateOf(false) }
    val complete=profile.age!=null && profile.heightCm!=null && profile.weightKg!=null
    fun ask() { if(!model.repository.sessions.signedIn()) setup() else if(state.consent) model.ask() else consent=true }
    Panel {
        Text("Daily calorie target",style=MaterialTheme.typography.titleMedium)
        Text(if(demo) "Sample mode" else profile.dailyCalorieTarget?.let { it.toString()+" kcal" } ?: "Not set",style=MaterialTheme.typography.headlineMedium)
        if(!demo && profile.dailyCalorieTarget!=null) Text("AI-guided estimate · from "+profile.targetEffectiveFrom,style=MaterialTheme.typography.bodySmall)
        if(!complete) Text("Add your age, height and weight first.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={ expanded=!expanded; if(expanded && state.reply==null) ask() },enabled=complete&&!demo&&!state.busy) { Text(if(expanded) "Close recommendation" else if(profile.dailyCalorieTarget==null) "Get AI recommendation" else "Review with AI") }
        if(expanded) {
            state.error?.let { ErrorMessage(it,model::dismissError) }
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.reply?.let { reply ->
                Text(reply.message)
                if(reply.status=="READY") {
                    Text(reply.calories.toString()+" kcal / day",style=MaterialTheme.typography.titleLarge)
                    reply.maintenanceCalories?.let { Text("Estimated maintenance: "+it+" kcal",style=MaterialTheme.typography.bodySmall) }
                    Button(onClick={ model.accept { expanded=false } },enabled=!state.busy) { Text("Use this target") }
                }
            }
            OutlinedTextField(text,model::type,placeholder={ Text("Reply to AI…") },enabled=!state.busy,maxLines=4,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick=::ask,enabled=!state.busy&&text.isNotBlank()) { Text("Send") }
                TextButton(onClick=model::restart,enabled=!state.busy) { Text("Start over") }
            }
        }
    }
    if(consent) AlertDialog(onDismissRequest={ consent=false },title={ Text("Use your profile with AI?") },text={ Text("Your age, gender, height, weight and interview answers will go to MetricMe and OpenAI for a calorie estimate. Your profile and accepted target will also provide context for Log and Insights. You can turn this off in Setup. The estimate is a starting point, not a hard calorie limit.") },confirmButton={ TextButton(onClick={ consent=false; model.ask(allow=true) }) { Text("Enable & ask") } },dismissButton={ TextButton(onClick={ consent=false }) { Text("Cancel") } })
}
