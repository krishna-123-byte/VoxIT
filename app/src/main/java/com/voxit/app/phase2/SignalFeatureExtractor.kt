package com.voxit.app.phase2

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class SignalFeatureExtractor(private val fftSize: Int = 2048) {
    fun extract(samples: FloatArray, sampleRate: Int): SignalFeatures {
        if (samples.isEmpty() || sampleRate <= 0) return SignalFeatures(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, null, null)
        val window = FloatArray(fftSize)
        val sourceStart = ((samples.size - fftSize) / 2).coerceAtLeast(0)
        for (i in window.indices) {
            val sample = samples.getOrElse(sourceStart + i) { 0f }
            window[i] = sample * (.5f - .5f * cos((2.0 * PI * i / (fftSize - 1))).toFloat())
        }
        val magnitudes = realFftMagnitudes(window)
        val total = magnitudes.sum().coerceAtLeast(1e-12f)
        val binHz = sampleRate.toFloat() / fftSize
        var weighted = 0f
        magnitudes.forEachIndexed { index, magnitude -> weighted += index * binHz * magnitude }
        val centroid = weighted / total
        var cumulative = 0f
        var rolloff = 0f
        for (i in magnitudes.indices) {
            cumulative += magnitudes[i]
            if (cumulative >= total * .85f) { rolloff = i * binHz; break }
        }
        val arithmetic = magnitudes.average().toFloat().coerceAtLeast(1e-12f)
        val geometric = exp(magnitudes.sumOf { ln(it.coerceAtLeast(1e-12f)).toDouble() } / magnitudes.size).toFloat()
        val flatness = (geometric / arithmetic).coerceIn(0f, 1f)
        fun band(from: Float, to: Float): Float {
            var value = 0f
            magnitudes.forEachIndexed { i, magnitude -> if (i * binHz in from..<to) value += magnitude }
            return value * 100f / total
        }
        var crossings = 0
        for (i in 1 until samples.size) if ((samples[i - 1] >= 0) != (samples[i] >= 0)) crossings++
        val pitch = estimatePitch(samples, sampleRate)
        return SignalFeatures(
            zeroCrossingRate = crossings.toFloat() / samples.size.coerceAtLeast(1),
            spectralCentroidHz = centroid,
            spectralRolloffHz = rolloff,
            spectralFlatness = flatness,
            rmsEnergy = PcmProcessor.rms(samples),
            lowBandPercent = band(0f, 300f),
            midBandPercent = band(300f, 3_000f),
            highBandPercent = band(3_000f, sampleRate / 2f + 1),
            pitchHz = pitch,
            // A single analysis window cannot establish stability reliably.
            pitchStability = null,
        )
    }

    private fun estimatePitch(samples: FloatArray, sampleRate: Int): Float? {
        val length = minOf(samples.size, 4096)
        if (length < 800 || PcmProcessor.rms(samples.copyOfRange(0, length)) < .01f) return null
        val minLag = sampleRate / 400
        val maxLag = minOf(sampleRate / 60, length / 2)
        var bestLag = 0
        var best = 0.0
        for (lag in minLag..maxLag) {
            var correlation = 0.0
            var energy = 0.0
            for (i in 0 until length - lag) {
                correlation += samples[i] * samples[i + lag]
                energy += samples[i] * samples[i]
            }
            val normalized = if (energy == 0.0) 0.0 else correlation / energy
            if (normalized > best) { best = normalized; bestLag = lag }
        }
        return if (best > .35 && bestLag > 0) sampleRate.toFloat() / bestLag else null
    }

    private fun realFftMagnitudes(input: FloatArray): FloatArray {
        require(input.size > 0 && input.size and (input.size - 1) == 0) { "FFT size must be a power of two" }
        val real = DoubleArray(input.size) { input[it].toDouble() }
        val imaginary = DoubleArray(input.size)
        var j = 0
        for (i in 1 until input.size) {
            var bit = input.size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val r = real[i]; real[i] = real[j]; real[j] = r }
        }
        var length = 2
        while (length <= input.size) {
            val angle = -2.0 * PI / length
            val wLengthReal = cos(angle)
            val wLengthImaginary = sin(angle)
            var start = 0
            while (start < input.size) {
                var wr = 1.0; var wi = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset; val odd = even + length / 2
                    val oddReal = real[odd] * wr - imaginary[odd] * wi
                    val oddImaginary = real[odd] * wi + imaginary[odd] * wr
                    real[odd] = real[even] - oddReal; imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal; imaginary[even] += oddImaginary
                    val nextWr = wr * wLengthReal - wi * wLengthImaginary
                    wi = wr * wLengthImaginary + wi * wLengthReal; wr = nextWr
                }
                start += length
            }
            length = length shl 1
        }
        return FloatArray(input.size / 2 + 1) { sqrt(real[it].pow(2) + imaginary[it].pow(2)).toFloat() }
    }
}
