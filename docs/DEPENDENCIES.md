# Dependency decisions

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

Stage 3 retains the Android toolchain and adds:

- Ktor 3.4.3, an explicitly pinned stable release used with the existing Kotlin 2.3/JDK 17 build, rather than a claim to the newest Ktor release. Official release history: https://ktor.io/docs/releases.html . Actual compilation and test-host/container behavior are verified in CI.
- Retrofit and its kotlinx.serialization converter 3.0.0, published under Square's Maven coordinates. AppAuth Android 0.11.1 uses the system-browser code + PKCE flow: https://github.com/openid/AppAuth-Android . No client secret or manual bearer-token entry UI is used.
- PostgreSQL JDBC 42.7.14, including the October 2026 security fixes documented in https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.14 . HikariCP 7.1.0 is the Java 11+ artifact documented in https://github.com/brettwooldridge/HikariCP ; TCP keepalive is enabled and the pool is bounded at four connections.
- Logback 1.6.5 includes current logging security fixes: https://github.com/qos-ch/logback/releases/tag/v_1.6.5 . Its published source targets Java 11 at runtime; the container verifies it on JRE 17. No dynamic scanning, Janino, network/file appenders, MDC-discriminated paths or request-body logging are configured.
- OpenAI `gpt-4.1-mini` is configurable and documented to support image input and structured outputs: https://developers.openai.com/api/docs/models/gpt-4.1-mini . The Responses adapter uses strict `text.format` JSON schema and `store:false`: https://developers.openai.com/api/docs/guides/structured-outputs . WAV uses the file-transcription endpoint with configurable `gpt-4o-mini-transcribe`: https://developers.openai.com/api/docs/guides/speech-to-text . Account access and live paid behavior were not tested; mocks verify the wire formats and failure handling.

These pins and selected release notes do not constitute an exhaustive dependency/CVE audit or a production-readiness claim. CI tests compatibility; Stage 5 retains the release review.
