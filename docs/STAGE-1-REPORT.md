# Stage 1 delivery and verification

Date: 2026-10-18. Scope: Android foundation, local profile/measurement history, energy calculations, navigation and demo scaffold. No Stage 2–5 implementation has started.

## What was built

- Native Compose navigation: Today, Journal, Insights, You, and measurement history. Ivory/forest light theme, independent dark palette, serif headlines, labeled icons, large controls, scrollable forms, and explicit partial/empty/loading/error states. Later-stage features are labeled, not simulated as working personal logging.
- Optional profile inputs, separate equation coefficient and gender identity, activity levels, goals/restrictions and imperial display. Forms retain draft values through SavedStateHandle, including save operation keys. Cancel preserves persisted data.
- Room schema v1 with 19 normalized tables and foreign keys/indexes. No destructive migration fallback. The actual compiler-exported schema is committed, and a MigrationTestHelper test validates reopening v1. There is no v1→v2 migration yet because no previous release schema exists.
- Measurement add/edit/delete with timestamps/zones, deterministic latest-value selection, provenance/acquisition labels, unusual-change confirmation and stale-data labels. Input remains metric; display can be imperial.
- Pure Kotlin Mifflin–St Jeor BMR/TDEE rules, missing-input handling, manual targets, disabled automatic recommendations, historical target snapshots and transactional operation receipts. Profile edits use Room Upsert, preserving child history.
- Read-only sample fixtures in memory, with visible sample labels and no inserts into the personal DB. This is a demo scaffold, not an AI result.
- Checksummed Gradle wrapper, version catalog, Hilt/KSP, CI build/lint/emulator workflow, database and Compose instrumentation tests.

## Real local verification

The machine initially had no JDK/Gradle/Android SDK on PATH. Project-local ignored tools were downloaded: Temurin JDK 17, Gradle 9.4.1, SDK Platform 37.0, Build Tools 36.0.0, Platform Tools. The Gradle ZIP checksum matched the published SHA-256. Actual Maven and SDK metadata confirmed dependency/package availability; sources are in DEPENDENCIES.md.

First command, using the downloaded Gradle binary:

```text
gradle.bat :domain:test :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL in 12m 44s
79 actionable tasks: 79 executed
```

JUnit XML: `HealthRulesTest`, 11 tests, 0 failures, 0 errors, 0 skipped. Covers both equation coefficients, activity multiplication, birthday age, missing/invalid/nonfinite inputs, pregnancy preference, separate gender identity, unusual changes, measurement timestamp ordering, future/draft exclusion, target-history selection/invalidation and staleness/local-date assignment.

Final command, through the checksummed wrapper:

```text
.\gradlew.bat :domain:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
BUILD SUCCESSFUL in 1m 1s
88 actionable tasks: 18 executed, 70 up-to-date
Lint: No issues found.
```

The preceding lint run found one `NonObservableLocale` error. It was fixed using Compose's observable LocalLocale for date/measurement formatting; the command above verifies the corrected final source. The domain tests were up-to-date on the final run after their real successful execution in the first build.

ZIP inspection confirms the test APK contains `assets/com.hbhaq.metricme.data.MetricMeDatabase/1.json`. The schema has 19 tables. AAPT confirms package `com.hbhaq.metricme`, version 0.1.0, target SDK 37, and no Internet/camera/microphone/notification permissions. A generated signature-level receiver permission is present; it produces no runtime permission prompt.

Artifacts: `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. These are debug builds, not a signed release. A nonfatal native-library stripping notice and a deprecated Compose test-rule notice occurred during the initial build; neither is a test pass or device validation.

## Not executed locally

Four database instrumentation tests and two Compose navigation/recreation/demo tests compile into the test APK. They have not yet run on a device. ADB found no connected devices; Windows reports virtualization unavailable. CI supplies an API 35 emulator and a screenshot artifact step, but configured CI is not a passing test result. Physical camera/microphone/notifications are outside Stage 1.

Rendered visual inspection, TalkBack, 200% font scaling, physical-device behavior, real process-death restoration, release signing, and migration across a future schema version are pending. Rotation coverage is authored; do not equate it with a verified process-death test.

## Incomplete / later stages

- Stage 2: food/activity/steps/sleep/habits/medication forms, scheduled occurrences, local reminders and their tests.
- Stage 3: Ktor API, OIDC/PKCE authentication, server validation/rate limits/isolation/idempotency, explicit media-upload consent, photo/audio AI review, durable offline queue, GitHub-backed Railway deployment.
- Stage 4: actual daily/weekly/monthly aggregates, coverage, charts and exactly-three-item coaching reports. Today’s sample metrics are illustrative read-only fixtures only.
- Stage 5: in-app full deletion and privacy controls, complete accessibility/device review, remaining tests, release APK distribution and final verification report.

No server or Railway deployment exists yet. Stage 1 has no Internet permission and makes no external API calls. No paid credentials are needed for the current build. Future release needs authenticated backend/OpenAI configuration and signing material outside the repository. Room is sandboxed but not separately database-encrypted; backups and device transfer are excluded.

## Files

Root: `.gitignore`, `.gitattributes`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `README.md`, catalog and wrapper files under `gradle/`.

Domain: `domain/build.gradle.kts`; complete `Profile.kt`, `HealthRules.kt`, and `HealthRulesTest.kt` under the `com.hbhaq.metricme.domain` package.

Android: `app/build.gradle.kts`, manifest and day/night/XML/icon resources; `MetricMeApplication.kt`, `MainActivity.kt`, `di/DatabaseModule.kt`; `data/Entities.kt`, `MetricMeDatabase.kt`, `ProfileRepository.kt`; `ui/Theme.kt`, `MetricMeViewModel.kt`, `MetricMeApp.kt`; compiler-exported `app/schemas/com.hbhaq.metricme.data.MetricMeDatabase/1.json`; `DatabaseTest.kt`, `NavigationTest.kt` instrumentation tests.

CI/docs: `.github/workflows/android.yml`, `docs/DEPENDENCIES.md`, `docs/DEVICE-CHECKS.md`, this report. The Stage 0 plan is preserved. The planned feature tree is consolidated into a smaller number of complete files for Stage 1; later features will split as implemented.

Per the supplied staged brief, stop here after Stage 1. The user's existing authorization covers GitHub upload and the later Railway deployment, but implementation advances only after “continue.”
