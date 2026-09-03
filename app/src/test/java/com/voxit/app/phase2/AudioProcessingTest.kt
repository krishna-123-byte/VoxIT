package com.voxit.app.phase2

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioProcessingTest {
    @Test fun stereoMixAveragesChannels() {
        assertArrayEquals(floatArrayOf(0f, .5f), PcmProcessor.mixToMono(floatArrayOf(1f, -1f, .25f, .75f), 2), .0001f)
    }

    @Test fun resamplingProducesExpectedLengthAndEndpoints() {
        val input = FloatArray(800) { it / 800f }
        val output = PcmProcessor.resampleLinear(input, 8_000, 16_000)
        assertEquals(1600, output.size)
        assertEquals(input.first(), output.first(), .0001f)
        assertEquals(input.last(), output.last(), .002f)
    }

    @Test fun normalizationDoesNotAmplifySilence() {
        val nearSilence = FloatArray(1000) { .0001f }
        val normalized = PcmProcessor.normalizeSafely(nearSilence)
        assertTrue(normalized.all { it == .0001f })
    }

    @Test fun waveformUsesBoundedPointCountAndSpeechFlags() {
        val samples = FloatArray(16_000) { if (it in 4_000..8_000) .5f else 0f }
        val points = WaveformDownsampler(100).downsample(samples, 16_000, listOf(SpeechRegion(250, 500)))
        assertEquals(100, points.size)
        assertTrue(points.any { it.isSpeech && it.amplitude > 0f })
        assertTrue(points.any { !it.isSpeech })
    }

    @Test fun silenceProducesNoSpeech() {
        assertTrue(EnergySpeechActivityDetector().detect(FloatArray(32_000), 16_000).isEmpty())
    }

    @Test fun constantHighFrequencyNoiseIsNotSpeech() {
        val noise = FloatArray(32_000) { if (it % 2 == 0) .08f else -.08f }
        assertTrue(EnergySpeechActivityDetector().detect(noise, 16_000).isEmpty())
    }

    @Test fun shortBurstIsIgnored() {
        val samples = FloatArray(16_000)
        addTone(samples, 2_000, 3_600)
        assertTrue(EnergySpeechActivityDetector().detect(samples, 16_000).isEmpty())
    }

    @Test fun separatedSpeechHasOrderedTimestamps() {
        val samples = FloatArray(48_000)
        addTone(samples, 4_000, 16_000)
        addTone(samples, 25_000, 40_000)
        val regions = EnergySpeechActivityDetector().detect(samples, 16_000)
        assertEquals(2, regions.size)
        assertTrue(regions[0].startMs < regions[1].startMs)
        assertTrue(regions.all { it.durationMs >= 220 })
    }

    @Test fun insufficientSpeechQualityIsExplicit() {
        val samples = FloatArray(16_000)
        addTone(samples, 3_000, 8_000)
        val regions = EnergySpeechActivityDetector().detect(samples, 16_000)
        assertEquals(RealAudioQuality.INSUFFICIENT_SPEECH, AudioQualityAnalyzer.analyse(samples, 16_000, regions).quality)
    }

    @Test fun clippedAndQuietQualityAreClassifiedFromMeasuredSamples() {
        val speech = listOf(SpeechRegion(0, 2_000))
        val clipped = FloatArray(32_000) { if (it % 2 == 0) 1f else -1f }
        val quiet = FloatArray(32_000) { .001f }
        assertEquals(RealAudioQuality.CLIPPED, AudioQualityAnalyzer.analyse(clipped, 16_000, speech).quality)
        assertEquals(RealAudioQuality.TOO_QUIET, AudioQualityAnalyzer.analyse(quiet, 16_000, speech).quality)
    }

    @Test fun sessionGenerationRejectsCancelledResults() {
        val sessions = SessionGeneration()
        val first = sessions.next()
        sessions.invalidate()
        assertFalse(sessions.isCurrent(first))
        val second = sessions.next()
        assertTrue(sessions.isCurrent(second))
    }

    private fun addTone(samples: FloatArray, start: Int, end: Int, frequency: Double = 180.0) {
        for (index in start until end.coerceAtMost(samples.size)) samples[index] = (.25 * sin(2 * PI * frequency * index / 16_000)).toFloat()
    }
}
