# VoxIT

VoxIT is an Android prototype for privacy-conscious voice-integrity and scam-warning experiences. It is designed to eventually analyse recordings and explicitly enabled microphone audio for possible synthetic speech, voice cloning, speaker mismatch, and scam language.

## Phase 1

This release builds the application foundation in Kotlin, Jetpack Compose, Material 3, MVVM, and Navigation Compose. It includes a branded splash and onboarding flow, Home dashboard, secure Storage Access Framework audio selection, Live Protection interface, results, transcripts, history, Privacy Centre, and settings.

Phase 1 includes a complete deterministic Demo Mode with six repeatable scenarios:

- Trusted human voice
- Noisy or insufficient audio
- Suspected AI-generated voice
- Suspected voice clone
- Suspicious scam conversation
- High combined risk

Every demo result is visibly labelled **DEMO MODE – SIMULATED RESULT**. The timeline data is predefined, can rise and fall, and does not analyse uploaded audio or microphone input.

## Phase 2: uploaded audio

The Upload Recording flow now performs real, local processing of user-selected content URIs:

1. Validate the selected file without converting its `content://` URI into a filesystem path.
2. Decode WAV directly or use Android `MediaExtractor` and `MediaCodec` for device-supported MP3, M4A, AAC, and OGG tracks.
3. Mix channels to mono, remove DC offset, resample to 16 kHz, and safely normalize non-silent audio.
4. Detect and merge usable speech regions, calculate audio quality, create an actual downsampled waveform, and extract experimental signal information.
5. Optionally transcribe with an imported offline Vosk model and run explainable, transcript-based scam rules.

Real Mode never derives an AI voice-manipulation percentage from signal features. Voice manipulation remains **Unavailable — AI voice model not installed**, and speaker mismatch remains **Not configured**. Scam risk is available only when an actual offline transcript was produced.

### Processing limits

- Maximum audio file size: 100 MB
- Maximum recording duration: 10 minutes
- Minimum recording duration: 0.5 seconds
- Minimum usable speech for transcription: 1.5 seconds
- Maximum imported model ZIP: 250 MB
- Maximum extracted model: 350 MB
- Decoded mono safety cap: 30 million samples

High-sample-rate recordings may be rejected below the duration limit when the estimated temporary PCM buffers would exceed 65% of the device app heap. This check happens before large buffers are allocated.

Cancellation invalidates the active session and releases streams, descriptors, extractors, codecs, and staging files. Selecting a new file prevents an older job from publishing results.

### Offline transcription model

VoxIT uses [Vosk Android](https://github.com/alphacep/vosk-api) `0.3.75`, an Apache-2.0 offline speech-recognition runtime distributed through Maven Central. It supports Android API 21+, including ARM64. Vosk word results provide timestamps used to build confirmed transcript segments.

No model binary is bundled or committed. Import a model ZIP through **Upload Recording → Import Model ZIP**; VoxIT validates the archive, blocks path traversal and oversized extraction, and stores the model under app-private storage. Settings and Privacy Centre can delete it.

Suggested Apache-2.0 mobile models from the [official Vosk model list](https://alphacephei.com/vosk/models):

- `vosk-model-small-en-us-0.15`: English, approximately 40 MB
- `vosk-model-small-hi-0.22`: Hindi, approximately 42 MB

Small models typically require approximately 300 MB of runtime memory. English and Hindi are supported by their respective models. Hinglish is exposed as an experimental option: Vosk loads one language model at a time, so mixed-language accuracy depends on the vocabulary and training data of the imported model and is not guaranteed.

Transcripts remain in memory and are not saved by default. Likely OTPs, PINs, CVVs, passwords, card/account numbers, and phone numbers are redacted before transcript display and scam evidence. Complete transcript content is never written to Logcat.

## Phase 3: Live Protection

Live Protection is an explicitly user-started ambient-microphone feature. After an in-app explanation, VoxIT requests microphone permission and starts `LiveProtectionService` as a non-sticky microphone foreground service. A persistent notification provides Open, Pause/Resume, and Stop actions. Nothing starts at install time, app launch, reboot, or after a forced stop.

One `AudioRecord` instance captures mono PCM16 at 16 kHz when available, with 48 kHz and 44.1 kHz fallbacks resampled to 16 kHz. Frames enter an eight-item drop-oldest channel, then fan out to a two-second PCM ring buffer, a throttled 120-point waveform, speech detection, audio-quality measurements, the already installed Vosk recognizer, and rolling transcript-risk rules. Captured PCM is never written to disk. Stopping clears PCM, partial/final transcript, waveform, and risk state.

Rolling scam risk uses only confirmed, redacted Vosk segments from the most recent 60 seconds. Old evidence expires, so risk can fall; time alone never raises it. Alerts require at least 1.5 seconds of usable speech, adequate quality, a configured score threshold, confirmed supporting evidence, and a 30-second cooldown. Manipulation remains unavailable and speaker verification remains unconfigured.

The optional VoxIT overlay uses `TYPE_APPLICATION_OVERLAY`, requires a separate explicit Android settings grant, and is never required for monitoring. It is draggable and exposes Open, Stop, and Hide actions. The foreground notification remains the fallback when overlay access is denied or revoked.

### Phase 3 permissions

- `RECORD_AUDIO`: requested only after Start Live Protection and an explanation
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE`: required for the visible microphone service
- `POST_NOTIFICATIONS`: requested contextually on Android 13+; denial does not create fake results
- `VIBRATE`: used only when vibration is enabled and an alert passes gating
- `SYSTEM_ALERT_WINDOW`: used only after the user separately enables the optional bubble

VoxIT does not request contacts, phone state, call logs, SMS, AccessibilityService access, `CAPTURE_AUDIO_OUTPUT`, broad storage access, or default-dialer privileges.

### Live audio limitation

A standard application cannot reliably capture protected cellular or VoIP call audio. During such calls Android may provide silence, limited ambient microphone input, or no usable input. VoxIT reports **Call audio unavailable or blocked by Android** after repeated zero buffers and does not treat absent audio or transcription as low risk. Ambient speech and audio played from another device are the supported inputs; speakerphone-call behavior is device-dependent and experimental, and VoxIT never claims both call participants were captured.

## Run

Open the project in Android Studio, sync Gradle, select an emulator/device running Android 8.0 (API 26) or newer, and run the `app` configuration. From a terminal:

```bash
./gradlew :app:assembleDebug
```

## Privacy and Android limitations

The app declares microphone and foreground-service permissions for explicitly started Live Protection, notification permission for its contextual foreground status, and overlay permission for the separately enabled optional bubble. It does not request these permissions at startup. Audio and model selection use Android's Storage Access Framework. The app requests no call-log, contacts, SMS, phone-state, accessibility, protected-audio-capture, or broad storage permission. No real transcript or transcript-derived score is generated when a real model is unavailable.

A standard Android app cannot directly access protected cellular-call audio. Live Protection analyses only the microphone input Android actually supplies and must be started explicitly by the user.

## Planned phases

Phase 4 should focus on validated voice-integrity research and optional speaker enrollment/verification, including representative evaluation data, calibration, model provenance, battery/thermal profiling, and physical-device call-routing tests. No manipulation or speaker-mismatch score should be exposed until those detectors are scientifically validated.
