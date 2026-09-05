package com.voxit.app.integrity

import com.voxit.app.phase2.*
import org.junit.Assert.*
import org.junit.Test

class VoiceIntegrityTest {
    @Test fun fixedWindowUsesOfficialShapeAndRepeatPadding() {
        val samples = FloatArray(32_000) { it / 32_000f }
        val windows = IntegrityWindowBuilder.build(samples, 16_000, listOf(SpeechRegion(0, 2_000)))
        assertEquals(1, windows.size); assertEquals(64_600, windows.single().samples.size)
        assertEquals(samples[0], windows.single().samples[32_000], 0f)
    }

    @Test fun overlappingLongWindowsAreBoundedAndSpeechOnly() {
        val samples = FloatArray(16_000 * 20) { .1f }
        val windows = IntegrityWindowBuilder.build(samples, 16_000, listOf(SpeechRegion(2_000, 18_000)))
        assertTrue(windows.size in 2..IntegrityWindowBuilder.MAX_WINDOWS)
        assertTrue(windows.all { it.startMs >= 2_000 && it.endMs <= 18_000 })
        assertTrue(windows.zipWithNext().all { (a,b) -> b.startMs > a.startMs && b.startMs < a.endMs })
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsIncorrectSampleRate() {
        IntegrityWindowBuilder.build(FloatArray(64_600), 8_000, listOf(SpeechRegion(0, 8_075)))
    }

    @Test fun tooShortAndSilenceProduceNoWindows() {
        assertTrue(IntegrityWindowBuilder.build(FloatArray(8_000), 16_000, listOf(SpeechRegion(0, 500))).isEmpty())
        assertTrue(IntegrityWindowBuilder.build(FloatArray(64_600), 16_000, emptyList()).isEmpty())
    }

    @Test fun thresholdAndInconclusiveBoundariesAreHonest() {
        val low = IntegrityAggregator.aggregate(listOf(.34f, .34f, .34f, .34f), RealAudioQuality.GOOD)
        val middle = IntegrityAggregator.aggregate(listOf(.5f, .5f, .5f, .5f), RealAudioQuality.GOOD)
        val high = IntegrityAggregator.aggregate(listOf(.65f, .65f, .65f, .65f), RealAudioQuality.GOOD)
        assertEquals(IntegrityConclusion.LIKELY_AUTHENTIC, low.second)
        assertEquals(IntegrityConclusion.INCONCLUSIVE, middle.second)
        assertEquals(IntegrityConclusion.POSSIBLE_MANIPULATION, high.second)
    }

    @Test fun noisyOrClippedAudioNeverForcesManipulationConclusion() {
        assertEquals(IntegrityConclusion.INCONCLUSIVE, IntegrityAggregator.aggregate(listOf(.99f, .99f), RealAudioQuality.NOISY).second)
        assertEquals(IntegrityConclusion.INCONCLUSIVE, IntegrityAggregator.aggregate(listOf(.99f, .99f), RealAudioQuality.CLIPPED).second)
    }

    @Test fun disagreementReducesConfidenceAndScoreCanRiseOrFall() {
        val agreed = IntegrityAggregator.aggregate(listOf(.8f, .8f, .8f, .8f), RealAudioQuality.GOOD)
        val mixed = IntegrityAggregator.aggregate(listOf(.1f, .9f, .1f, .9f), RealAudioQuality.GOOD)
        val falling = IntegrityAggregator.aggregate(listOf(.2f, .2f, .2f, .2f), RealAudioQuality.GOOD)
        assertTrue(agreed.third.first > mixed.third.first)
        assertTrue(falling.first < agreed.first)
    }

    @Test fun acousticAndTranscriptRiskRemainSeparateAndUnavailableIsNotZero() {
        val result = RealAnalysisResult(
            metadata = AudioMetadata("fixture", "audio/wav", 1, 2_000, 16_000, 1), waveform = emptyList(), speechRegions = emptyList(),
            quality = AudioQualityMetrics(.1f,.2f,0f,0f,2_000,0f,RealAudioQuality.GOOD), features = SignalFeatures(0f,0f,0f,0f,0f,0f,0f,0f,null,null),
            transcript = emptyList(), conversationRisk = ConversationRiskResult(88, emptyList(), "test"), transcriptionModel = null, transcriptionVersion = null, transcriptionMessage = "none",
            manipulationScore = null, voiceIntegrity = VoiceIntegrityResult.Unavailable("missing"),
        )
        assertNull(result.manipulationScore); assertEquals(88, result.conversationRisk.score)
        assertTrue(result.voiceIntegrity is VoiceIntegrityResult.Unavailable)
    }
}
