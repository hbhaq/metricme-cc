# Stage 3 verification report

Scope: Ktor backend, authentication, validated OpenAI adapters, native photo/audio drafts, explicit upload consent, editable review, durable offline work, sample examples and deployment preparation. Stage 4 analytics/coaching and Stage 5 final privacy/deletion/accessibility are not implemented here.

## Implemented

- Shared serializable food/activity/job/error contracts and independent validation of finite bounded values, optional unknowns, clarification responses and macro/calorie mismatch warnings.
- Ktor API with PostgreSQL receipts, per-subject ownership, transaction-serialized concurrent claims, payload-hash conflicts, result replay, cancellation, bounded retries, rate/concurrency limits, retention cleanup and safe typed errors. No request/body logger is installed.
- RS256 OIDC access-token validation of signature, exact issuer, audience, expiry and subject. Unconfigured authentication rejects requests. No static client bearer secret or remotely enabled development bypass exists.
- OpenAI Responses adapter with strict JSON schemas, image input, `store:false`, explicit untrusted-input instructions and no tools. WAV transcription is followed by structured activity extraction. Model names are server configuration; absent steps/loads remain unknown. Refusal, malformed/incomplete output and invalid ranges do not become confirmed records.
- Native Photo Picker and external camera intent capture, bounded private import, orientation normalization, metadata-free JPEG preview and scoped FileProvider. Using the external camera avoids a blanket camera permission; Android camera-provider behavior remains a physical-device check.
- Foreground-only AudioRecord capture to canonical mono PCM WAV, finalized headers, 120-second limit, microphone permission denial and interruption handling. Recording stops when the screen leaves the foreground; no background microphone service is started.
- Private Room drafts/jobs using the existing schema tables. Context and review edits survive rotation; no draft contributes to totals. Explicit confirmation validates against domain rules and inserts a reviewed food batch atomically as independently editable entries, or a structured workout. AI provenance stays labeled after confirmation and subsequent manual corrections.
- Consented network-constrained unique WorkManager uploads, maximum five attempts, same operation key on retries, account/backend binding, sign-in suspension, cancellation tombstones and late-result rejection. Cancelled work cannot overwrite user edits. Remote purge is retried with the matching account.
- AppAuth system-browser authorization-code + PKCE, public-client configuration and AES-GCM session state protected by Android Keystore. Signing out cannot be undone by an in-flight refresh callback. This does not encrypt the Room database or sign out the identity provider's browser session.
- Credential-free sample reviews are visibly labeled, run locally and cannot write personal entries. Manual logging remains offline and account-free.
- Multi-stage non-root Docker runtime, Railway configuration, environment template, PostgreSQL-backed backend CI, deployment smoke checks and emulator UI artifact capture.

## Verification

Verified source commit: `aaeec1d83e1c2b036b657b298ace43988a84776e`. Both final workflows passed: [Android build and emulator](https://github.com/hbhaq/metricme-cc/actions/runs/39152724736), [backend, PostgreSQL and container](https://github.com/hbhaq/metricme-cc/actions/runs/39152724727). Subsequent delivery changes contain only documentation and actual emulator screenshots.

| Suite / check | Actual result |
|---|---|
| Pure domain tests | 29 passed, 0 failures, 0 skips |
| Shared contract/validation/retry tests | 9 passed, 0 failures, 0 skips |
| Backend tests | 15 passed, 0 failures, 0 skips |
| API 35 emulator tests | 29 passed, 0 failures, 0 skips |
| Debug app and instrumented-test APK build | Passed |
| Android lint | 0 errors, 9 warnings: 1 ExifInterface advisory and 8 UseKtx style suggestions |
| Actual Docker image build and smoke test | Passed: PostgreSQL readiness, 401 for protected unauthenticated routes, non-root runtime |

**82 tests passed in total.** The backend tests include generated-key JWT verification, mocked Responses and audio-transcription/extraction, malformed/refused/negative/incomplete output, stalled response-body timeout, ownership/isolation, and actual PostgreSQL concurrent claims, persistence, cancellation and Retry-After deadlines. Emulator tests include metadata removal/orientation, invalid photo cleanup, offline draft edits/rotation without uploading, credential-free sample isolation, atomic confirmation, unknown steps and stale/cancelled results, plus all earlier logging/profile/reminder checks.

Verification exposed and fixed a same-timestamp recovery bug by selecting jobs by monotonic revision before timestamp, two cross-module nullability errors, and non-void JUnit test declarations. The final complete suites pass; tests were not disabled to obtain these results.

Local Windows execution passed eight shared contract tests and six backend route/media/cancellation tests. The PostgreSQL test was explicitly skipped locally because `TEST_DATABASE_URL` was not set; CI supplies PostgreSQL. A local combined Android attempt hit the previously observed Windows generated-output deletion failure. It is not reported as a successful Android build.

Commands executed successfully in CI:

```sh
./gradlew -PbackendOnly :contracts:test :backend:test :backend:installDist :backend:distZip
docker build -t metricme-test .
bash scripts/backend-smoke.sh
./gradlew :domain:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
bash scripts/device-tests.sh
```

No paid/live OpenAI request was made. No real identity-provider account was used. All external model responses in tests are mocked; JWT tests use generated test signing keys.

Read the actual test reports and packaged APK metadata from the CI artifacts. The APK is `com.hbhaq.metricme`, debug version `0.3.0`, version code 3, minSdk 26. The complete screenshot archive SHA-256 matched GitHub's published digest. The final Today and AI-assistance screenshots were visually inspected and saved in `docs/screenshots/`; they are real app captures, not mockups.

[Download debug APK/build reports](https://github.com/hbhaq/metricme-cc/actions/runs/39152724736/artifacts/12145927133) and extract `app/build/outputs/apk/debug/app-debug.apk`. [Backend distribution and reports](https://github.com/hbhaq/metricme-cc/actions/runs/39152724727/artifacts/12145133826). [Device reports and screenshots](https://github.com/hbhaq/metricme-cc/actions/runs/39152724736/artifacts/12145947097). Artifacts expire 2027-01-16; source remains rebuildable. Persistent release/update signing is not configured for these development artifacts; it remains Stage 5.

## Railway and credentials

Deployment authorization was already provided. Railway rejected project creation with **“Free plan resource provision limit exceeded. Please upgrade to provision more resources!”** No MetricMe project, database, service or URL was created, and no existing application or paid plan was changed. Code is prepared for deployment, but deployment is not complete.

Real AI also needs a server-side OpenAI key, an OIDC native client with PKCE, exact issuer/API audience/JWKS and the Android callback. None were invented or embedded. See [DEPLOYMENT.md](DEPLOYMENT.md) for setup, service variables, request limits, idempotency and recovery behavior.

## Limits and remaining checks

- Network outcome uncertainty cannot guarantee exactly one paid upstream execution across every crash. The server stops automatic upstream retries when the outcome is uncertain; a deliberate new request requires review/consent.
- Per-instance concurrency is two; PostgreSQL per-subject quotas remain shared across instances. The server has no autoscaling/cost policy configured by this code.
- Raw media is request-memory-only on the server. Cached results/receipts expire after seven days with hourly cleanup; Android pending media expires after seven days and confirmed media within 24 hours. Offline/force-stopped Android cleanup runs on the next permitted execution. OpenAI/platform retention is separate.
- Cancelling/deleting an already-sent operation cannot retract data already processed upstream. Account-bound server-result purge can remain pending offline or signed out; the backend retention window is a fallback. Stage 5 must finish the whole-account deletion UI.
- Real camera providers, microphone revocation/audio focus/calls, native recording validity on hardware, OAuth provider return/refresh, device process death, Doze/OEM background restrictions, TalkBack and large font behavior require physical-device checks.
- Railway deployment, public HTTPS health checks, real OIDC and opted-in live AI verification remain unverified until capacity/configuration are available.
- Remaining stages: Stage 4 analytics/coaching; Stage 5 complete privacy/deletion, accessibility and release/signing review. No production-readiness claim is made.

## Changed files

Added `contracts/`, `backend/`, `Dockerfile`, `.dockerignore`, `railway.json`, `.env.example`, backend CI/smoke script, native `ai/` media/session/API/queue/draft/UI classes, FileProvider paths, draft database/UI tests, this report and deployment documentation. Updated module/catalog/build setup, application/activity/manifest, database DAO access, journal provenance, Log/You navigation and screenshot capture. Room table definitions remain at schema version 2; no destructive migration or data reset was introduced.
