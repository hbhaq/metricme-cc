# Stage 2 verification report

Scope: offline manual logging, scheduled habit/medication occurrences, local reminders and tests. Stage 3 AI/backend/Railway work has not started.

## Implemented

- Food logging with meal, quantity/unit, consumed portion, optional calories/macros and edit/delete. Unknown nutrition remains nullable; recorded zero and explicitly none consumed stay distinct.
- Activity and workouts with optional duration, reported steps, perceived intensity, notes and structured exercise/sets/reps/load rows. No steps or exercise calories are invented.
- Manual daily step totals, with latest-report selection and no addition of walk steps on top. Overlapping walk step records are excluded conservatively with an explanation.
- Sleep sessions/naps using actual instants, timezone/offset validation, wake-day assignment, overlap warning/confirmation and union duration for overlapping sessions.
- Habits/vitamins and medications with selected weekdays, up to eight times, date bounds, separate occurrences, take/done/skip/undo, medication snooze, preserved schedule history and archive. PRN medication events have their own table and no scheduled denominator.
- Inexact AlarmManager alerts plus durable WorkManager reconciliation/fallback, opt-in notification permission, generic private notification content, stable IDs, atomic delivery claims and idempotent actions. See REMINDERS.md for timing and recovery limits.
- A persistent Log action, focused saved-state forms, date navigation, a real offline journal with edit/delete, daily logged subtotals and routine controls. Demo remains read-only and does not write personal data. Insights and full analytics remain Stage 4.
- Non-destructive Room schema 1→2 migration. Version 1 stays committed; version 2 has 23 tables, nullable food nutrition, snapshot fields and new workout/PRN/delivery/settings tables. Existing profile, measurements, food and history are preserved.

## Verification in progress

The first two local attempts encountered undeletable stale generated-output directories left from Stage 1. Only verified `app/build` and `domain/build` outputs were cleared, after preserving the prior APK. No source or user data was removed.

An intermediate clean build compiled app code and ran 28 domain tests with zero failures. Its test compilation used a dependency/service configuration superseded during implementation; the final source is now frozen and rebuilding. A further nutrition portion-scaling test brings the intended domain count to 29; it is not yet claimed passed here.

Executed the actual migration SQL in SQLite against the committed v1 schema and preservation fixtures. Profile and food values survived; all 23 table column definitions matched the compiler-exported v2 schema and foreign-key checks passed. This complements rather than replaces the Android MigrationTestHelper test.

Pending final local build/lint and CI emulator results are recorded here before delivery. Authored instrumented tests cover migration/data preservation, all four manual log types, edit/delete, unknown vs zero, sleep overlap, concurrent save retries, recurrence deduplication, schedule edits/archive, snooze/PRN, stale actions and delivery claims. Compose tests cover saved edits, cancellation, food edit/delete, explicit zero steps, and demo isolation. A real NotificationManager component test checks private preview text, duplicate delivery, retained not-recorded state and idempotent Taken behavior.

## Remaining / not verified

Physical-device Doze/OEM battery behavior, reboot/update/clock broadcasts on real hardware, force-stop recovery, notification permission revocation, lock-screen privacy on OEM skins, TalkBack and 200% font scaling need device checks. Automated tests cannot establish exact delivery, which is not promised. No real medical instructions or paid APIs were used.

No Ktor backend, photo/audio AI, offline AI queue, OIDC authentication or Railway deployment exists yet (Stage 3). Full analytics/coaching are Stage 4. Full privacy/deletion and release review are Stage 5. No production-readiness claim is made.

## Files

Added: domain `Logging.kt`, `Routines.kt` and their tests; data `LocalMutations.kt`, `LoggingDao.kt`, `LoggingRepository.kt`, `RoutineDao.kt`, `RoutineRepository.kt`, `Migrations.kt`; `LoggingViewModel.kt`, `LoggingScreens.kt`; reminder coordinator/workers/receivers and notification icon; `LoggingDatabaseTest.kt`, `LoggingFlowTest.kt`, `ReminderIntegrationTest.kt`; v2 schema; REMINDERS.md and this report.

Updated: entities/database and Hilt module; application/activity; navigation/shared components; catalog, app build config/version and manifest; existing migration/navigation tests; CI emulator profile/artifact naming; README and dependency/device-check docs.

Stop after Stage 2 per the staged brief. Existing authorization covers GitHub upload and the later Railway deployment; the next stage begins after “continue.”
