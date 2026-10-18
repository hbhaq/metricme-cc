package com.hbhaq.metricme.reminders

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.hbhaq.metricme.MainActivity
import com.hbhaq.metricme.R
import com.hbhaq.metricme.data.RoutineRepository
import com.hbhaq.metricme.data.ProfileRepository
import com.hbhaq.metricme.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun notificationsAllowed(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()

@Singleton
class ReminderCoordinator @Inject constructor(@param:ApplicationContext private val context: Context, private val repository: RoutineRepository, private val clock: Clock, private val profiles: ProfileRepository) {
    private val mutex = Mutex()
    private val work get() = WorkManager.getInstance(context)
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val registry get() = context.getSharedPreferences("reminder-registry", Context.MODE_PRIVATE)
    fun start() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Routine reminders", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Best-effort local reminders with private content"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        work.enqueueUniquePeriodicWork("routine-reconcile-periodic", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ReconcileWorker>(6, TimeUnit.HOURS).build())
        requestReconcile()
    }
    fun requestReconcile() { work.enqueueUniqueWork("routine-reconcile-now", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<ReconcileWorker>().build()) }
    suspend fun setEnabled(enabled: Boolean) { repository.setReminders(enabled); reconcile() }
    suspend fun reconcile() = mutex.withLock { reconcileLocked() }
    private suspend fun reconcileLocked() {
        profiles.ensureCurrentTarget()
        repository.reconcile()
        val now = clock.instant()
        val enabled = repository.enabled() && notificationsAllowed(context)
        notifications.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { posted ->
            val id = posted.tag
            if (!enabled || id == null || !repository.canRemind(id) || repository.occurrence(id)?.second !in setOf(OccurrenceStatus.NOT_RECORDED, OccurrenceStatus.SNOOZED)) notifications.cancel(posted.tag, posted.id)
        }
        val candidates = if (enabled) repository.deliveries().filter { d ->
            val state = repository.occurrence(d.occurrenceId)
            state != null && state.second in setOf(OccurrenceStatus.NOT_RECORDED, OccurrenceStatus.SNOOZED) && d.deliveredFor != d.dueAt && d.dueAt >= now.minus(Duration.ofHours(2)).toEpochMilli() && repository.canRemind(d.occurrenceId)
        }.sortedBy { it.dueAt }.take(64) else emptyList()
        val ids = candidates.map { it.occurrenceId }.toSet()
        val old = registry.getStringSet("ids", emptySet()).orEmpty().toSet()
        (old - ids).forEach { id ->
            alarms.cancel(alarmIntent(id, 0))
            if (!repository.enabled() || !notificationsAllowed(context) || repository.occurrence(id)?.second !in setOf(OccurrenceStatus.NOT_RECORDED, OccurrenceStatus.SNOOZED)) {
                work.cancelAllWorkByTag("reminder:$id"); notifications.cancel(id, 1)
            }
        }
        candidates.forEach { d ->
            val data = workDataOf("id" to d.occurrenceId, "due" to d.dueAt)
            val request = OneTimeWorkRequestBuilder<DeliveryWorker>().setInputData(data).setInitialDelay(maxOf(0L, d.dueAt - clock.millis()), TimeUnit.MILLISECONDS).addTag("reminder:${d.occurrenceId}").build()
            work.enqueueUniqueWork("delivery:${d.occurrenceId}:${d.dueAt}", ExistingWorkPolicy.KEEP, request)
            try { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(clock.millis() + 1000, d.dueAt), alarmIntent(d.occurrenceId, d.dueAt)) }
            catch (_: SecurityException) { /* The durable worker remains a best-effort fallback. */ }
        }
        registry.edit().putStringSet("ids", ids).apply()
    }
    private fun alarmIntent(id: String, due: Long): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderAlarmReceiver::class.java).setData(Uri.parse("metricme://reminder/$id")).putExtra("id", id).putExtra("due", due), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    suspend fun action(id: String, action: OccurrenceAction, key: String, expectedDue: Long? = null) = mutex.withLock {
        repository.action(id, action, key, expectedDue); notifications.cancel(id, 1); reconcileLocked()
    }
    @android.annotation.SuppressLint("MissingPermission") // Guarded before claiming, and revocation is caught when posting.
    suspend fun deliver(id: String, due: Long) = mutex.withLock {
        if (!notificationsAllowed(context) || !repository.claim(id, due)) return@withLock
        val kind = repository.occurrence(id)?.first ?: return@withLock
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val generic = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("MetricMe reminder").setContentText("Open your private routine list.").build()
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("A moment for your routine").setContentText("Check the instructions you already have, or open MetricMe.")
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(generic)
            .addAction(0, if (kind == RoutineKind.MEDICATION) "Taken" else "Done", actionIntent(id, due, OccurrenceAction.DONE))
            .addAction(0, "Skip", actionIntent(id, due, OccurrenceAction.SKIP))
        if (kind == RoutineKind.MEDICATION) notification.addAction(0, "Snooze 10 min", actionIntent(id, due, OccurrenceAction.SNOOZE))
        try { notifications.notify(id, 1, notification.build()) } catch (_: SecurityException) { /* Permission may be revoked between checking and posting; occurrence stays not recorded. */ }
        if (repository.occurrence(id)?.second !in setOf(OccurrenceStatus.NOT_RECORDED, OccurrenceStatus.SNOOZED)) notifications.cancel(id, 1)
        reconcileLocked()
    }
    private fun actionIntent(id: String, due: Long, action: OccurrenceAction): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderActionReceiver::class.java).setData(Uri.parse("metricme://occurrence/$id/$due/${action.name}")).putExtra("id", id).putExtra("due", due).putExtra("action", action.name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    companion object { const val CHANNEL = "private-routines" }
}
