package com.voxit.app.phase2

import android.net.Uri
import com.voxit.app.domain.TranscriptSegment
import com.voxit.app.integrity.VoiceIntegrityResult

object AudioLimits {
    const val MAX_FILE_BYTES = 100L * 1024L * 1024L
    const val MAX_DURATION_MS = 10L * 60L * 1000L
    const val MIN_DURATION_MS = 500L
    const val TARGET_SAMPLE_RATE = 16_000
    const val MIN_USABLE_SPEECH_MS = 1_500L
    const val MAX_DECODED_MONO_SAMPLES = 30_000_000L
    const val MAX_MODEL_ARCHIVE_BYTES = 250L * 1024L * 1024L
    const val MAX_MODEL_EXTRACTED_BYTES = 350L * 1024L * 1024L
}

data class SelectedAudio(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class AudioMetadata(
    val fileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val channelCount: Int,
)

data class DecodedAudio(val monoSamples: FloatArray, val metadata: AudioMetadata)

data class SpeechRegion(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

data class WaveformPoint(val timeMs: Long, val amplitude: Float, val isSpeech: Boolean)

enum class RealAudioQuality(val label: String, val explanation: String) {
    GOOD("Good", "Clear level with usable speech and little clipping."),
    ACCEPTABLE("Acceptable", "Usable audio with some quality limitations."),
    NOISY("Noisy", "Background energy may reduce transcription accuracy."),
    CLIPPED("Clipped", "Frequent peak clipping may have removed speech detail."),
    TOO_QUIET("Too quiet", "The recording level is too low for reliable analysis."),
    INSUFFICIENT_SPEECH("Insufficient speech", "Not enough valid speech was detected."),
    UNSUPPORTED("Unsupported", "The device could not decode this audio format."),
}

data class AudioQualityMetrics(
    val rmsEnergy: Float,
    val peakAmplitude: Float,
    val clippingPercent: Float,
    val silencePercent: Float,
    val usableSpeechMs: Long,
    val approximateNoiseRms: Float,
    val quality: RealAudioQuality,
)

data class SignalFeatures(
    val zeroCrossingRate: Float,
    val spectralCentroidHz: Float,
    val spectralRolloffHz: Float,
    val spectralFlatness: Float,
    val rmsEnergy: Float,
    val lowBandPercent: Float,
    val midBandPercent: Float,
    val highBandPercent: Float,
    val pitchHz: Float?,
    val pitchStability: Float?,
)

data class TranscriptWarning(
    val category: String,
    val timestamp: String,
    val evidence: String,
    val explanation: String,
    val confidence: Int,
    val detectorMode: String = "Real — experimental transcript rules",
)

data class ConversationRiskResult(
    val score: Int?,
    val warnings: List<TranscriptWarning>,
    val explanation: String,
)

data class RealAnalysisResult(
    val metadata: AudioMetadata,
    val waveform: List<WaveformPoint>,
    val speechRegions: List<SpeechRegion>,
    val quality: AudioQualityMetrics,
    val features: SignalFeatures,
    val transcript: List<TranscriptSegment>,
    val conversationRisk: ConversationRiskResult,
    val transcriptionModel: String?,
    val transcriptionVersion: String?,
    val transcriptionMessage: String,
    val transcriptSaved: Boolean = false,
    val manipulationScore: Int? = null,
    val speakerMismatchScore: Int? = null,
    val voiceIntegrity: VoiceIntegrityResult = VoiceIntegrityResult.Unavailable("Voice-integrity model not installed"),
)

enum class PipelineStage(val label: String, val progress: Float) {
    OPENING("Opening file", .04f), VALIDATING("Validating", .10f), DECODING("Decoding", .20f),
    PREPARING("Preparing samples", .34f), DETECTING_SPEECH("Detecting speech", .48f),
    EXTRACTING("Extracting audio information", .58f), VOICE_INTEGRITY("Analysing acoustic voice integrity", .68f), LOADING_MODEL("Loading transcription model", .74f),
    TRANSCRIBING("Transcribing", .82f), ANALYSING_TRANSCRIPT("Analysing transcript", .91f),
    PREPARING_RESULT("Preparing result", .97f), COMPLETE("Complete", 1f)
}

sealed interface Phase2UiState {
    data object Idle : Phase2UiState
    data class FileSelected(val file: SelectedAudio) : Phase2UiState
    data class Working(val stage: PipelineStage) : Phase2UiState
    data class ModelRequired(val result: RealAnalysisResult) : Phase2UiState
    data class Complete(val result: RealAnalysisResult) : Phase2UiState
    data object Cancelled : Phase2UiState
    data class Error(val message: String) : Phase2UiState
}

data class InstalledModel(
    val id: String,
    val directory: String,
    val displayName: String,
    val language: String,
    val languageCode: String,
    val version: String,
    val originalName: String,
    val importedAtEpochMs: Long,
    val validationStatus: ModelValidationStatus,
    val pathIdentifier: String,
)

enum class ModelValidationStatus { VALID, MISSING, INVALID }

data class ModelCatalog(
    val models: List<InstalledModel> = emptyList(),
    val selectedModelId: String? = null,
    val selectedModel: InstalledModel? = null,
) {
    val readySelectedModel: InstalledModel?
        get() = selectedModel?.takeIf { it.validationStatus == ModelValidationStatus.VALID }
}

sealed interface ModelImportState {
    data object Idle : ModelImportState
    data class Importing(val progress: Float) : ModelImportState
    data class Ready(val model: InstalledModel) : ModelImportState
    data class Error(val message: String) : ModelImportState
}

class AudioPipelineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Prevents results from a cancelled or superseded file session from reaching UI state. */
class SessionGeneration {
    private var generation = 0L
    @Synchronized fun next(): Long = ++generation
    @Synchronized fun invalidate() { generation++ }
    @Synchronized fun isCurrent(token: Long): Boolean = token == generation
}
