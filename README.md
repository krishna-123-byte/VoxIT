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

Every demo result is visibly labelled **SIMULATED DEMO — NOT A REAL DETECTION**. The timeline data is predefined, can rise and fall, and does not analyse uploaded audio or microphone input.

## Phase 2: uploaded audio

The Upload Recording flow now performs real, local processing of user-selected content URIs:

1. Validate the selected file without converting its `content://` URI into a filesystem path.
2. Decode WAV directly or use Android `MediaExtractor` and `MediaCodec` for device-supported MP3, M4A, AAC, and OGG tracks.
3. Mix channels to mono, remove DC offset, resample to 16 kHz, and safely normalize non-silent audio.
4. Detect and merge usable speech regions, calculate audio quality, create an actual downsampled waveform, and extract experimental signal information.
5. Optionally transcribe with an imported offline Vosk model and run explainable, transcript-based scam rules.

Real Mode never derives an AI voice-manipulation percentage from signal features. Voice manipulation remains unavailable unless the separately validated Phase 4 acoustic model completes inference, and speaker mismatch remains **Not configured**. Scam risk is available only when an actual offline transcript was produced.

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

No model binary is bundled or committed. Import a model ZIP through **Upload Recording → Import Model ZIP**; VoxIT validates the archive, blocks path traversal and oversized extraction, and stores each model in a separate app-private directory. Each model has a stable ID, detected language, original archive name, import time, validation state, and non-sensitive private path identifier. Settings, Upload Recording, and Privacy Centre show the exact selected model.

The selected model ID is persisted and shared by uploaded-audio and Live Protection transcription. A new live session receives that exact ID, validates its directory, and creates a new Vosk model and recognizer. Stop closes both objects and clears partial/final transcript and transcript-risk state. Switching is blocked while Live Protection is active; stop it before selecting another model. A deleted, corrupt, or missing selection reports **Selected model unavailable** and never falls back to a different model or Demo Mode. Legacy single-directory installations are migrated without trusting a conflicting language label; known archive names and the model README are used to recover actual language identity.

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

## Phase 4: uploaded voice integrity

Uploaded recordings can optionally use the genuine AASIST-L acoustic anti-spoofing network from NAVER Clova's official MIT-licensed repository. VoxIT uses the official 85,306-parameter ASVspoof 2019 Logical Access checkpoint, reproducibly exports it to fixed-shape ONNX, and runs it locally with Microsoft's official ONNX Runtime Android package.

The model is not bundled. Follow [ml/README.md](ml/README.md) to export it, then choose **Upload Recording → Import verified AASIST-L ONNX model**. Import uses Android's Storage Access Framework, limits the file to 10 MB, checks the exact SHA-256, validates `audio [1,64600]` and `logits [1,2]`, and stores it in app-private storage. A corrupt or different model is rejected rather than used or silently replaced.

Analysis uses only detected speech, 64,600-sample windows with 32,300-sample overlap, official repeat-padding for shorter valid regions, at most 32 windows, and median aggregation. Class 0 softmax is displayed as an **uncalibrated experimental synthetic/spoof score**. Quality problems yield unavailable or inconclusive output rather than mechanically increasing risk. The result shows window count, agreement, confidence, runtime, model identity, and limitations separately from transcript scam risk.

AASIST-L is a binary bona-fide/spoof research model trained on ASVspoof 2019 LA. It may detect artifacts from the synthetic-speech and voice-conversion systems represented there, but it cannot reliably distinguish TTS, voice conversion, or cloning subtypes; it is not a validated replay detector; and it does not verify identity or prove fraud. VoxIT has not run an accuracy dataset evaluation or calibration. See [the Phase 4 research report](docs/phase4-model-evaluation.md) and [model card](ml/model-card.md).

## Phase 5: final prototype integration

Version `1.0.0-prototype` brings the independent Phase 1–4 capabilities into one cautious workflow. Home reports English, Hindi, selected transcription, AASIST-L, Live Protection, and local-processing status without claiming unavailable features are ready. Model Manager is the single place to import, select, replace, inspect, and delete Vosk and voice-integrity models. Unsafe transcription-model changes remain blocked while Live Protection is active, and deletion never silently selects a different model.

Real uploaded results now keep five sections visibly separate: audio quality, transcript, scam-language/context risk, acoustic voice integrity, and overall safety guidance. The guidance explains its contributing signals; it is not a combined fraud probability. Speaker verification is explicitly not included. AASIST-L remains upload-only and its output remains an experimental, uncalibrated research score.

History is opt-in and metadata-only. Retaining a result stores its time, duration, quality, detector conclusions/scores, and model names in app-private preferences. It never stores the source filename, URI, original recording, PCM, or transcript. Individual and complete history deletion require confirmation. Demo results never enter real history.

See [Final Prototype Guide](docs/FINAL_PROTOTYPE.md) for architecture, setup, workflows, permissions, manual verification, and honest limitations.

## Run

Open the project in Android Studio, sync Gradle, select an emulator/device running Android 8.0 (API 26) or newer, and run the `app` configuration. From a terminal:

```bash
./gradlew :app:assembleDebug
```

## Privacy and Android limitations

The app declares microphone and foreground-service permissions for explicitly started Live Protection, notification permission for its contextual foreground status, and overlay permission for the separately enabled optional bubble. It does not request these permissions at startup. Audio and model selection use Android's Storage Access Framework. The app requests no call-log, contacts, SMS, phone-state, accessibility, protected-audio-capture, or broad storage permission. No real transcript or transcript-derived score is generated when a real model is unavailable.

A standard Android app cannot directly access protected cellular-call audio. Live Protection analyses only the microphone input Android actually supplies and must be started explicitly by the user.

## Future work

The next work should be validation rather than another feature phase: representative multilingual and unseen-generator evaluation, validation-only calibration, and physical-device latency, memory, battery, thermal, microphone-routing, and repeated overlay/service lifecycle testing. Consent-based speaker verification remains separately scoped and must not be treated as proof of fraud.
