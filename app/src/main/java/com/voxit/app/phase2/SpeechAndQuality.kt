package com.voxit.app.phase2

import kotlin.math.abs

data class SpeechDetectionConfig(
    val frameMs: Int = 20,
    val minimumSpeechMs: Int = 220,
    val mergeGapMs: Int = 280,
    val absoluteEnergyFloor: Float = .012f,
)

class EnergySpeechActivityDetector(private val config: SpeechDetectionConfig = SpeechDetectionConfig()) {
    fun detect(samples: FloatArray, sampleRate: Int): List<SpeechRegion> {
        if (samples.isEmpty() || sampleRate <= 0) return emptyList()
        val frameSize = (sampleRate * config.frameMs / 1000).coerceAtLeast(1)
        val frames = PcmProcessor.frame(samples, frameSize, frameSize)
        if (frames.isEmpty()) return emptyList()
        val energies = frames.map(PcmProcessor::rms)
        val sorted = energies.sorted()
        val noiseFloor = sorted[(sorted.lastIndex * .2f).toInt()]
        val p90 = sorted[(sorted.lastIndex * .9f).toInt()]
        if (p90 < config.absoluteEnergyFloor || (noiseFloor > .02f && p90 / noiseFloor.coerceAtLeast(.0001f) < 1.55f)) return emptyList()
        val threshold = maxOf(config.absoluteEnergyFloor, noiseFloor * 2.4f)
        val active = energies.mapIndexed { index, energy ->
            val zcr = zeroCrossingRate(frames[index])
            energy >= threshold && zcr in .008f..0.48f
        }
        val raw = mutableListOf<SpeechRegion>()
        var start = -1
        active.forEachIndexed { index, speech ->
            if (speech && start < 0) start = index
            if ((!speech || index == active.lastIndex) && start >= 0) {
                val endFrame = if (speech && index == active.lastIndex) index + 1 else index
                val region = SpeechRegion(start * config.frameMs.toLong(), endFrame * config.frameMs.toLong())
                if (region.durationMs >= config.minimumSpeechMs) raw += region
                start = -1
            }
        }
        if (raw.isEmpty()) return emptyList()
        val merged = mutableListOf(raw.first())
        raw.drop(1).forEach { next ->
            val previous = merged.last()
            if (next.startMs - previous.endMs <= config.mergeGapMs) merged[merged.lastIndex] = SpeechRegion(previous.startMs, next.endMs)
            else merged += next
        }
        return merged
    }

    private fun zeroCrossingRate(frame: FloatArray): Float {
        if (frame.size < 2) return 0f
        var crossings = 0
        for (i in 1 until frame.size) if ((frame[i - 1] >= 0) != (frame[i] >= 0)) crossings++
        return crossings.toFloat() / (frame.size - 1)
    }
}

object AudioQualityAnalyzer {
    fun analyse(samples: FloatArray, sampleRate: Int, speech: List<SpeechRegion>): AudioQualityMetrics {
        if (samples.isEmpty()) return AudioQualityMetrics(0f, 0f, 0f, 100f, 0, 0f, RealAudioQuality.UNSUPPORTED)
        val rms = PcmProcessor.rms(samples)
        val peak = samples.maxOf { abs(it) }
        val clipped = samples.count { abs(it) >= .985f } * 100f / samples.size
        val usable = speech.sumOf { it.durationMs }
        val durationMs = samples.size * 1000L / sampleRate
        val silence = if (durationMs == 0L) 100f else ((durationMs - usable).coerceAtLeast(0) * 100f / durationMs)
        val noiseValues = mutableListOf<Float>()
        val frameSize = (sampleRate / 50).coerceAtLeast(1)
        PcmProcessor.frame(samples, frameSize, frameSize).forEachIndexed { index, frame ->
            val time = index * 20L
            if (speech.none { time in it.startMs..it.endMs }) noiseValues += PcmProcessor.rms(frame)
        }
        val noise = if (noiseValues.isEmpty()) 0f else noiseValues.sorted()[noiseValues.size / 2]
        val quality = when {
            usable < AudioLimits.MIN_USABLE_SPEECH_MS -> RealAudioQuality.INSUFFICIENT_SPEECH
            rms < .008f -> RealAudioQuality.TOO_QUIET
            clipped > 1f -> RealAudioQuality.CLIPPED
            noise > .035f || silence > 85f -> RealAudioQuality.NOISY
            noise > .018f || clipped > .1f -> RealAudioQuality.ACCEPTABLE
            else -> RealAudioQuality.GOOD
        }
        return AudioQualityMetrics(rms, peak, clipped, silence, usable, noise, quality)
    }
}
