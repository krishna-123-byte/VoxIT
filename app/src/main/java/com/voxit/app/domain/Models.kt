package com.voxit.app.domain

import android.net.Uri

enum class AnalysisSource { UPLOAD, LIVE_PROTECTION, DEMO }
enum class DetectorMode { REAL, DEMO }
enum class AudioQuality { GOOD, NOISY, INSUFFICIENT, UNKNOWN }
enum class WarningLevel { LOW, UNCERTAIN, HIGH }
enum class EngineState { IDLE, COLLECTING_SPEECH, ANALYSING, INSUFFICIENT_SPEECH, LOW_QUALITY_AUDIO, MODEL_UNAVAILABLE, RESULT_AVAILABLE, ERROR }

data class RiskResult(
    val manipulation: Int = 0,
    val speakerMismatch: Int = 0,
    val scamRisk: Int = 0,
    val confidence: Int = 0,
    val warningLevel: WarningLevel = WarningLevel.LOW,
    val audioQuality: AudioQuality = AudioQuality.UNKNOWN,
    val speechDurationSeconds: Int = 0,
    val evidence: List<EvidenceReason> = emptyList(),
)

data class AnalysisSession(
    val id: String,
    val source: AnalysisSource,
    val mode: DetectorMode,
    val state: EngineState,
    val result: RiskResult? = null,
    val modelVersion: String = "Not installed",
)

data class TranscriptSegment(
    val timestamp: String,
    val text: String,
    val language: String,
    val confirmed: Boolean = false,
    val suspicious: Boolean = false,
    val startMs: Long = 0,
    val endMs: Long = 0,
)

sealed interface TranscriptionOutput {
    data class Available(
        val segments: List<TranscriptSegment>,
        val modelName: String,
        val modelVersion: String,
    ) : TranscriptionOutput
    data class Unavailable(val reason: String) : TranscriptionOutput
    data class Failed(val reason: String) : TranscriptionOutput
}

data class EvidenceReason(val title: String, val detail: String)

sealed interface AudioSource { val label: String }
data class UploadedAudioSource(val uri: Uri, override val label: String) : AudioSource
data object MicrophoneAudioSource : AudioSource { override val label = "Microphone" }
