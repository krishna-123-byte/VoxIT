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

## Run

Open the project in Android Studio, sync Gradle, select an emulator/device running Android 8.0 (API 26) or newer, and run the `app` configuration. From a terminal:

```bash
./gradlew :app:assembleDebug
```

## Privacy and Android limitations

The app requests no microphone, overlay, call-log, contacts, SMS, phone-state, accessibility, or broad storage permission. Audio and model selection use Android's Storage Access Framework. Live Protection does not begin microphone capture. No real transcript or transcript-derived score is generated when a real model is unavailable.

A standard Android app cannot directly access protected cellular-call audio. A future microphone-based experience will work best with speakerphone enabled and must be started explicitly by the user.

## Planned phases

Phase 3 should implement explicitly started microphone capture, a foreground processing service, real-time buffering, and live VAD/transcription permission flows. A later phase can integrate validated voice-integrity and speaker-verification models. Those future detectors must be calibrated with representative evaluation data before they expose risk scores.
