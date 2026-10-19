# MetricMe API deployment

Vercel is the selected host for the Ktor API. The native Android UI is installed from an APK; it is not a browser application.

## Verified deployment — 2026-10-19

Production API: **https://metricme-cc.vercel.app**. Deployment `dpl_7FnHW5v82urbZGLrRSCtwh9kDtEZ` runs the existing Ktor/JVM container on the Hobby plan. The source baseline was `9d91d8e`, with the Vercel configuration subsequently committed as `7ab4979`. No backend application code or Android APK was changed for this deployment.

Unauthenticated public production requests returned: `GET /` 200, `GET /health/live` 200 (`aiConfigured:false`, `authConfigured:false`), `GET /health/ready` 503 (`database_unavailable`), `POST /v1/food-estimates` 401, and `GET /v1/jobs/{id}` 401. The database has not been provisioned because Neon requires account-holder acceptance of its Marketplace terms. Real AI requests and real OIDC login remain unverified and disabled. The earlier Stage 3 test results are historical verification of the unchanged application; deployment checks do not replace them.

## Vercel configuration

The project is `me-8d70/metricme-cc` on the existing Hobby plan. `vercel.json` defines a container service rooted at the repository, with `Dockerfile.vercel` as its entrypoint and a catch-all rewrite to the API. Vercel's current container runtime supports the existing JVM backend; no JavaScript rewrite is needed. See [container runtime](https://vercel.com/docs/functions/runtimes/container) and [Services](https://vercel.com/docs/services).

Set `PORT=8080` in Production and Preview so Vercel routes to the non-root Java process. Deploy from the linked root with `vercel deploy --non-interactive --scope me-8d70`; use `--prod` for production after verifying the preview. `.vercelignore` excludes Android sources, local tools, caches, build outputs, signing files and local environment files. `.vercel/` and `.env.*` are ignored by Git. Do not commit provider credentials.

The backend needs `DATABASE_URL` for PostgreSQL. Provisioning the free Neon database requires the account holder to accept the Neon Marketplace terms first. No paid database plan is authorized. Keep `OPENAI_API_KEY`, `OIDC_ISSUER`, `OIDC_AUDIENCE` and `OIDC_JWKS_URL` unset until the intended provider is configured. Without these settings the API remains locked; `/health/live` can succeed while `/health/ready` returns 503 for an unavailable database. Neither liveness nor a successful platform deployment implies AI readiness.

Vercel scales idle containers down, so its in-process hourly cleanup is opportunistic. Cleanup also runs when the process starts and on new job claims; do not promise deletion at an exact wall-clock deadline while the service is scaled to zero. Configure a durable scheduled database cleanup before making a strict retention guarantee.

Vercel documents a [4.5 MB function payload limit](https://vercel.com/docs/functions/limitations#request-body-size), below this portable backend's 15 MiB JSON envelope bound. Base64 increases media size by about one third: the current maximum 120-second PCM recording exceeds the platform envelope. Before enabling real uploads on this host, implement and verify a client-side envelope bound or a consented private direct-upload flow. Larger requests can return platform 413 errors before reaching Ktor. Live large-media behavior has not been verified.

Automatic Git deployments also require connecting GitHub under the Vercel account's Login Connections. GitHub source uploads and authenticated CLI deployments can work before this separate account connection is enabled.

## Previous Railway deployment attempt

The user authorized GitHub upload and Railway deployment. Creating a private `MetricMe` Railway project on 2026-10-18 was rejected by Railway with: **“Free plan resource provision limit exceeded. Please upgrade to provision more resources!”** No MetricMe service, database, public URL or paid plan change was created. Existing applications and their data were not modified. Deployment remains blocked until the account has capacity; this is separate from the code/build verification.

## Railway configuration

1. Create a private project with a production environment. Add the Railway PostgreSQL template, retaining its generated credentials and persistent volume.
2. Deploy `hbhaq/metricme-cc`, branch `main`, root directory `/`, using the committed `Dockerfile` and `railway.json`.
3. Set `DATABASE_URL` to `${{Postgres.DATABASE_URL}}` using Railway variable references. Never paste database credentials into Android or source control. The service uses four pooled connections and additive PostgreSQL DDL for its initial receipt schema.
4. Use `/health/ready` as the deployment health check. Generate a Railway HTTPS domain pointing to the service's `PORT` (default 8080). `/health/live` reports process liveness; readiness verifies PostgreSQL. Both explicitly report whether AI and authentication are configured.
5. Keep real AI locked until the following variables are set in Railway: `OPENAI_API_KEY`, `OIDC_ISSUER`, `OIDC_AUDIENCE`, `OIDC_JWKS_URL`. Model defaults are `gpt-4.1-mini` and `gpt-4o-mini-transcribe`; they can be overridden server-side. `DEV_AUTH_ENABLED=true` prevents startup; no shared static development bearer is supported.
6. Verify unauthenticated health-data routes return 401; invalid issuer/audience/signature/expiry tokens also fail. Then test with your own provider and account. Live paid API calls are opt-in and were not made during implementation.

The Docker image uses a JDK build stage and a non-root JRE runtime with a 256 MiB Java heap. Two uploads/processes are allowed at a time. The server accepts bounded JSON envelopes containing base64 media, with 10 MiB decoded limits, a 20-second upload read timeout, and validated file signatures. Images are JPEG/PNG up to 4,194,304 pixels; the app transcodes to at most 2048 pixels on either side. Audio is canonical mono 16-bit PCM WAV at 16 kHz, up to 120 seconds.

## Sign-in provider setup

OIDC (OpenID Connect) delegates sign-in to an identity provider. Use a native/public Android client with authorization-code + PKCE, no client secret, and redirect URI `com.hbhaq.metricme:/oauth2redirect`. Register a dedicated API audience and RS256 access tokens. Configure issuer and JWKS URLs exactly as the provider publishes them; issuer trailing slashes can be significant. Tokens must include `sub`, `exp`, the expected issuer and API audience. The backend does not treat an ID token or a public client ID as user authentication.

In Android: **You → Photo, audio & private drafts → Set up AI connection**. Enter the public backend URL, issuer, Android client ID and API audience, then sign in with the system browser. The `audience` authorization parameter follows providers such as Auth0; use a compatible provider configuration. Provider-specific scopes/claims and callback behavior require a real account test. Google sign-in by itself is not a drop-in replacement for a dedicated API audience.

Session state is AES-GCM encrypted with an Android Keystore key. This protects credentials, not the Room database. Sign-out clears local credentials and suspends pending uploads; the provider's browser session can remain active. Account-bound jobs cannot switch to a newly signed-in subject. An interrupted token exchange requires a fresh sign-in rather than replaying the authorization code.

## Local server and tests

Set environment variables from `.env.example` in your shell; Gradle does not automatically load that file. Start a local PostgreSQL database, then:

```sh
./gradlew -PbackendOnly :contracts:test :backend:test :backend:run
```

The actual PostgreSQL integration test uses `TEST_DATABASE_URL`; without it that one local test is explicitly skipped. CI supplies a real PostgreSQL 17 service and runs it. The remaining tests mock upstream OpenAI and token/JWK material. CI also builds the actual Docker image and checks database readiness, unauthenticated rejection and the non-root runtime using `scripts/backend-smoke.sh`.

Client HTTPS is mandatory. For local Android network testing, use a trusted HTTPS tunnel with your test identity provider; cleartext traffic and certificate bypasses are not enabled in release or debug builds.

## API and idempotency

`POST /v1/food-estimates`, `POST /v1/activity-transcriptions`, `GET/DELETE /v1/jobs/{id}`, and `DELETE /v1/account/data` require bearer authentication. POST routes require a canonical UUID `Idempotency-Key`. Bodies use the serializable contracts module. Errors contain safe codes and retryability, never transcripts, media, model output, credentials or database errors.

Receipts are keyed by SHA-256 of validated issuer+subject and operation UUID, with a request hash and PostgreSQL transaction lock. Conflicting reuse returns 409; completed retries return the same result. Requests are capped at five, per-subject new operations at ten/hour, and per-instance concurrency at two. A 429 before an upstream success can retry. Retry-After seconds/HTTP dates are respected; PostgreSQL and Room retain the next eligible attempt time. Waiting does not consume a network attempt. WorkManager uses exponential backoff with a small additional jitter and may deliver later than the deadline. A provider delay beyond the seven-day retention window stops automatic retries. Transport uncertainty, upstream 5xx, process interruption or a stale five-minute processing lease is not silently rerun upstream: the client shows an uncertain outcome and requires deliberate review/new consent. An upstream call cannot be made exactly once across every crash boundary; this limitation is explicit.

Cancellation tombstones the local draft before cancelling work. Server cancellation discards the result and prevents a late in-flight result from overwriting it. The client keeps account-bound purge work until acknowledged. PostgreSQL result/receipt retention is seven days, with hourly cleanup and cleanup on new operations. The server holds raw media in bounded request memory only, not files or object storage. Application-level request/body logging is absent. Configure provider and platform log retention independently.

OpenAI requests use `store:false`; this is not a promise of zero OpenAI retention. Review the provider's data controls before using sensitive real data.

## Rollout checklist

- [x] Vercel container live and public HTTPS health/authentication checks confirmed.
- [ ] Neon terms accepted and PostgreSQL provisioned; readiness returns 200.
- [ ] Vercel media-envelope bound or private direct-upload flow implemented and verified.
- [ ] OIDC native client, exact issuer/audience/JWKS and Android callback verified.
- [ ] OpenAI key stored only in Vercel secrets and account access to configured models verified.
- [ ] One explicit, opted-in real photo/audio request tested; costs and retention reviewed.
- [ ] Physical-device camera/audio interruption, OAuth return, background queue, revoked permissions, offline cancellation and process recovery checked.
- [ ] Stage 5 privacy/deletion, accessibility and release/signing review completed.
