# SAM — Native Android build candidate (v3.3)

A Persian-first native **single-user** Android assistant foundation for Samsung Galaxy S24 Ultra (12 GB RAM), Android 17. Package: `ir.elviran.sam`. It includes single-user personal records, factory production control and status data, voice input/output and an optional online AI endpoint. It does not implement multi-user accounts or roles.

## Build on GitHub

1. Extract this ZIP and upload the **contents of `SAM-Native`** to the root of your repository (not the containing folder).
2. Commit to the `main` branch.
3. Open **Actions → Build SAM Android APK → Run workflow** (or push to `main`).
4. Download the `SAM-debug-apk` artifact from the successful run.

The workflow uses JDK 17 and Gradle 8.13, runs unit tests, and builds the debug APK. This source package does not include a generated APK.

## Implemented in this candidate

- Kotlin + Jetpack Compose dark RTL application shell and bottom navigation.
- **Dynamic Data Engine (موتور داده پویا):** the user creates their own modules, defines fields of 12 types
  (text, long text, integer, decimal, Jalali date, time, single-select, multi-select, yes/no, file,
  computed, relation), sets required/min/max/options/default, reorders fields, then adds, edits,
  searches and deletes records — all at runtime, with **no code change and no new tables**. Stored in
  Room (`dyn_modules` / `dyn_fields` / `dyn_records`), included in backup, and validated by a pure-logic
  engine. Computed fields evaluate formulas like `{قیمت}*{تعداد}` through the bounded arithmetic parser.
- **Jalali (Solar Hijri) calendar engine** — pure Kotlin, no ICU dependency, using the official-calendar
  “breaks” leap rule. It correctly treats **1403 as a leap year** (30 Esfand 1403 == 20 March 2025) and
  **1404 / 1405 as non-leap**, rejects 30 Esfand in non-leap years, and clamps a monthly due-day of 30
  to 29 in a non-leap Esfand. Covered by unit tests.
- Room persistence for conversation history, notes, tasks, money entries, factories, production lines, machines, production orders, memories, documents, audit events and chat history. Database v4 → v5 migration removes legacy HR tables while preserving personal and production records.
- Personal notes, tasks, money entries and memories support create/edit/delete from the UI (task completion toggle included). Factory, line, machine and order records support create/edit/delete with dependency checks to prevent orphaned child records. Document metadata can be added and removed.
- Persistent conversation history in Room, included in local JSON backup and supplied as the latest 20 turns of context to the configured AI endpoint, plus Persian speech recognition request (`fa-IR`) and Android Text-to-Speech when a Persian voice is installed; **push-to-talk only**.
- OpenAI-compatible online chat endpoint configuration; API key encrypted with Android Keystore.
- Network calls dispatched on `Dispatchers.IO`; endpoint validation requires HTTPS except local development endpoints.
- Bounded arithmetic parser for Persian and English digits; no dynamic code evaluation.
- Action risk policy requiring approval for sensitive, destructive and external actions.
- Versioned JSON backup and restore (now **v3**, including Dynamic Data Engine and conversation history; legacy HR fields in older backups are ignored). Restore validates payload and record relationships before replacing all table families inside one Room database transaction; malformed files or failed inserts roll back the complete restore.
- Document picker stores metadata and persisted URI permission; source file bytes are not embedded in JSON backup.
- **Factory tools (ابزار کارخانه):**
  - **Taffy capacity rules:** Friday off; Saturday starts at 07:30; Sunday–Thursday at 07:00; Saturday–Wednesday 75 batches (72 net + 3 loss); Thursday 54 (52 net + 2 loss); Thursday 24:30–Friday 07:00 cleaning; filled-product batch mass 156.5 kg. A 14-day Jalali-date view displays these capacities.
  - **Kavandish Classic packaging split:** bitter and fruit quantities are kept separate and each is split in parallel between two-end and cushion packaging, defaulting to 70% / 30% with an adjustable ratio.
  - **Production control (کنترل تولید):** assigns remaining order quantity to lines by daily capacity, computes start/finish day and on-time / days-late against each order's due date; orders whose line is missing or has zero capacity are reported as unassigned.
  - **Production status / OEE (وضعیت تولید):** availability × performance × quality from planned/downtime minutes, ideal rate, total and good counts, with a quality-grade label.
- **Offline brain (مغز آفلاین پایه):** executes recognized local commands without internet: save tasks, notes and income/expense entries; calculate arithmetic; and answer basic queries from locally stored task, finance and production-order records. This is a deterministic rule/calculation engine, **not** the generative on-device LLM (see "On-device offline AI" below).
- Unit-test source files cover the arithmetic parser, action policy, Jalali calendar engine, Dynamic Data Engine validation/formula logic, factory scheduler/OEE/rules and offline brain. GitHub Actions is configured to run the unit tests and assemble a debug APK.

## Not implemented — do not treat these as available features

- Multi-user accounts and roles/permissions. Human resources, employee management, payroll and deductions are explicitly out of scope. Factory features are limited to production planning, line/machine status, actual output, downtime, waste, capacity and OEE.
- The offline *brain* is a deterministic calculator/rule engine, not a generative model; real on-device generation is the separate, optional llama.cpp feature described below.
- Google Drive/Calendar/Gmail or Microsoft OAuth/Graph sync. Backup is user-selected local JSON export/import only.
- Advanced factory optimisation (multi-constraint/finite-capacity solver), shift calendars, inventory, quality, maintenance, procurement, and detailed production reports. The included production-control and OEE tools are single-pass capacity math, not a full MES/APS.
- Encrypted Room database.
- Comprehensive Compose UI / instrumentation tests.

No API key or OAuth secret is embedded in source. Online AI is optional and may incur costs depending on the provider.

## Verification limits

The standalone Kotlin smoke tests for Jalali conversion, OEE, generic production scheduling, Taffy capacity rules and Kavandish packaging split passed. XML files parse successfully, and Kotlin source parsing found no syntax errors in the changed Compose files. A full Android/Room/KSP + Compose Gradle build and the complete JUnit suite have **not** been run in this environment because no Gradle installation or Android SDK is available here. The full build must be confirmed by a green GitHub Actions run, then the APK installed and validated on the target Samsung device. A successful GitHub build is not a substitute for device testing.


## Persian hands-free voice assistant (3.2.1)

The new `سام` tab adds a continuous, touch-free Persian voice assistant that runs on top of the
existing push-to-talk chat (both remain available).

### What it does
- **On-device wake word «سام».** A local Vosk recogniser restricted by a tiny keyword grammar listens
  for «سام» on the device; no audio is streamed to any server during wake-word listening. A matcher
  gate plus a cool-down prevent one utterance from firing repeatedly.
- **Continuous hands-free conversation.** After «سام» the assistant listens, recognises, answers with
  Persian TTS, and (optionally) keeps listening for a follow-up — a full multi-turn loop with no
  touching.
- **Real STT, online + offline.** When the network is up and the user allows it, the device
  `SpeechRecognizer` is used for `fa-IR`; otherwise real offline recognition runs through Vosk
  (Kaldi) on-device. The deterministic offline brain still decides command *intent*; the words come
  from the recogniser.
- **Barge-in.** While SAM is speaking, the user can talk over it; voice-activity detection stops TTS
  instantly and starts listening again.
- **Foreground microphone service.** `VoiceForegroundService` keeps the loop alive outside the
  Activity with a persistent, user-controllable notification (mute / stop actions), declaring the
  `microphone` foreground-service type required from Android 14+.
- **Voice UI.** Animated status orb, live phase (منتظر «سام» / در حال شنیدن / پردازش / صحبت), partial
  transcript, user/assistant subtitle cards, an offline-model install card, and a voice settings
  panel (master **enable/disable** switch for the assistant, power mode, wake sensitivity,
  prefer-online, follow-up, speech rate/pitch).

### Offline Persian model (Vosk)
- The offline model is **not bundled**; the small Persian Vosk model (~50 MB, Apache-2.0) is
  downloaded once at runtime from the `سام` tab ("دانلود مدل آفلاین") and cached on the device.
- Wake-word spotting and offline STT both require this model; until it is installed the UI shows the
  install card and online STT (if available) is still usable.

### Permissions & privacy
- Runtime permissions: `RECORD_AUDIO` (required) and, on Android 13+, `POST_NOTIFICATIONS` for the
  service notification. Manifest also declares `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE`
  and a `<queries>` entry for the recognition/TTS services.
- Raw audio is **never** saved to disk unless the user explicitly enables it (`allowRawAudio`,
  default **off**). No API key is hard-coded; online AI keys stay encrypted in Android Keystore.
- Capture uses `AcousticEchoCanceler` / `NoiseSuppressor` / `AutomaticGainControl` when the device
  provides them.

### Device requirements and limitations
- Online `fa-IR` recognition depends on the speech service / Persian language pack installed on the
  phone; Persian TTS depends on a Persian voice in the Android/Samsung TTS engine.
- Reliable background wake detection on Samsung requires exempting SAM from battery optimisation; the
  service only recovers via `START_STICKY` within what Android allows and does **not** claim to defeat
  aggressive OEM battery management.
- The voice loop could **not** be built or run in this environment (no Gradle/Android SDK, no device).
  A green GitHub Actions build plus on-device testing on the target Galaxy S24 Ultra / Android 17 are
  still required to confirm wake-word accuracy, STT/TTS quality and barge-in behaviour.

## On-device offline AI (llama.cpp, v3.3 — optional)

A real generative LLM runs on the device through llama.cpp, wired into the existing `AiRouter`.

- **Enable it in the build:** the native library is off by default so the normal build never breaks. Build with
  `./gradlew assembleDebug -PsamLlama`, or run the GitHub workflow **Build SAM APK (Offline AI / llama.cpp)**. The normal
  workflow and APK are unaffected; without the flag the app falls back to the online / command engines.
- **Pinned runtime:** llama.cpp tag `b4409` (CMake FetchContent). The native code uses the API of that period
  (`llama_kv_cache_clear`, `llama_load_model_from_file`, …); re-check every call marked `[API]` in `llama_jni.cpp` before bumping the tag.
- **Independent requests:** each request is a complete prompt; the KV cache is cleared before and after it, nothing carries over.
  Generation, stop, load and unload are serialised (`LlmSession`); unloading waits for a running generation and never frees a live context.
- **Chat templates:** the prompt is built by an extensible `ChatTemplate` (system, history, new message, stop markers).
  Qwen2.5-Instruct uses ChatML, Gemma 2 has its own template; add a family with `ChatTemplateRegistry.register(...)`.
- **Models and integrity:** models are downloaded (resumable) or imported from a file; the GGUF header, size and, when pinned, the official SHA-256 are checked,
  and the file is re-hashed before every load, so a corrupt, truncated or modified file is rejected.
  **No official SHA-256 is pinned in the catalog yet**, so every catalog model is shown as *unverified* (only size, header and a local fingerprint are checked).
  Run `python3 tools/fetch_model_hashes.py` with internet access and paste the printed hashes to enable "verified".
- **Not verified here:** the Kotlin/Gradle build, unit-test run, NDK build against the real llama.cpp `b4409` and device behaviour were not run in the authoring environment; confirm with CI and on the target phone.

