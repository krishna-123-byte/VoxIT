package com.voxit.app.integrity

import com.voxit.app.phase2.RealAudioQuality
import com.voxit.app.phase2.SpeechRegion

enum class IntegrityModelStatus { NOT_INSTALLED, IMPORTING, VALIDATING, READY, INCOMPATIBLE, CORRUPT, LOADING, ERROR }

data class IntegrityModelMetadata(
    val id: String,
    val name: String,
    val version: String,
    val architecture: String,
    val source: String,
    val licence: String,
    val sha256: String,
    val fileName: String,
    val sizeBytes: Long,
    val importedAtEpochMs: Long,
    val pathIdentifier: String,
    val sampleRate: Int = 16_000,
    val inputSamples: Int = 64_600,
    val inputName: String = "audio",
    val outputName: String = "logits",
)

data class IntegrityModelState(
    val status: IntegrityModelStatus = IntegrityModelStatus.NOT_INSTALLED,
    val metadata: IntegrityModelMetadata? = null,
    val message: String = "Voice-integrity model not installed",
)

data class IntegritySegmentResult(
    val startMs: Long,
    val endMs: Long,
    val syntheticProbability: Float,
)

enum class IntegrityConclusion(val label: String) {
    LIKELY_AUTHENTIC("Likely authentic"),
    POSSIBLE_MANIPULATION("Possible synthetic or manipulated speech"),
    INCONCLUSIVE("Inconclusive"),
    UNAVAILABLE("Unavailable"),
    FAILED("Analysis failed"),
}

sealed interface VoiceIntegrityResult {
    data class Available(
        val score: Int,
        val conclusion: IntegrityConclusion,
        val confidence: Int,
        val threshold: Float,
        val analysedSpeechMs: Long,
        val validWindows: Int,
        val agreement: Float,
        val segments: List<IntegritySegmentResult>,
        val model: IntegrityModelMetadata,
        val initializationMs: Long,
        val meanInferenceMs: Long,
        val calibration: String = "Uncalibrated experimental score",
        val limitations: String = "Trained on ASVspoof 2019 Logical Access. It may not generalise to new generators, languages, codecs, microphones, noise, telephone channels, or replay attacks.",
    ) : VoiceIntegrityResult
    data class Unavailable(val reason: String) : VoiceIntegrityResult
    data class Failed(val reason: String) : VoiceIntegrityResult
}

data class IntegrityWindow(val samples: FloatArray, val startMs: Long, val endMs: Long)

object IntegrityWindowBuilder {
    const val WINDOW_SAMPLES = 64_600
    const val HOP_SAMPLES = 32_300
    const val MIN_REGION_SAMPLES = 16_000
    const val MAX_WINDOWS = 32

    fun build(samples: FloatArray, sampleRate: Int, regions: List<SpeechRegion>): List<IntegrityWindow> {
        require(sampleRate == 16_000) { "Voice-integrity input must be 16 kHz" }
        val result = ArrayList<IntegrityWindow>()
        for (region in regions) {
            val regionStart = (region.startMs * sampleRate / 1000).toInt().coerceIn(0, samples.size)
            val regionEnd = (region.endMs * sampleRate / 1000).toInt().coerceIn(regionStart, samples.size)
            val length = regionEnd - regionStart
            if (length < MIN_REGION_SAMPLES) continue
            if (length < WINDOW_SAMPLES) {
                val window = FloatArray(WINDOW_SAMPLES) { samples[regionStart + (it % length)] }
                result += IntegrityWindow(window, region.startMs, region.endMs)
            } else {
                var start = regionStart
                while (start + WINDOW_SAMPLES <= regionEnd && result.size < MAX_WINDOWS) {
                    result += IntegrityWindow(samples.copyOfRange(start, start + WINDOW_SAMPLES), start * 1000L / sampleRate, (start + WINDOW_SAMPLES) * 1000L / sampleRate)
                    start += HOP_SAMPLES
                }
                if (start < regionEnd && result.size < MAX_WINDOWS) {
                    val tailStart = regionEnd - WINDOW_SAMPLES
                    if (result.lastOrNull()?.startMs != tailStart * 1000L / sampleRate) {
                        result += IntegrityWindow(samples.copyOfRange(tailStart, regionEnd), tailStart * 1000L / sampleRate, regionEnd * 1000L / sampleRate)
                    }
                }
            }
            if (result.size >= MAX_WINDOWS) break
        }
        return result
    }
}

object IntegrityAggregator {
    const val CLASSIFICATION_THRESHOLD = .5f
    fun aggregate(probabilities: List<Float>, quality: RealAudioQuality): Triple<Int, IntegrityConclusion, Pair<Int, Float>> {
        require(probabilities.isNotEmpty() && probabilities.all { it.isFinite() && it in 0f..1f })
        val sorted = probabilities.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        val mean = probabilities.average().toFloat()
        val variance = probabilities.sumOf { (it - mean).toDouble() * (it - mean) } / probabilities.size
        val agreement = (1f - kotlin.math.sqrt(variance).toFloat() * 2f).coerceIn(0f, 1f)
        val conclusion = when {
            quality in setOf(RealAudioQuality.CLIPPED, RealAudioQuality.TOO_QUIET, RealAudioQuality.NOISY, RealAudioQuality.INSUFFICIENT_SPEECH) -> IntegrityConclusion.INCONCLUSIVE
            median < .35f -> IntegrityConclusion.LIKELY_AUTHENTIC
            median >= .65f -> IntegrityConclusion.POSSIBLE_MANIPULATION
            else -> IntegrityConclusion.INCONCLUSIVE
        }
        val amount = (probabilities.size / 4f).coerceAtMost(1f)
        val confidence = (100 * agreement * amount * if (quality == RealAudioQuality.GOOD) 1f else .7f).toInt().coerceIn(0, 100)
        return Triple((median * 100).toInt().coerceIn(0, 100), conclusion, confidence to agreement)
    }
}
