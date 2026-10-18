package com.hbhaq.metricme

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hbhaq.metricme.data.*
import com.hbhaq.metricme.domain.*
import com.hbhaq.metricme.reminders.coordinator
import com.hbhaq.metricme.reminders.ReminderEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.time.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.*
import org.junit.Assert.*

@SdkSuppress(minSdkVersion = 33)
class ReminderIntegrationTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @Test fun actualNotificationIsPrivateDeduplicatedAndTakenActionIsIdempotent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java)
        val repo = entry.routines(); val dao = entry.database().routineDao(); val manager = context.getSystemService(NotificationManager::class.java)
        val coordinator = coordinator(context)
        val input = RoutineInput(null, RoutineKind.MEDICATION, "Private test name", "Existing private instructions", "Private dose text", "", DayOfWeek.entries.toSet(), listOf(LocalTime.now().withSecond(0).withNano(0).minusMinutes(1)), LocalDate.now().minusDays(1), null, false)
        val id = repo.save(input, UUID.randomUUID().toString())
        try {
            coordinator.setEnabled(true)
            val due = dao.medicationOccurrences().filter { it.medicationId == id && it.scheduledAt <= System.currentTimeMillis() }.maxBy { it.scheduledAt }
            repeat(2) { coordinator.deliver(due.id, due.scheduledAt) }
            withTimeout(5000) { while (manager.activeNotifications.none { it.tag == due.id }) delay(50) }
            val notification = manager.activeNotifications.single { it.tag == due.id }.notification
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.visibility)
            val preview = notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString() + notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
            assertFalse(preview.contains("Private test name")); assertFalse(preview.contains("Private dose text"))
            assertEquals("NOT_RECORDED", dao.medicationOccurrence(due.id)!!.status)
            val key = UUID.randomUUID().toString()
            repeat(2) { coordinator.action(due.id, OccurrenceAction.DONE, key, due.scheduledAt) }
            withTimeout(5000) { while (manager.activeNotifications.any { it.tag == due.id }) delay(50) }
            assertEquals("TAKEN", dao.medicationOccurrence(due.id)!!.status)
            assertFalse(manager.activeNotifications.any { it.tag == due.id })
        } finally {
            repo.archive(id, RoutineKind.MEDICATION, UUID.randomUUID().toString()); coordinator.setEnabled(false)
        }
    }
}
