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

## Run

Open the project in Android Studio, sync Gradle, select an emulator/device running Android 8.0 (API 26) or newer, and run the `app` configuration. From a terminal:

```bash
./gradlew :app:assembleDebug
```

## Privacy and Android limitations

The app requests no microphone, overlay, call-log, or broad storage permission in Phase 1. Audio selection uses Android's Storage Access Framework. Live Protection does not begin microphone capture automatically and no real detection result, score, or transcript is generated when a real model is unavailable.

A standard Android app cannot directly access protected cellular-call audio. A future microphone-based experience will work best with speakerphone enabled and must be started explicitly by the user.

## Planned phases

Phase 2 will integrate vetted on-device transcription and recording ingestion. Phase 3 will implement explicitly started microphone live processing, speech activity detection, and permission flows. A later phase can integrate validated voice-integrity, speaker-verification, and conversation-risk models, with user-approved data controls.
