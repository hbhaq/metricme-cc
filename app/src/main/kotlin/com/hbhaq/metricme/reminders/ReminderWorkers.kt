package com.hbhaq.metricme.reminders

import android.content.*
import androidx.work.*
import com.hbhaq.metricme.domain.LogRules
import com.hbhaq.metricme.domain.OccurrenceAction
import com.hbhaq.metricme.data.MetricMeDatabase
import com.hbhaq.metricme.data.RoutineRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*

@EntryPoint @InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun coordinator(): ReminderCoordinator
    fun routines(): RoutineRepository
    fun database(): MetricMeDatabase
}
fun coordinator(context: Context): ReminderCoordinator = EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java).coordinator()

class ReconcileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { coordinator(applicationContext).reconcile(); Result.success() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
}
class DeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        return try { coordinator(applicationContext).deliver(id, inputData.getLong("due", -1)); Result.success() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}
class OccurrenceActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val action = inputData.getString("action")?.let { runCatching { OccurrenceAction.valueOf(it) }.getOrNull() } ?: return Result.failure()
        val due = inputData.getLong("due", -1)
        return try { coordinator(applicationContext).action(id, action, LogRules.stableId("notification:$id:$due:$action"), due); Result.success() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}
private fun BroadcastReceiver.enqueue(context: Context, block: () -> Operation) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { try { block().result.get() } finally { pending.finish() } }
}
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val due = intent.getLongExtra("due", -1)
        enqueue(context) { WorkManager.getInstance(context).enqueueUniqueWork("alarm-delivery:$id:$due", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<DeliveryWorker>().setInputData(workDataOf("id" to id, "due" to due)).build()) }
    }
}
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val action = intent.getStringExtra("action") ?: return
        val due = intent.getLongExtra("due", -1)
        enqueue(context) { WorkManager.getInstance(context).enqueueUniqueWork("occurrence-action:$id:$due:$action", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<OccurrenceActionWorker>().setInputData(workDataOf("id" to id, "due" to due, "action" to action)).build()) }
    }
}
class ReminderSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED)) coordinator(context).requestReconcile()
    }
}
