# Stage 1 dependency decisions

Checked 2026-10-18 using official docs and actual published Maven/SDK metadata. Exact pins live in `gradle/libs.versions.toml`; actual resolution/build results live in the stage report.

- AGP 9.2.1 / Gradle 9.4.1 / JDK 17: https://developer.android.com/build/releases/agp-9-2-0-release-notes . This deliberately pins a documented compatible AGP line rather than upgrading every tool independently.
- AGP built-in Kotlin, Kotlin/Compose compiler plugin 2.3.10: https://developer.android.com/build/migrate-to-built-in-kotlin . No legacy Android Kotlin plugin is applied.
- KSP 2.3.9: https://github.com/google/ksp/releases ; Hilt 2.60.1: https://dagger.dev/hilt/gradle-setup . AndroidX Hilt is not needed; the Activity obtains the Hilt ViewModel through ViewModelProvider.
- Compose BOM 2026.09.00 and Room 2.8.5 confirmed in Google Maven metadata. Lifecycle 2.11.0, Navigation 2.10.2: https://developer.android.com/jetpack/androidx/versions/stable-channel . Extended icons are independently pinned at 1.7.8 because that artifact is frozen independently of current Compose UI releases.
- SDK `platforms;android-37.0` is on stable channel 0 in https://dl.google.com/android/repository/repository2-3.xml . Target API 37; minimum API 26. AGP 9.2 supports 37.0. The 37.1/37.2 SDK variants are not required for the chosen major-API target.
- Coroutines 1.10.2 and JUnit 4.13.2 are intentionally pinned, not claimed as the latest. Dependency resolution verifies availability; no dynamic versions.
- kotlinx.serialization BOM 1.8.1 aligns the installed app runtime with the Room migration-test schema reader. Instrumentation loads app classes ahead of test-APK classes, so resolving a newer runtime in the test APK alone is insufficient. The first emulator schema test exposed this mismatch; relevant upstream discussion: https://github.com/Kotlin/kotlinx.serialization/issues/2968 .

The Gradle distribution SHA-256 is recorded in wrapper properties after verifying the downloaded archive against the published checksum. JDK and Android tools are ignored build-time installations. Dependency artifact verification metadata will be generated and reviewed after dependency resolution; do not claim cryptographic verification for all artifacts before that review.

Stage 2 adds WorkManager runtime 2.12.0 and AndroidX test rules 1.7.0. The Android alarm/notification guidance and WorkManager release documentation were rechecked; see REMINDERS.md. The rest of the verified toolchain is retained. Room schema 2 uses an explicit non-destructive Migration(1, 2).
