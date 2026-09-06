package com.voxit.app.phase2

import com.voxit.app.domain.TranscriptSegment
import com.voxit.app.integrity.IntegrityConclusion
import com.voxit.app.integrity.IntegrityModelMetadata
import com.voxit.app.integrity.VoiceIntegrityResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ResultPresentationTest {
    @Test fun `all acoustic states use cautious plain language`() {
        assertEquals(VoiceResultState.LIKELY_MANIPULATED, ResultPresentationPolicy.voiceState(available(78, IntegrityConclusion.POSSIBLE_MANIPULATION)))
        assertEquals(VoiceResultState.LIKELY_HUMAN, ResultPresentationPolicy.voiceState(available(12, IntegrityConclusion.LIKELY_AUTHENTIC)))
        assertEquals(VoiceResultState.INCONCLUSIVE, ResultPresentationPolicy.voiceState(available(49, IntegrityConclusion.INCONCLUSIVE)))
        assertEquals(VoiceResultState.NOT_PERFORMED, ResultPresentationPolicy.voiceState(VoiceIntegrityResult.Unavailable("Voice-integrity model not installed")))
        assertEquals(VoiceResultState.INCONCLUSIVE, ResultPresentationPolicy.voiceState(VoiceIntegrityResult.Unavailable("Insufficient speech for voice-integrity analysis")))
        assertEquals(VoiceResultState.FAILED, ResultPresentationPolicy.voiceState(VoiceIntegrityResult.Failed("runtime error")))
    }

    @Test fun `fraud states require a real transcript`() {
        assertEquals(FraudResultState.NOT_PERFORMED, ResultPresentationPolicy.evaluate(fixture(available(12, IntegrityConclusion.LIKELY_AUTHENTIC), null, emptyList())).fraud)
        assertEquals(FraudResultState.NO_STRONG_LANGUAGE, ResultPresentationPolicy.evaluate(fixture(available(12, IntegrityConclusion.LIKELY_AUTHENTIC), 10, transcript())).fraud)
        assertEquals(FraudResultState.SUSPICIOUS, ResultPresentationPolicy.evaluate(fixture(available(12, IntegrityConclusion.LIKELY_AUTHENTIC), 45, transcript())).fraud)
        assertEquals(FraudResultState.HIGH_RISK, ResultPresentationPolicy.evaluate(fixture(available(12, IntegrityConclusion.LIKELY_AUTHENTIC), 80, transcript())).fraud)
        assertEquals(FraudResultState.INCONCLUSIVE, ResultPresentationPolicy.evaluate(fixture(available(12, IntegrityConclusion.LIKELY_AUTHENTIC), null, transcript())).fraud)
    }

    @Test fun `overall state never turns missing checks or poor audio green`() {
        assertEquals(GuidanceLevel.INCOMPLETE, ResultPresentationPolicy.evaluate(fixture(VoiceIntegrityResult.Unavailable("not installed"), 5, transcript())).overall.level)
        assertEquals(GuidanceLevel.INCOMPLETE, ResultPresentationPolicy.evaluate(fixture(available(12, IntegrityConclusion.LIKELY_AUTHENTIC), null, emptyList())).overall.level)
        assertEquals(GuidanceLevel.REVIEW, ResultPresentationPolicy.evaluate(fixture(available(12, IntegrityConclusion.INCONCLUSIVE), 5, transcript(), RealAudioQuality.CLIPPED)).overall.level)
        assertEquals(GuidanceLevel.NO_STRONG_WARNING, ResultPresentationPolicy.evaluate(fixture(available(12, IntegrityConclusion.LIKELY_AUTHENTIC), 5, transcript())).overall.level)
    }

    @Test fun `strong independent evidence produces caution without cross-classification`() {
        val transcriptHigh = ResultPresentationPolicy.evaluate(fixture(available(12, IntegrityConclusion.LIKELY_AUTHENTIC), 85, transcript()))
        assertEquals(GuidanceLevel.HIGH_CAUTION, transcriptHigh.overall.level)
        assertEquals(VoiceResultState.LIKELY_HUMAN, transcriptHigh.voice)
        assertEquals(FraudResultState.HIGH_RISK, transcriptHigh.fraud)

        val acousticHigh = ResultPresentationPolicy.evaluate(fixture(available(85, IntegrityConclusion.POSSIBLE_MANIPULATION), 5, transcript()))
        assertEquals(GuidanceLevel.HIGH_CAUTION, acousticHigh.overall.level)
        assertEquals(VoiceResultState.LIKELY_MANIPULATED, acousticHigh.voice)
        assertEquals(FraudResultState.NO_STRONG_LANGUAGE, acousticHigh.fraud)
        assertFalse(acousticHigh.voice.description.contains("fraud", ignoreCase = true))
    }

    private fun transcript() = listOf(TranscriptSegment("00:01", "ordinary confirmed words", "English", true, false, 1_000, 2_000))

    private fun fixture(
        integrity: VoiceIntegrityResult,
        scam: Int?,
        transcript: List<TranscriptSegment>,
        quality: RealAudioQuality = RealAudioQuality.GOOD,
    ) = RealAnalysisResult(
        AudioMetadata("not-persisted.wav", "audio/wav", 10, 4_000, 16_000, 1), emptyList(), listOf(SpeechRegion(0, 3_000)),
        AudioQualityMetrics(.1f, .2f, 0f, 0f, 3_000, .01f, quality), SignalFeatures(0f,0f,0f,0f,0f,0f,0f,0f,null,null),
        transcript, ConversationRiskResult(scam, emptyList(), "test"), if (transcript.isEmpty()) null else "Vosk", if (transcript.isEmpty()) null else "test", "test",
        manipulationScore = (integrity as? VoiceIntegrityResult.Available)?.score, voiceIntegrity = integrity,
    )

    private fun available(score: Int, conclusion: IntegrityConclusion) = VoiceIntegrityResult.Available(
        score, conclusion, 70, .5f, 3_000, 4, .9f, emptyList(),
        IntegrityModelMetadata("aasist", "AASIST-L", "test", "AASIST-L", "official", "MIT", "a".repeat(64), "model.onnx", 1, 1, "voice-integrity-models/aasist/model.onnx"),
        10, 5,
    )
}
