package com.voxit.app.domain

import com.voxit.app.integrity.VoiceIntegrityResult
import com.voxit.app.phase2.AudioQualityMetrics
import com.voxit.app.phase2.SpeechRegion

/** Contracts for later on-device implementations. Phase 1 real engines never invent output. */
interface AudioPreprocessor
interface SpeechActivityDetector
interface AudioFeatureExtractor
interface VoiceIntegrityEngine {
    suspend fun analyse(samples: FloatArray, sampleRate: Int, speechRegions: List<SpeechRegion>, quality: AudioQualityMetrics): VoiceIntegrityResult
}
interface TranscriptionEngine {
    suspend fun transcribe(samples: FloatArray, sampleRate: Int, language: String): TranscriptionOutput
}
interface SpeakerVerificationEngine
interface ConversationRiskEngine
interface RiskFusionEngine
interface AlertManager
interface CallControlProvider { fun openPhoneControls() }

class RealVoiceIntegrityEngine : VoiceIntegrityEngine {
    override suspend fun analyse(samples: FloatArray, sampleRate: Int, speechRegions: List<SpeechRegion>, quality: AudioQualityMetrics) =
        VoiceIntegrityResult.Unavailable("Voice-integrity model not installed")
}

class DemoVoiceIntegrityEngine : VoiceIntegrityEngine {
    override suspend fun analyse(samples: FloatArray, sampleRate: Int, speechRegions: List<SpeechRegion>, quality: AudioQualityMetrics) =
        VoiceIntegrityResult.Unavailable("Demo Mode uses only its clearly labelled deterministic scenarios.")
}

class DemoTranscriptionEngine : TranscriptionEngine {
    override suspend fun transcribe(samples: FloatArray, sampleRate: Int, language: String) =
        TranscriptionOutput.Unavailable("Select a Demo Mode scenario for simulated transcription.")
}
