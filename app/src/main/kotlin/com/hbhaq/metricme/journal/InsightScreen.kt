package com.hbhaq.metricme.journal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hbhaq.metricme.contracts.MealRetention
import com.hbhaq.metricme.ui.ErrorMessage

@Composable fun CloudInsightsScreen(model:JournalViewModel,demo:Boolean,setup:()->Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val question by model.questionText.collectAsStateWithLifecycle()
    val questions=if(demo) emptyList() else state.questions
    var consent by rememberSaveable { mutableStateOf(false) }
    val list=rememberLazyListState()
    fun ask() { if(!state.signedIn) setup() else if(state.consentAccepted) model.ask() else consent=true }
    LaunchedEffect(state.questions.size,state.busy) { if(list.layoutInfo.totalItemsCount>0) list.animateScrollToItem(list.layoutInfo.totalItemsCount-1) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("day" to "Daily","week" to "Weekly","month" to "Monthly").forEach { (key,label) ->
                SuggestionChip(onClick={ model.choosePeriod(key); model.typeQuestion(when(key) { "day" -> "Summarize today in a table"; "week" -> "Summarize this week in a table"; else -> "Summarize this month in a table" }); ask() },enabled=!state.busy&&!state.questionPending&&!demo,label={ Text(label) })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,contentPadding=PaddingValues(20.dp,12.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            if(questions.isEmpty()) item { Box(Modifier.fillParentMaxHeight(.65f).fillMaxWidth(),contentAlignment=Alignment.Center) { Text("Ask your history",style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(questions,key={ it.id }) { raw -> val reply=MealRetention.reply(raw)
                Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    ChatUser(reply.transcript)
                    val summary=reply.insights
                    Text(reply.message)
                    if(summary!=null) InsightTable(summary)
                    if(reply.insights==null && reply.references.isNotEmpty()) ReferenceTable(reply.references)
                }
            }
            (if(demo) null else state.error)?.let { item { ErrorMessage(it,model::dismissError) } }
            if(state.busy&&!demo) item { Text("Checking your records…",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Surface(color=MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxWidth().imePadding().padding(12.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(question,model::typeQuestion,modifier=Modifier.weight(1f).heightIn(max=140.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(28.dp),placeholder={ Text("Ask about your history") },enabled=!state.busy&&!state.questionPending,maxLines=4)
                FilledIconButton(onClick=::ask,enabled=!demo&&!state.busy&&(question.isNotBlank()||state.questionPending),modifier=Modifier.padding(bottom=4.dp)) { Icon(Icons.Outlined.ArrowUpward,if(state.questionPending) "Retry question" else "Send question") }
            }
        }
    }
    if(consent) AlertDialog(onDismissRequest={ consent=false },title={ Text("Use AI insights?") },text={ Text("Questions go to MetricMe and OpenAI. Answers use your account's saved database records. Questions here do not log new entries. This also enables AI logging for messages you send from Log.") },confirmButton={ TextButton(onClick={ consent=false; model.acceptConsentAndAsk() }) { Text("Enable & ask") } },dismissButton={ TextButton(onClick={ consent=false }) { Text("Cancel") } })
}
