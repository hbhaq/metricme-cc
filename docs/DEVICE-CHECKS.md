# Device checks

Stage 1: install the debug APK, test compact and expanded widths, 200% text scaling, light/dark mode, TalkBack focus/labels, keyboard visibility, rotation while editing, background/process recreation, cancellation, measurement edit/delete, imperial display, unusual-value confirmation and sample/personal isolation. Confirm no sensitive data is included in Android backup/device transfer.

Stage 2: notification denial, Doze, manufacturer battery limits, reboot, clock/timezone/DST changes, schedule edits and duplicate actions. Force-stop limitations must be explained.

Stage 3: real camera/microphone permission denial/revocation, picker cancellation, audio interruptions/calls, recording container validity, offline queue restart/cancellation, explicit upload consent, oversized media and privacy-conscious logging.

Stage 3 specifically: validate external-camera return and retake/cancel on multiple camera providers; Photo Picker cancellation and orientation; microphone silencing/revocation during capture; stop/discard while a call or background transition occurs; verify the exported private recording is mono 16 kHz PCM WAV. Exercise provider PKCE browser return, token expiry/refresh, local sign-out during refresh, account switching, and an interrupted token exchange. Queue with consent, kill the process, restart offline, cancel/delete, reconnect and confirm no late result enters the journal. Check Retry-After deadlines survive recreation and defer uploads without consuming the five-request budget. Confirm deletion/purge remains visibly pending for the original account. Actual device Doze/force-stop restrictions and provider behavior remain pending even when Room/Compose/mocked-server tests pass.

Stage 5: full user-data deletion, accessibility pass, real release signing, authenticated Railway endpoint checks and credential review.

These are pending physical-device checks, not test results.
