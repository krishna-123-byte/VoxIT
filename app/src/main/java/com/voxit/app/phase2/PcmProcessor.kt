package com.voxit.app.phase2

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

object PcmProcessor {
    fun mixToMono(interleaved: FloatArray, channels: Int): FloatArray {
        require(channels > 0) { "Channel count must be positive" }
        if (channels == 1) return interleaved.copyOf()
        val frames = interleaved.size / channels
        return FloatArray(frames) { frame ->
            var sum = 0f
            for (channel in 0 until channels) sum += interleaved[frame * channels + channel]
            (sum / channels).coerceIn(-1f, 1f)
        }
    }

    fun resampleLinear(input: FloatArray, sourceRate: Int, targetRate: Int = AudioLimits.TARGET_SAMPLE_RATE): FloatArray {
        require(sourceRate > 0 && targetRate > 0)
        if (input.isEmpty() || sourceRate == targetRate) return input.copyOf()
        val outputSize = ceil(input.size.toDouble() * targetRate / sourceRate).toInt()
        return FloatArray(outputSize) { index ->
            val sourcePosition = index.toDouble() * sourceRate / targetRate
            val left = floor(sourcePosition).toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = (sourcePosition - left).toFloat()
            input[left] * (1f - fraction) + input[right] * fraction
        }
    }

    fun removeDcOffset(input: FloatArray): FloatArray {
        if (input.isEmpty()) return input.copyOf()
        val mean = input.fold(0.0) { acc, value -> acc + value } / input.size
        return FloatArray(input.size) { (input[it] - mean.toFloat()).coerceIn(-1f, 1f) }
    }

    fun normalizeSafely(input: FloatArray, minimumRms: Float = .005f): FloatArray {
        if (input.isEmpty()) return input.copyOf()
        val rms = sqrt(input.fold(0.0) { acc, value -> acc + value * value } / input.size).toFloat()
        val peak = input.maxOf { abs(it) }
        if (rms < minimumRms || peak <= 0f) return input.copyOf()
        val gain = (.90f / peak).coerceIn(1f, 4f)
        return FloatArray(input.size) { (input[it] * gain).coerceIn(-1f, 1f) }
    }

    fun frame(input: FloatArray, frameSize: Int, hopSize: Int): List<FloatArray> {
        require(frameSize > 0 && hopSize > 0)
        if (input.size < frameSize) return emptyList()
        val result = ArrayList<FloatArray>()
        var start = 0
        while (start + frameSize <= input.size) {
            result += input.copyOfRange(start, start + frameSize)
            start += hopSize
        }
        return result
    }

    fun rms(input: FloatArray): Float = if (input.isEmpty()) 0f else
        sqrt(input.fold(0.0) { acc, value -> acc + value * value } / input.size).toFloat()
}

class WaveformDownsampler(private val pointCount: Int = 320) {
    fun downsample(samples: FloatArray, sampleRate: Int, regions: List<SpeechRegion>): List<WaveformPoint> {
        if (samples.isEmpty() || sampleRate <= 0) return emptyList()
        val count = pointCount.coerceAtMost(samples.size).coerceAtLeast(1)
        return List(count) { index ->
            val start = index * samples.size / count
            val end = ((index + 1) * samples.size / count).coerceAtMost(samples.size)
            var peak = 0f
            for (i in start until end) peak = maxOf(peak, abs(samples[i]))
            val timeMs = start * 1000L / sampleRate
            WaveformPoint(timeMs, peak.coerceIn(0f, 1f), regions.any { timeMs in it.startMs..it.endMs })
        }
    }
}
