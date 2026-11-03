# Android 0.5.0

Four main tabs: Log, Insights, You, Setup.

Log opens into a chat composer. Text, a recorded voice note, a photo, a camera capture or an audio file is sent to the existing authenticated AI journal API. Categories are chosen by AI, not by a picker. One send can create several categorized events. Clarification, undo and entry editing/deletion remain available; entry controls are under a small options menu. Older saved records are reconstructed as compact chat history from the database. Raw past messages are not replayed to AI as a growing conversation.

Insights has a separate question composer. Daily, Weekly and Monthly shortcuts request summaries through the existing database-backed question endpoint. Summary results render as tables; item lookups render record dates and names. No automatic summary request is started merely by opening the tab. Main-tab navigation uses saved explicit screen state, avoiding restored nested navigation stacks. Both chat drafts survive tab switching and activity recreation.

You contains name, age calculated from date of birth, gender, height and weight. Profile and measurements stay on this device; this release does not add profile cloud sync or send profile fields to the AI endpoint. Existing optional target configuration is preserved in storage.

Setup contains AI account sign-in. Technical fields remain collapsed. Data settings contains legacy offline records/drafts, reminders, sample mode, consent reset and cloud deletion. Legacy manual records are not included in cloud insights.

Audio imports are decoded locally with Android MediaCodec, downmixed and resampled to 16 kHz mono PCM WAV. Maximum: 80 seconds, 25 MB source. Temporary source files are removed on success/failure; unsupported codecs report an attachment error. Photo normalization and bounded recording remain unchanged.

Three-calendar-month meal-detail retention and authenticated database queries are unchanged from 0.4.1. Keywords are literal title/note matching; retrieval does not implement semantic search or arbitrary AI-generated SQL.

Validation: pending build and device-test results. Live paid AI and account approval on the user's phone require a separate end-to-end check.
