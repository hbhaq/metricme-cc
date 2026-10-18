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

## Verification

GitHub Actions built the debug application and instrumented-test APK, ran all 29 domain tests with zero failures/skips, and completed Android lint with 0 errors and 3 style warnings (`UseKtx`). Commands:

```sh
bash gradlew :domain:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --stacktrace
bash scripts/device-tests.sh
```

The final API 35 emulator run passed all 19 instrumented tests with 0 failures and 0 skips: 4 profile/database/migration tests, 10 logging/occurrence tests, 2 manual logging UI flows, 2 navigation/draft/demo tests and 1 real NotificationManager integration test. The UI flows exercise food save/edit/rotation/delete and explicit-zero daily steps. An earlier failure revealed that the extended Log button omitted its label from the accessibility tree; the directly labeled replacement passes both flows.

The downloaded emulator artifact SHA-256 matched GitHub's published digest. The earlier build artifact was also integrity-checked; its APK metadata confirms application ID `com.hbhaq.metricme`, version `0.2.0` (2), minSdk 26 and targetSdk 37. It requests optional notification permission and normal reminder/WorkManager infrastructure permissions, with no exact-alarm, camera or microphone permission.

Executed the actual migration SQL in SQLite against the committed v1 schema and preservation fixtures. Profile and food values survived; all 23 table column definitions matched the compiler-exported v2 schema and foreign-key checks passed. The separate Android MigrationTestHelper preservation test also passed in CI.

Local Windows attempts encountered undeletable generated-output directories. An intermediate clean local build compiled app code and passed 28 domain tests; final verification therefore uses clean Linux CI, rather than claiming a successful final Windows build. Only verified generated outputs were cleared; no source or user data was removed.

The final real first-launch screenshot was visually inspected on the API 35 emulator and saved at `docs/screenshots/today.png`. No screenshot or mockup is presented as physical-device validation.

Verified source commit: `0de0a4253c93836ae0bee28ed0aea521d19c132e`. [Passing workflow](https://github.com/hbhaq/metricme-cc/actions/runs/39140255260). Subsequent delivery commits contain documentation, the screenshot and text line-ending normalization; application behavior is unchanged.

[Download APKs and build reports](https://github.com/hbhaq/metricme-cc/actions/runs/39140255260/artifacts/12140743862). Extract `app/build/outputs/apk/debug/app-debug.apk` from the archive. This is a development debug build. Artifact retention ends 2027-01-16; source remains rebuildable.

## Remaining / not verified

Physical-device Doze/OEM battery behavior, reboot/update/clock broadcasts on real hardware, force-stop recovery, notification permission revocation, lock-screen privacy on OEM skins, TalkBack and 200% font scaling need device checks. Automated tests cannot establish exact delivery, which is not promised. No real medical instructions or paid APIs were used.

No Ktor backend, photo/audio AI, offline AI queue, OIDC authentication or Railway deployment exists yet (Stage 3). Full analytics/coaching are Stage 4. Full privacy/deletion and release review are Stage 5. No production-readiness claim is made.

## Files

Added: domain `Logging.kt`, `Routines.kt` and their tests; data `LocalMutations.kt`, `LoggingDao.kt`, `LoggingRepository.kt`, `RoutineDao.kt`, `RoutineRepository.kt`, `Migrations.kt`; `LoggingViewModel.kt`, `LoggingScreens.kt`; reminder coordinator/workers/receivers and notification icon; `LoggingDatabaseTest.kt`, `LoggingFlowTest.kt`, `ReminderIntegrationTest.kt`; v2 schema; REMINDERS.md and this report.

Updated: entities/database and Hilt module; application/activity; navigation/shared components; catalog, app build config/version and manifest; existing migration/navigation tests; CI emulator profile/artifact naming; README and dependency/device-check docs.

Stop after Stage 2 per the staged brief. Existing authorization covers GitHub upload and the later Railway deployment; the next stage begins after “continue.”
