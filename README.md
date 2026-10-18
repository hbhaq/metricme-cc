# MetricMe

A calm, local-first native Android habit and health journal. Kotlin, Jetpack Compose Material 3, Hilt, Room, and a pure Kotlin domain module.

**Current delivery: Stage 2.** Profile/measurement history, energy targets, offline food/activity/steps/sleep logging, habits and medication occurrences, private local reminders, and isolated read-only sample data. AI and the Railway Ktor backend are Stage 3; analytics/coaching are Stage 4; final privacy/deletion and accessibility verification are Stage 5. Do not use this development build as medical advice.

<img src="docs/screenshots/today.png" width="320" alt="MetricMe Today screen captured from the real Android app on the CI emulator">

## Build

Use JDK 17 and an Android SDK with `platforms;android-37.0` and `build-tools;36.0.0`. Set `ANDROID_HOME`, or put `sdk.dir=/absolute/path/to/sdk` in the ignored `local.properties`. The Gradle wrapper pins 9.4.1 and verifies its distribution checksum. AGP 9.2.1 uses built-in Kotlin; do not add `org.jetbrains.kotlin.android`.

```powershell
.\gradlew.bat :domain:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
# With a running Android emulator or connected device:
.\gradlew.bat :app:connectedDebugAndroidTest
```

```sh
bash gradlew :domain:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
bash gradlew :app:connectedDebugAndroidTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Test reports: `domain/build/reports/tests/test/`, `app/build/reports/`. GitHub Actions builds the app and runs instrumented tests on an API 35 emulator. A configured workflow is not evidence that CI has run; see the stage report for real results.

## Try the app

Open Today → Set up your profile, or choose “Explore with sample data.” Demo is read-only and uses in-memory fixtures instead of inserting sample records into the personal database. Exit demo to return to your records. Add kg/cm measurements from You; imperial display is optional. History supports correction and deletion. Manage target supports manual values or a maintenance estimate when inputs are available. All forms offer Cancel and preserve unfinished edits through recreation.

Use **Log** for food, activity/workouts, daily steps, sleep, habits/vitamins or medications. Optional nutrition/duration/step fields stay unknown when blank. Food nutrition describes the full entered quantity; portion consumed scales it. Workout lines use `exercise | sets | reps | load in kg`, with unknown fields left blank. Journal supports date navigation, corrections and confirmed deletion. Sleep belongs to the wake-up date; overlapping sessions require confirmation and do not count shared minutes twice.

Open **You → Habits, medications & reminders** to manage routines and enable optional notifications. Scheduled occurrences are tracked separately, as-needed use is recorded separately, and a missed alert does not imply a missed dose. Read [the reminder behavior and limitations](docs/REMINDERS.md).

## Rules that matter

- Missing is distinct from zero. AI drafts will not count toward confirmed totals.
- Gender identity never selects the BMR coefficient. Optional physiological equation input is collected separately. Pregnancy/breastfeeding or a user preference can disable automatic calorie recommendations.
- Mifflin–St Jeor: `10 × kg + 6.25 × cm − 5 × completed age + coefficient`; coefficients +5 and −161. Activity multipliers 1.2, 1.375, 1.55, 1.725, 1.9 already include habitual activity. No automatic deficit or extra exercise calories.
- Target snapshots are dated; editing/deleting a measurement affects current estimates and creates a new snapshot today. Past snapshots remain intact. Manual targets stay until explicitly replaced.
- Measurements store instant and IANA zone. Latest instant wins, with a stable insertion sequence for ties. Backdated entries use local noon; a correction on the same date preserves its time. More than 30 days old is stale.
- Writes use UUID operation receipts and transactions. A retry with a changed payload is rejected; a repeated identical save returns the previous result.

## Privacy and scope

Notifications are opt-in; no camera, microphone or photo/storage access is requested. Stage 2 makes no health-data network calls. Room uses the Android app sandbox and device file-based encryption; it is **not separately database-encrypted**. Cloud backup and device transfer are excluded. No API key is required or embedded. Full in-app deletion controls arrive in Stage 5; uninstalling clears the application sandbox now. Do not enter sensitive real data into an unreviewed development build.

No backend is deployed in Stage 2. Railway will host the Ktor API, while Android UI ships as an APK. GitHub upload and Railway deployment have been authorized by the user, subject to service access. The staged brief requires stopping after each stage until “continue.”

[Stage 2 passing checks and debug APK](https://github.com/hbhaq/metricme-cc/actions/runs/39140255260) — 29 domain tests and 19 emulator tests passed; lint completed with 0 errors and 3 style warnings.

See [the approved planning document](STAGE-0-PLAN.md), [Stage 1 report](docs/STAGE-1-REPORT.md) and [Stage 2 verification report](docs/STAGE-2-REPORT.md).
