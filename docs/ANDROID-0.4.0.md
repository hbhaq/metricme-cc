# MetricMe 0.4.0: conversational journal

The main navigation is Journal, Insights and Setup. Journal is both the capture screen and the categorized history: type a message, record up to 80 seconds, take a photo or select an image, then Send. There is no separate main AI tab.

## Behavior

- A message can create multiple FOOD, MEDICATION, SLEEP, ACTIVITY, WATER, MOOD, MEASUREMENT or NOTE records.
- The first Send explains cloud storage, AI processing and automatic journalling. Consent is remembered per account for future explicitly sent messages and can be reset in Setup. Attaching or recording alone never uploads.
- Clear statements are saved automatically, with edit, delete and batch undo controls. Ambiguous statements ask a short question and save no partial batch. Food estimates are labelled. Other categories reject inferred/estimated quantities. A medicine photo without a statement of actual use cannot automatically record medication taken.
- Text, voice and photos use strict structured extraction. Unknown numeric values remain null, distinct from zero. No prescription, diagnosis, medication-dose recommendation, or arbitrary AI SQL/tool execution is implemented.
- Queries about history produce a validated date range/category. The server calculates period totals and coverage from PostgreSQL and retrieves up to 50 relevant saved entries. Default Insights covers 30 days. Queries support up to 3,660 days. Summaries are descriptive, not causal conclusions, personalised coaching or medical advice.
- Sleep summaries sum reported durations per logged day. Overlapping/duplicate reports are not deduplicated; the interface discloses that separate sleep reports may overlap.

## Persistent memory

`journal_entries` stores each categorized fact with an account owner hash, UUID, event time, category and edit revision. These records do not inherit temporary AI jobs' seven-day expiry: they remain until the user deletes them. Raw photos/audio are handled in memory by the server, not stored as journal media. OpenAI provider retention is separate; requests set `store=false`.

`journal_messages` records submission hashes/status/results to prevent duplicate processing after network retries. A completed batch and its receipt are committed in one transaction. Edits/deletions invalidate cached copies in source receipts and insight answers. Cancellation/account deletion prevents late processing from resurrecting records. Minimal receipt tombstones remain to prevent replays; journal payloads are removed.

Android uses an account-and-backend-scoped private cache and retains a prepared message for explicit retry. There is no automatic background upload of newly attached media. Switching accounts clears the visible journal and composer. API authentication and owner constraints apply to every journal route. Local manual Room records remain separate and are not silently uploaded or included in cloud summaries.

Each extraction uses the current message and bounded clarification context. Saved history is queried as records/aggregates, not appended as an ever-growing OpenAI conversation. AI extraction can still make mistakes; the original reported text is shown with the latest result and every saved entry is editable.

## Routes

- `POST /v1/journal/messages` — authenticated, consented, bounded submission with Idempotency-Key.
- `GET /v1/journal/entries` — cursor pagination and category filter.
- `PUT /v1/journal/entries/{id}` — validated edit with revision conflict detection.
- `DELETE /v1/journal/entries/{id}` and `DELETE /v1/journal/messages/{id}` — delete/undo.
- `GET /v1/journal/insights` — date range, timezone, coverage and descriptive aggregates.
- `DELETE /v1/account/data` — removes cloud journal payloads and temporary AI results. Setup provides an explicit confirmation dialog.

Payloads stay below Vercel's request-body ceiling; photos are normalized and bounded, and the journal recorder limits voice notes to 80 seconds. The original 120-second recorder remains only for older AI drafts.

## Verification and distribution

The 0.4.0 download reuses the local development signing key introduced with 0.3.1, permitting an in-place update from that downloadable version. A 0.3.0 installation still has the earlier incompatible CI debug signature.

- Verified source: `b3945ff8af52b5c7565dcd40a858a81b5254439c`.
- [Android CI](https://github.com/hbhaq/metricme-cc/actions/runs/41667842349): build/lint and 35 emulator tests passed. Domain: 29 tests passed. Lint: zero errors, 14 warnings (KTX/ExifInterface suggestions and an empty super-call warning).
- [Backend CI](https://github.com/hbhaq/metricme-cc/actions/runs/41667842305): 13 contract tests and 20 backend tests passed, including real PostgreSQL journal tests and strict upstream-response fixtures. The actual non-root Docker container passed readiness/authentication smoke checks. Total: **97 tests, zero failures or skips**.
- Real emulator screenshots of the merged Journal, typed draft after recreation and Setup were inspected.
- Production API deployment: `dpl_3hwZhA2dgHMmTujc8GGdgDWDLr4D`, serving [metricme-cc.vercel.app](https://metricme-cc.vercel.app). Liveness and PostgreSQL readiness return 200 with AI/auth configured. Unauthenticated journal entries and insights return 401.
- APK hosting deployment: `dpl_C9ngZeQ5haYS4T1K843nu6kDoxqB`.
- [Download MetricMe 0.4.0](https://metricme-downloads.vercel.app/MetricMe-0.4.0.apk). Published APK SHA-256: `5aa8f6b101b466b93cf0f809ceb9ef725d8b15eb2a27ef16bbaf6c99563e302e`.
- Signing certificate SHA-256: `783803cc1d876f0712b8c3692a6c232d361c2b3ca40ced93580b28700bdeb451` (same as downloadable 0.3.1). The private key stays in ignored local `.tools/signing/`.

No live paid OpenAI request or user sign-in was performed during these checks. End-to-end voice/photo extraction with the user's configured key and Redmi sign-in still require phone verification. Summary features are descriptive retrieval/aggregates; overlapping sleep reports and semantic extraction mistakes remain practical limitations.
