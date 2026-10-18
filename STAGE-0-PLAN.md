# MetricMe — Stage 0 plan

Planning only. No application code has been generated, built, uploaded, or deployed. Implementation begins after the requested stage approval. GitHub push and Railway deployment are already authorized by the user's accompanying message; this does not bypass platform permission controls.

## Assumptions and delivery

- Native Android app, application ID `com.hbhaq.metricme`; adults 18+, English first, metric storage, optional imperial display. One local profile per installation; authenticated backend users remain isolated.
- Railway hosts the Ktor API, not the Android UI. GitHub repository: https://github.com/hbhaq/metricme-cc. Deliver an installable demo APK through a GitHub release after build verification; signing credentials must stay outside source control.
- Local manual use needs no account or API key. Demo uses a separate database and prominent “Sample data” labels. Real AI requires explicit consent and authenticated backend access.
- Minimum SDK proposed: 26, giving native java.time and a manageable supported-device baseline. Compile/target SDK proposed: 37, subject to checking stable SDK metadata at Stage 1. No preview SDK will be used.
- Do not introduce a browser app as a substitute for native Android. No wearables, passive steps, billing, social features, or Health Connect.

## UI direction and acceptance

Warm ivory background, deep forest primary, muted terracotta accents, restrained rounded surfaces, spacious typography, and tabular numerals. Dark theme uses independently checked contrast. Material 3 components receive a consistent theme rather than unrelated default styling. Icons have text labels; estimates always have visible labels.

Four destinations: Today, Journal, Insights, and You. An accessible, persistent Log action opens Food / Activity / Steps / Sleep / Habit / Medication choices. Today presents a date strip, a primary intake card with logging coverage, compact steps and sleep cards, scheduled occurrences, and one small coaching action. Missing information says “Not logged”; no empty progress rings imply zero. There are no punitive streak messages.

Journal is a date-grouped timeline with source badges and a separate pending-drafts section. Insights has weekly/monthly range selection, charts with readable text summaries, coverage beside averages, and exactly three coaching items. You contains profile, measurement history, targets, reminders, privacy, deletion, and demo switching.

Logging uses focused forms with units beside inputs, immediate field validation, explicit Save/Cancel, and preserved edits. AI review shows the original preview/transcript, editable components, assumptions, confidence where supported, portion consumed, and a clear Confirm entry action. No AI draft contributes to totals.

Design checks per screen: 48dp interactive targets, TalkBack order and names, 200% font scale, small-screen scrolling, keyboard/insets, light/dark contrast, loading/empty/partial/offline/denied/retry/error states. Test screenshots at compact and large widths. Motion respects system animation settings. Rotation/process restoration uses saved state and persisted drafts.

## Architecture and data flow

Compose -> ViewModel StateFlow -> domain use cases -> repositories -> Room. Room is authoritative. Composables never calculate health metrics or perform network calls. Hilt supplies repositories, clocks, dispatchers, authentication, media, and API clients. Pure Kotlin domain and mirrored/shared serializable API contracts remain Android-independent.

Manual flow: validate -> transactionally insert record, provenance, target snapshot if required -> Room Flow updates UI. All saves carry a UUID operation key, enforced by unique indexes.

AI flow: capture/select locally -> preview/edit context -> explicit consent and upload action -> persist draft/job and consent version -> unique WorkManager operation -> authenticated Ktor validation -> OpenAI -> validated draft result -> user edits -> transactional confirmation. User revisions are versioned so retries cannot overwrite edits. Deleted drafts get tombstones; late results are discarded. Never put raw health payloads into WorkManager input data or logs.

Backend: Ktor routes -> bearer authentication -> user-scoped validation/rate limiting -> durable PostgreSQL idempotency/job store -> OpenAI adapter. Unique `(user_id, operation_key)` plus payload hash rejects conflicting reuse. Transactional claim/status/result writes handle concurrent requests. Network calls run outside DB transactions; uncertain upstream results remain recoverable without duplicate client confirmation.

Release authentication: OIDC authorization-code flow with PKCE through the system browser; validate issuer, audience, signature, expiry, and subject. No shared user password or static APK bearer secret. Development auth requires explicit local configuration and is rejected in release/deployed configuration. Local logging remains available when authentication expires.

Coaching: deterministic local statistics and coverage -> user-approved minimal aggregates and restrictions -> Ktor -> validated three-item report -> persist period, input version, generation time, model config. Offline fallback gives three clearly labeled deterministic logging/consistency actions, never fabricated AI analysis.

## Offline / online matrix

| Capability | Offline | Online dependency |
|---|---|---|
| Profile, history, manual logs, targets | Full | None |
| Dashboard, charts, deterministic aggregates | Full | None |
| Local reminders and occurrence actions | Available subject to OS limits | None |
| Photo/audio capture and editable drafts | Full | None |
| Real photo estimate / transcription / AI coaching | Consented queue, cancel, retry | Auth + Ktor + OpenAI |
| Demo AI examples | Labeled local samples | None |
| Local deletion | Immediate | Backend purge queued until acknowledged |
| Backend account/media deletion | Pending status offline | Authenticated deletion and acknowledgment |

## Central domain rules

Provenance contains source (`manual`, `ai_estimated`, `imported`), optional acquisition method for measurements, confirmation and processing status, nullable confidence, created/updated instants. Imported data has no import feature in MVP. Represent missing/not-recorded, skipped, not-applicable, and recorded zero separately. Confirmed data and AI drafts have separate tables.

Store event instant, event zone and offset, and assigned local date. Food/activity/steps use the event's recorded zone; sleep belongs to wake-up date. DST overlaps require an explicit offset choice; gaps advance to the next valid time with a visible explanation. Existing events retain their day when device zone changes. Future reminders follow the current selected local zone.

Measurements: latest instant on/before the relevant time wins; equal timestamps use a stable creation sequence. Historical target snapshots are immutable except explicit correction workflows. Prompt monthly, mark measurements older than 30 days stale, preserve prior data when cancelled. Invalid values are rejected; unusual deltas require confirmation.

Mifflin–St Jeor estimate: `10*kg + 6.25*cm - 5*ageYears + coefficient`, coefficient +5 or -161 chosen from a separately explained physiological input, never gender identity. Birthday-based age on the effective date. Declining inputs disables estimates, not logging. Activity multipliers: sedentary 1.2, light 1.375, moderate 1.55, high 1.725, very high 1.9. These are estimates requiring health-calculation review before real release. Default maintenance only; exercise is not added again. Pregnancy/breastfeeding disables automatic calorie recommendations. Manual targets retain an explicit source.

Daily steps: a confirmed daily total supersedes individual activity step counts for that date; otherwise sum confirmed non-overlapping walk counts and flag overlaps for review. Never infer steps from vague audio. Sleep duration uses instants; multiple sessions sum after overlap warning/reconciliation, including naps.

Medication schedule versions preserve prior occurrences. Stable occurrence identity includes definition, schedule version, scheduled instant and offset. PRN entries have no adherence denominator. Ended courses schedule nothing new. Snooze changes delivery time, not scheduled identity or taken status. Missed notification never implies missed dose. No medication or dose recommendations.

Weekly report default: Sunday 09:00 local, covering the most recent completed Monday–Sunday (ending the preceding Sunday). Explain the one-week lag; users may choose Monday morning for fresher reports. Monthly: first day 09:00 covering prior calendar month. Unique `(profile, period type, start, end)` prevents duplicates; overdue periods run on next eligible worker/app open.

## Data model and persistence

UUID primary keys throughout; user/profile foreign keys on owned records. Main Room tables: UserProfile, BodyMeasurement, FoodEntry, FoodComponent, ActivityEntry, StepEntry, SleepEntry, HabitDefinition, HabitOccurrence, Medication, ReminderSchedule, MedicationOccurrence, TargetHistory, ProcessingJob, CoachingReport. Supporting tables: Draft, DraftComponent, ConsentReceipt, OperationReceipt, DeletionTombstone, ScheduleVersion. A derived daily aggregation is a query/use case, never a giant DailyLog entity.

Indexes: profile + event date/instant; measurement kind + effective instant; unique operation key; job status + next attempt; unique occurrence identity; unique report period. Food components cascade with their parent. Profile deletion cascades owned local data after cancellation/media cleanup is recorded. Schedule edits end versions rather than deleting history; definition deletion archives when occurrences exist. Draft confirmation inserts entry/components and marks job consumed in one transaction. Occurrence actions use conditional updates and operation receipts in one transaction. Export Room schemas; release migrations never use destructive fallback.

Backend PostgreSQL stores users/auth subjects, idempotency receipts, bounded job results, and deletion state, not a mirror of the health diary. All queries scope ownership using validated token subject. Cleanup and purge are retryable and auditable without logging content.

## Reminders, media, privacy, and recovery

WorkManager: durable AI queue, cleanup, report generation, and schedule reconciliation. AlarmManager: local reminder delivery, inexact by default; exact alarms only if justified and permitted. Reconcile after reboot, package replacement, zone/clock change and schedule edits. Stable request IDs and unique work names; re-read occurrence state before notification. Document Doze, force-stop and manufacturer restrictions. Generic lock-screen text by default.

Photo Picker avoids blanket storage permission. Camera and microphone permissions appear contextually. Record mono PCM WAV through AudioRecord with valid header finalization; enforce 120 seconds and 10 MiB. Images capped at 10 MiB and decoded dimension limits; transcode supported formats locally and remove EXIF. Server checks content signature, dimensions, duration and size, not filename alone.

Backend media stays in bounded temporary files for the request and is removed in finally blocks; crash sweeper removes leftovers within one hour. Local pending media expires after seven days with warning, is removed on cancel/delete, and removed within 24 hours after successful confirmation unless the user explicitly keeps it. Idempotency result retention proposed 7 days; expired jobs require a deliberate new operation. Logs contain only request IDs, status, timing, and redacted errors.

Android Keystore protects session/encryption keys; this is not database encryption. Proposed MVP Room storage uses app sandbox and device file-based encryption; no SQLCipher claim. Exclude health DB, media, and credentials from backup. State this plainly in Privacy. App deletion removes local files/jobs and queues authenticated backend purge; offline UI shows server deletion pending. Explain provider retention separately: `store:false` is not a promise of zero upstream retention.

Bounded retry: exponential backoff with jitter, maximum five attempts; honor Retry-After. Retry transient network/429/5xx only. Validation/auth/refusal need user action. Restore queued work from DB on startup; user cancellation creates a tombstone before worker cancellation. Media consent is independent of camera/microphone permission.

## API plan

Routes: `/health/live`, `/health/ready`, `/v1/food-estimates`, `/v1/activity-transcriptions`, `/v1/coaching`, `/v1/jobs/{id}`, `/v1/account/data`. Auth required for health-data routes, UUID Idempotency-Key for mutations. Error responses use safe typed codes and retryability.

Use Responses API with image input and strict JSON schema for food; audio transcription endpoint followed by structured activity extraction. Server independently validates finite nonnegative numeric values, units, limits, and required fields. Macro/calorie tolerance mismatch is a warning. Refusals, ambiguity, incomplete results and non-food images yield clarification/manual entry, not invented numbers. No model tools or actions are enabled for untrusted transcripts.

Food contract: items, quantities/units, calories, protein/carbs/fat grams, assumptions, confidence, needs_clarification and portion consumed. Activity contract: stated type/start/duration/steps/intensity/exercises plus assumptions/unresolved fields; absent fields are nullable. Coaching contract has exactly three observation/action/target/timeframe/rationale/limitation objects. Model names are server configuration; verify supported image/schema capability and account access at Stage 3 before pinning. Transcription candidate `gpt-4o-mini-transcribe` remains unverified until its model page/account capability is checked; demo does not require it.

## Dependency verification ledger (2026-10-18)

Official documentation was consulted; this is not a claim of dependency resolution or a compatible build. Pin all versions in the catalog at Stage 1, with dependency verification and wrapper checksum. Avoid dynamic versions.

| Dependency | Proposed version / status |
|---|---|
| Android min / target | 26 / 37 proposed; target stable status must be checked against SDK metadata |
| Compose UI/runtime/foundation | 1.12.1 listed in official stable releases; BOM mapping still unverified |
| Room | 2.8.5 listed stable; deliberately keep requested Room 2 architecture |
| Navigation Compose | 2.10.2 listed stable |
| WorkManager | 2.12.0 listed stable |
| CameraX | 1.6.2 listed stable |
| Lifecycle | 2.11.0 listed stable |
| AndroidX Hilt | 1.4.0 listed stable; not the Dagger Hilt compiler version |
| Material 3 / Compose BOM | Exact pins unverified, check before use |
| AGP / Gradle / JDK / Kotlin / KSP | Compatible exact pins unverified, check before use; AGP release page retrieval failed |
| Dagger Hilt / Coroutines / serialization | Exact pins unverified, check before use |
| Retrofit / OkHttp | Exact pins unverified, check before use |
| Ktor / PostgreSQL driver / DB migrations | Exact pins unverified, check before use; official releases consulted without resolving full compatibility |
| JUnit / AndroidX Test / MockWebServer / Turbine | Exact pins unverified, check before use |

Sources: https://developer.android.com/jetpack/androidx/versions/stable-channel ; https://developer.android.com/guide/topics/manifest/uses-sdk-element ; https://kotlinlang.org/docs/releases.html ; https://ktor.io/docs/releases.html ; https://developers.openai.com/api/docs/guides/images-vision ; https://developers.openai.com/api/docs/guides/speech-to-text ; https://developers.openai.com/api/docs/guides/structured-outputs . Endpoint mechanisms verified from official guides; model selection and exact request schemas must be rechecked during implementation.

## Planned full source tree

Paths below are relative to this document. Each named feature file is a complete source file; braces enumerate individual planned files, not generated application code.

```text
MetricMe/
  STAGE-0-PLAN.md
  README.md
  .gitignore
  .env.example
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradlew
  gradlew.bat
  gradle/libs.versions.toml
  gradle/verification-metadata.xml
  gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}
  .github/workflows/{android.yml,backend.yml,release.yml}
  docs/{architecture.md,data-model.md,privacy.md,reminders.md,device-checks.md,verification.md,deployment.md}
  contracts/
    build.gradle.kts
    src/main/kotlin/com/hbhaq/metricme/contracts/{FoodContracts.kt,ActivityContracts.kt,CoachingContracts.kt,JobContracts.kt,ApiError.kt}
  domain/
    build.gradle.kts
    src/main/kotlin/com/hbhaq/metricme/domain/
      model/{Profile.kt,Measurement.kt,Provenance.kt,LogRecords.kt,Occurrence.kt,Target.kt,Draft.kt,Report.kt}
      repository/{ProfileRepository.kt,LogRepository.kt,OccurrenceRepository.kt,DraftRepository.kt,ReportRepository.kt}
      usecase/{CalculateEnergy.kt,SaveMeasurement.kt,ResolveHistoricalTarget.kt,AssignLocalDay.kt,ReconcileSteps.kt,ComputeSleep.kt,ApplyOccurrenceAction.kt,BuildReminderPlan.kt,ValidateEstimate.kt,ConfirmDraft.kt,AggregatePeriod.kt,BuildCoachingInput.kt,DeleteUserData.kt}
      policy/{SafetyPolicy.kt,RetryPolicy.kt,MediaPolicy.kt}
    src/test/kotlin/com/hbhaq/metricme/domain/{EnergyTest.kt,MeasurementHistoryTest.kt,HistoricalTargetsTest.kt,LocalDayDstTest.kt,SleepTest.kt,StepReconciliationTest.kt,OccurrencesTest.kt,ReminderPlanTest.kt,EstimateValidationTest.kt,SparseAnalyticsTest.kt,CoachingTest.kt}
  app/
    build.gradle.kts
    proguard-rules.pro
    schemas/com.hbhaq.metricme.data.local.MetricMeDatabase/1.json
    src/main/AndroidManifest.xml
    src/main/res/values/{strings.xml,themes.xml}
    src/main/res/xml/{backup_rules.xml,data_extraction_rules.xml,file_paths.xml}
    src/main/res/drawable/ic_launcher_foreground.xml
    src/main/res/mipmap-anydpi-v26/{ic_launcher.xml,ic_launcher_round.xml}
    src/main/kotlin/com/hbhaq/metricme/
      {MetricMeApplication.kt,MainActivity.kt}
      di/{DatabaseModule.kt,RepositoryModule.kt,NetworkModule.kt,WorkerModule.kt}
      data/local/{MetricMeDatabase.kt,Converters.kt,Entities.kt,ProfileDao.kt,LogDao.kt,OccurrenceDao.kt,DraftDao.kt,ReportDao.kt,Migrations.kt}
      data/repository/{RoomProfileRepository.kt,RoomLogRepository.kt,RoomOccurrenceRepository.kt,RoomDraftRepository.kt,RoomReportRepository.kt}
      data/remote/{MetricMeApi.kt,AuthInterceptor.kt,SessionStore.kt,OidcSession.kt}
      data/demo/{DemoSeeder.kt,DemoAiService.kt}
      background/{AiJobWorker.kt,CoachingWorker.kt,MediaCleanupWorker.kt,ReconcileWorker.kt}
      reminders/{ReminderScheduler.kt,ReminderReceiver.kt,OccurrenceActionReceiver.kt,SystemChangeReceiver.kt,NotificationFactory.kt}
      media/{PhotoCapture.kt,AudioRecorder.kt,MediaFiles.kt}
      ui/theme/{Color.kt,Type.kt,Shape.kt,Theme.kt}
      ui/components/{MetricCard.kt,SourceBadge.kt,CoverageLabel.kt,ScreenState.kt,ChartSummary.kt,LogSheet.kt}
      ui/navigation/{MetricMeNavHost.kt,Destinations.kt}
      ui/today/{TodayScreen.kt,TodayViewModel.kt}
      ui/journal/{JournalScreen.kt,JournalViewModel.kt}
      ui/profile/{ProfileScreen.kt,ProfileViewModel.kt,MeasurementHistoryScreen.kt,TargetScreen.kt}
      ui/logging/{FoodScreen.kt,ActivityScreen.kt,StepsScreen.kt,SleepScreen.kt,LoggingViewModel.kt}
      ui/routines/{HabitsScreen.kt,MedicationScreen.kt,ReminderScreen.kt,RoutinesViewModel.kt}
      ui/ai/{PhotoReviewScreen.kt,AudioCaptureScreen.kt,DraftReviewScreen.kt,DraftViewModel.kt}
      ui/insights/{InsightsScreen.kt,CoachingScreen.kt,InsightsViewModel.kt}
      ui/settings/{SettingsScreen.kt,PrivacyScreen.kt,SettingsViewModel.kt}
    src/test/kotlin/com/hbhaq/metricme/{QueueRecoveryTest.kt,JobCancellationTest.kt,ConsentTest.kt,DemoIsolationTest.kt}
    src/androidTest/kotlin/com/hbhaq/metricme/{MigrationTest.kt,ConfirmationTransactionTest.kt,NavigationTest.kt,AccessibilityTest.kt,ReminderActionTest.kt}
  backend/
    build.gradle.kts
    Dockerfile
    railway.json
    src/main/resources/application.conf
    src/main/resources/db/migration/V1__initial.sql
    src/main/kotlin/com/hbhaq/metricme/server/
      {Application.kt,Configuration.kt}
      auth/{Authentication.kt,UserIdentity.kt}
      routes/{HealthRoutes.kt,FoodRoutes.kt,ActivityRoutes.kt,CoachingRoutes.kt,JobRoutes.kt,DeletionRoutes.kt}
      service/{OpenAiClient.kt,EstimateValidator.kt,CoachingValidator.kt,UploadValidator.kt,IdempotencyService.kt,CleanupService.kt}
      persistence/{Database.kt,JobRepository.kt,UserRepository.kt}
      plugins/{Errors.kt,RateLimits.kt,RedactedLogging.kt}
    src/test/kotlin/com/hbhaq/metricme/server/{AuthTest.kt,IsolationTest.kt,ValidationTest.kt,IdempotencyTest.kt,OpenAiContractTest.kt,UploadLimitsTest.kt,DeletionTest.kt,HealthTest.kt}
```

## Stages and acceptance

0. This plan only. Stop for approval as requested by the supplied brief.
1. Establish verified compatible toolchain, CI, domain/contracts, schema and migration harness, profile/measurements/targets, theme/navigation, and isolated demo scaffold. Run debug assemble, domain/calculation/history tests and connected migration tests if an emulator is available. Report unavailable execution honestly.
2. Complete manual logging, occurrences, durable reminders and offline forms. Verify save/edit/delete, DST/overnight sleep, duplicates, schedule history and notification actions. Device restrictions remain physical-device checks.
3. Add Ktor, production auth, mocked AI contracts, photo/audio review and durable queue. Push verified source to GitHub and deploy backend to Railway with health checks. Demo works without paid credentials; production AI remains unavailable until OIDC/OpenAI configuration exists. Test server isolation, request limits, malformed/refused output, concurrent idempotency, queue recovery and cancellation.
4. Finish dashboard/analytics, coverage, historical targets, weekly/monthly reports and scheduling. Verify sparse data, three-item constraints, period boundaries and delayed execution.
5. Complete deletion/privacy/accessibility, migration/navigation tests, documentation, final UI screenshot review and release artifacts. Verify Railway endpoint after final deployment. Report exact build/test results, untested device behavior, limitations and required configuration. Never claim production readiness.

GitHub writes must preserve existing remote history after read-only inspection; never force-push. Railway setup uses the authorized repository source, PORT binding, readiness check, PostgreSQL, and server-side variables. Inspect existing resources before creating any. Never commit `.env`, signing keys, tokens, recordings, photos or personal demo data. Required configuration: OIDC issuer/audience/client ID, OpenAI server credential/models, database URL and release signing material. Public health endpoints expose no sensitive config. Do not enable production development-auth to make deployment appear functional.

## Stage 0 report

Added: this planning document only. No application code, stubs, build artifacts, GitHub upload or Railway resources created.

Executed: workspace listing, git status, git remote inspection, executable discovery for java/gradle/adb, Android SDK environment-variable checks, and official documentation searches. Workspace contained only `.git`; git status/remote emitted no entries. Tool discovery emitted no Java/Gradle/adb paths and no configured Android SDK environment values. This is PATH/config discovery, not proof no SDK exists elsewhere.

Not run: compilation, tests, emulator/device checks, API calls, dependency resolution, repository access verification and deployment. No test pass claimed.

Remaining: approve Stage 0, then stages 1–5 above. Platform-controlled authorization cannot be pre-approved by a chat message, but the requested GitHub upload and Railway deployment need no additional conversational permission. Android camera/microphone/notification prompts and health upload consent remain contextual product requirements; they cannot be granted in bulk here.
