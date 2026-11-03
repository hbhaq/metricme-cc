# Android 0.3.1: simpler navigation and code sign-in

Source revision: `33b00899ba8144f4a14b63a0f2140bc5a777e35e`.

- Main tabs: Today, Journal, AI, Setup. The unfinished Insights placeholder is no longer in the main navigation.
- Setup contains the AI account, profile/measurements, routines/reminders and sample mode. Technical connection fields are collapsed under Advanced; the public MetricMe service configuration is supplied by default on a fresh install.
- AI contains photo/audio capture and private drafts. A signed-out draft links directly to Setup and saves its edits before navigating.
- Primary sign-in uses Auth0 device authorization: request a short-lived code, approve it in the browser and manually return to the app. This avoids the redirect activity implicated in the reported phone crash; the original crash cause has not been established from a device trace.
- The app handles pending approval, slower polling, declined/expired codes, cancellation and encrypted session persistence. Refresh requests explicitly support sessions without an authorization-code redirect. Existing API authorization and upload-consent requirements are unchanged.
- Browser redirect sign-in remains an advanced fallback, with callback exceptions reported rather than left uncaught.

Auth0's Device Code grant was enabled for the existing MetricMe Android client. A live unauthenticated smoke check successfully obtained a 900-second device code with a five-second polling interval. No user tokens or paid AI requests were used in that smoke check.

## Verification

[Android build, lint and emulator tests](https://github.com/hbhaq/metricme-cc/actions/runs/41658139806) passed: 29 domain tests and 34 Android emulator tests, zero failed/skipped. Lint: zero errors, 13 warnings (existing ExifInterface warning and KTX style suggestions). Four added authentication tests cover polling/backoff and refresh reconstruction, denial, expiry/cancellation, and issuer verification; one added UI test covers Setup and AI navigation. Real emulator screenshots of Setup and AI were inspected.

The reported Redmi sign-in and a complete live AI upload still need verification on the user's phone. The old callback crash is bypassed by the new flow, not claimed to have a proven root-cause fix.

## Development APK distribution

Download: [MetricMe 0.3.1 APK](https://metricme-downloads.vercel.app/MetricMe-0.3.1.apk).

- Published APK SHA-256: `23dc880b3c5e2fd386c8db4e7612b8eb5cf155eae8b2fe4ec92646a53436869e`.
- Signing certificate SHA-256: `783803cc1d876f0712b8c3692a6c232d361c2b3ca40ced93580b28700bdeb451`.
- The downloadable APK is re-signed with a persistent **development-only** key kept in ignored local `.tools/signing/metricme-development.jks`. Never publish that private key. Production release signing remains separate.
- Version 0.3.0 had a different ephemeral CI debug key; Android cannot update it with this APK. The user confirmed there are no saved records and a fresh install is acceptable. Uninstall 0.3.0 before installing 0.3.1. Uninstalling clears local records.
- Later downloadable development updates must reuse this local signing key to preserve update compatibility.

The Vercel download project remains separate from the API project. No backend deployment or OpenAI secret change is required for this Android update.
