# VoxIT 1.0.0-prototype

## Purpose and final architecture

VoxIT is a privacy-first Android cybersecurity prototype. It presents warnings—not proof—using two independent evidence paths:

- Acoustic voice integrity: optional, uploaded-recording-only AASIST-L inference through ONNX Runtime.
- Conversation risk: optional offline Vosk transcription followed by explainable scam-language/context rules.

The Compose UI observes ViewModels backed by Flow. Uploaded audio uses `ContentResolver`, Android/WAV decoding, 16 kHz mono preprocessing, VAD, quality/features, optional transcription, optional integrity inference, and bounded result state. Live Protection uses one `AudioRecord`, a non-sticky microphone foreground service, bounded/drop-oldest frames, a short PCM ring buffer, VAD/quality, one optional Vosk recognizer, rolling confirmed-transcript risk, a notification, and an optional overlay bubble.

No component converts missing evidence into a zero score or substitutes Demo Mode data in Real Mode.

## Final workflows

### Uploaded recording

Select with Storage Access Framework → validate and decode locally → audio quality → speech segmentation → optional Vosk transcript → transcript-context warnings → optional AASIST-L analysis → five separate result sections → delete immediately or explicitly retain metadata.

### Live Protection

Tap Start → read contextual permission explanation → grant microphone (and notification where applicable) → foreground microphone service → real waveform, speech/silence and audio quality → optional Vosk partial/final transcript → rolling scam-language warning → notification/bubble guidance → Pause, Resume, or Stop.

Live AASIST-L inference is intentionally not enabled. Android may block or limit protected cellular/VoIP call audio; VoxIT analyses only microphone audio Android supplies and never claims that both participants were captured.

## Model installation

Open **Home → Model Manager**.

### Vosk

1. Obtain a compatible model from the official Vosk model list.
2. Choose English, Hindi, or experimental Hinglish.
3. Import the model ZIP with Android's document picker.
4. Confirm the detected model name, language, stable ID, validation state, and private path identifier.
5. Select the exact model. Stop Live Protection before changing it.

Known small models include `vosk-model-small-en-us-0.15` and `vosk-model-small-hi-0.22`. Vosk runtime code is Apache-2.0; users must also verify the terms shown by the official model source for the imported model. Missing, deleted, corrupt, or mismatched selections are explicit and never fall back.

### AASIST-L

1. Follow `ml/README.md` to obtain the official NAVER Clova MIT-licensed source/checkpoint and reproducibly export the fixed-shape ONNX artifact.
2. Import that `.onnx` file in Model Manager.
3. VoxIT enforces the expected SHA-256, size, input `audio [1,64600]`, and output `logits [1,2]` before activation.

The model is not bundled or committed. AASIST-L was trained on ASVspoof 2019 Logical Access; class-0 softmax is shown as an **uncalibrated experimental synthetic/spoof score**, not a probability that a real-world voice is AI-generated or a call is fraudulent.

## Result semantics

The real result contains:

1. Audio quality and real waveform/speech regions.
2. Timestamped local transcript when Vosk is available.
3. Experimental scam-language/context warnings from confirmed transcript segments.
4. AASIST-L voice-integrity state: likely authentic, possible synthetic/manipulated speech, inconclusive, unavailable, or failed.
5. Cautious guidance with the independent reasons that contributed.

Poor or insufficient audio yields an inconclusive/unavailable acoustic result. Speaker verification is not included and never contributes a value. No screen claims confirmed fraud, confirmed cloning, identity, or a manipulation subtype.

## Privacy and retention

- No raw live PCM is saved.
- No audio, transcript, embedding, feature, or result is uploaded.
- No internet permission or analytics SDK is present.
- The original selected recording is never copied into persistent app storage.
- Live transcript, waveform, buffers, and risk state are cleared on Stop.
- Uploaded transcript/result state is in memory until deleted or replaced.
- History is opt-in and stores only time, source type, duration, quality, detector conclusions/scores, and model names. It excludes filename, URI, audio, PCM, and transcript.
- Imported models live in separate app-private directories and can be deleted with confirmation.

## Permissions

- `RECORD_AUDIO`: requested only after the user starts Live Protection.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE`: visible microphone foreground service.
- `POST_NOTIFICATIONS`: contextual Android 13+ request.
- `VIBRATE`: only for enabled, gated alerts.
- `SYSTEM_ALERT_WINDOW`: separately requested optional bubble.

VoxIT requests no broad storage, internet, contacts, call-log, SMS, phone-state, accessibility, protected-output-capture, or default-dialer permission.

## Build and run

Requirements: Android Studio with its bundled JDK, Android SDK 36, and an API 26+ emulator/device.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`; it is intentionally ignored by Git.

## Demo presentation

Open **Home → Demo Mode**. All six deterministic scenarios work without imported models. Every demo surface says **SIMULATED DEMO — NOT A REAL DETECTION**. `End Demo Call` ends only the in-app simulation. Demo results do not enter real history.

## Manual testing checklist

- Cold launch; complete Splash → Onboarding → Home and open every Home destination.
- Cancel the upload picker; test supported, corrupt, short, silent, clipped, and long files.
- Test upload processing with no models, each Vosk model, and the verified AASIST-L artifact.
- Switch English → Hindi and Hindi → English while stopped; confirm actual loaded IDs.
- Start, pause, resume, and stop Live Protection; repeat start/stop at least five times.
- With overlay permission: show, drag, open, hide, stop, and recreate the bubble; confirm exactly one bubble/service.
- Deny microphone, notification, and overlay permissions independently.
- Retain then individually delete a result; clear all history; delete each imported model.
- Run all demo scenarios and confirm the simulation banner.
- Inspect Logcat for fatal exceptions and audio/transcript leakage.

## Provenance and limitations

Vosk provenance is documented in `README.md`. AASIST-L candidate comparison, official repository/paper/checkpoint provenance, licence, preprocessing, parity, and measured Phase 4 emulator timings are in `docs/phase4-model-evaluation.md` and `ml/model-card.md`.

Published research metrics are not VoxIT accuracy measurements. VoxIT has not completed representative multilingual, accent, microphone, codec, telephone/VoIP, replay, noise, or unseen-generator evaluation, nor validation-only calibration. The emulator cannot establish physical-device microphone routing, thermal, battery, memory, or real-time suitability. Real call audio access remains Android- and device-dependent. The appropriate next step is rigorous dataset and physical-device validation, not adding another unvalidated score.
