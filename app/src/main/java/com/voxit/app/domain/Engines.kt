package com.voxit.app.domain

/** Contracts for later on-device implementations. Phase 1 real engines never invent output. */
interface AudioPreprocessor
interface SpeechActivityDetector
interface AudioFeatureExtractor
interface VoiceIntegrityEngine { suspend fun analyse(source: AudioSource): AnalysisSession }
interface TranscriptionEngine {
    suspend fun transcribe(samples: FloatArray, sampleRate: Int, language: String): TranscriptionOutput
}
interface SpeakerVerificationEngine
interface ConversationRiskEngine
interface RiskFusionEngine
interface AlertManager
interface CallControlProvider { fun openPhoneControls() }

class RealVoiceIntegrityEngine : VoiceIntegrityEngine {
    override suspend fun analyse(source: AudioSource) = AnalysisSession(
        id = "unavailable", source = if (source is UploadedAudioSource) AnalysisSource.UPLOAD else AnalysisSource.LIVE_PROTECTION,
        mode = DetectorMode.REAL, state = EngineState.MODEL_UNAVAILABLE
    )
}

class DemoVoiceIntegrityEngine : VoiceIntegrityEngine {
    override suspend fun analyse(source: AudioSource) = AnalysisSession(
        id = "demo", source = AnalysisSource.DEMO, mode = DetectorMode.DEMO,
        state = EngineState.RESULT_AVAILABLE, modelVersion = "Demo timeline v1"
    )
}

class DemoTranscriptionEngine : TranscriptionEngine {
    override suspend fun transcribe(samples: FloatArray, sampleRate: Int, language: String) =
        TranscriptionOutput.Unavailable("Select a Demo Mode scenario for simulated transcription.")
}
