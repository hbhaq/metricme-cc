# MetricMe 0.4.1: calendar insights and meal memory

Insights now has Daily, Weekly and Monthly selectors, previous/next navigation and a calendar date picker. Daily covers the selected day; weeks start Monday; months use calendar boundaries. A current week/month stops at today, so future days are not treated as missing records.

The Insights question composer keeps a small in-memory conversation. Questions use the displayed period unless the user specifies dates. A “when did I last…” lookup searches beyond that period. The AI extracts a date range, category, short item keyword and query type; PostgreSQL performs the search. Keyword filtering happens before the result limit. Latest lookups return one matching saved record, while broader item searches return up to 50. This is a literal title/note search, not an exhaustive semantic or synonym search. Results say “matching record,” not an inferred claim about an unrecorded event. Follow-ups use the preceding question, not a growing copy of the entire journal.

Insights submissions use an explicit query-only mode: a statement typed there cannot silently create journal entries. The Journal composer remains available for logging and also supports history questions.

## Three-month meal retention

FOOD details are retained for three **calendar months**, measured from the event instant against the UTC calendar-month cutoff. Expired food entries become nutrition-only records:

- Meal title, notes, portions, units and exact time are removed.
- Calendar day, calories/macros, estimated flag and entry count remain available for long-term summaries.
- Archived times are represented internally by a noon anchor in the recorded timezone; the new app displays only the date and does not offer an edit that could restore meal details. Older records without timezone metadata default to UTC.
- Associated source receipts and saved insight-answer copies are cleared. Minimal idempotency tombstones remain to prevent replayed submissions.
- Other categories, such as medication and sleep, retain their existing behavior.

Compaction runs on server maintenance while the container is running and before journal access. Idle/suspended hosting does not guarantee a wall-clock deletion task, but journal routes compact expired details before returning records. The new Android app also redacts expired food details in cached records and displayed results; private cache maintenance runs when the app/worker is permitted to execute. A force-stopped device cannot perform background work until resumed. Raw source media continues to follow its existing local cleanup behavior.

Item lookup does not guess after the detail window: if no retained match exists, it explains that older nutrition records cannot identify a particular meal. Daily/weekly/monthly summaries still include retained nutrition values.

## Compatibility and verification

The new client opts into retention metadata through `X-MetricMe-Features: meal-retention-v1`. Responses to the strict-parsing 0.4.0 client omit the additional metadata fields so existing installs keep reading journal records while upgrading. The 0.4.1 APK uses the same development signing certificate as 0.3.1 and 0.4.0.

Validation includes real PostgreSQL keyword lookup beyond the recent-entry page, literal wildcard escaping, owner isolation, meal redaction without changing nutrition/day, cached-receipt removal, calendar-month cutoff boundaries, query-only behavior, legacy JSON compatibility, and emulator period/composer checks.

- Android source: `89eb75c5f5f7619220c7fd7435c9fb78b34764bb`.
- [Android CI](https://github.com/hbhaq/metricme-cc/actions/runs/41677215485): 29 domain tests and 37 emulator tests passed. Build/lint passed: zero lint errors, 14 style/ExifInterface warnings.
- [Backend CI](https://github.com/hbhaq/metricme-cc/actions/runs/41677215446): 14 contract tests and 22 backend tests passed, including real PostgreSQL tests. Container readiness/authentication checks passed. Total: **102 tests, zero failures or skips**.
- Production API: `dpl_4UEMbWQfsYZa5orChFDBBnm87uRG`, built from `dc9b34b13e6fbd8a9735562e36f56a8a343365b6`. Backend/contracts are identical to the final Android revision; the subsequent change only fixes Android selected-period loading. Liveness/readiness return 200, journal routes reject unauthenticated access with 401.
- [MetricMe 0.4.1 APK](https://metricme-downloads.vercel.app/MetricMe-0.4.1.apk): SHA-256 `4b0a5dd99c2431eb99363584a78832f47c9e0b01f6fc59ef63a0a7089372a269`.
- Signing certificate SHA-256 `783803cc1d876f0712b8c3692a6c232d361c2b3ca40ced93580b28700bdeb451`, matching the downloadable 0.3.1 and 0.4.0 builds.

These checks used synthetic upstream fixtures, not a paid live OpenAI request or a signed-in phone session. Live question planning and phone sign-in still need device verification.
