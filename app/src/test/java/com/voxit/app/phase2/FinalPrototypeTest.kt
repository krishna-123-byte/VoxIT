package com.voxit.app.phase2

import com.voxit.app.integrity.IntegrityConclusion
import com.voxit.app.integrity.IntegrityModelMetadata
import com.voxit.app.integrity.VoiceIntegrityResult
import com.voxit.app.domain.TranscriptSegment
import org.junit.Assert.*
import org.junit.Test

class FinalPrototypeTest {
    @Test fun missingModelsAreReviewNotFakeLowRisk() {
        val result = fixture(VoiceIntegrityResult.Unavailable("not installed"), null, RealAudioQuality.GOOD)
        val guidance = OverallGuidancePolicy.evaluate(result)
        assertEquals(GuidanceLevel.INCOMPLETE, guidance.level)
        assertNull(result.manipulationScore)
        assertNull(result.conversationRisk.score)
    }

    @Test fun poorAudioIsInconclusiveEvenWithLowAcousticScore() {
        val acoustic = available(12, IntegrityConclusion.INCONCLUSIVE)
        val guidance = OverallGuidancePolicy.evaluate(fixture(acoustic, 8, RealAudioQuality.CLIPPED))
        assertEquals(GuidanceLevel.REVIEW, guidance.level)
        assertTrue(guidance.reasons.any { it.contains("quality", true) })
    }

    @Test fun acousticAndTranscriptSignalsStaySeparateInOverallExplanation() {
        val result = fixture(available(78, IntegrityConclusion.POSSIBLE_MANIPULATION), 12, RealAudioQuality.GOOD)
        val guidance = OverallGuidancePolicy.evaluate(result)
        assertEquals(GuidanceLevel.HIGH_CAUTION, guidance.level)
        assertEquals(78, result.manipulationScore)
        assertEquals(12, result.conversationRisk.score)
        assertTrue(guidance.reasons.any { it.contains("acoustic", true) })
    }

    private fun fixture(integrity: VoiceIntegrityResult, scam: Int?, quality: RealAudioQuality): RealAnalysisResult {
        val score = (integrity as? VoiceIntegrityResult.Available)?.score
        return RealAnalysisResult(
            AudioMetadata("not-persisted.wav", "audio/wav", 10, 4_000, 16_000, 1), emptyList(), listOf(SpeechRegion(0, 3_000)),
            AudioQualityMetrics(.1f, .2f, 0f, 0f, 3_000, .01f, quality), SignalFeatures(0f,0f,0f,0f,0f,0f,0f,0f,null,null),
            if (scam == null) emptyList() else listOf(TranscriptSegment("00:01", "fixture transcript", "English", confirmed = true)), ConversationRiskResult(scam, emptyList(), "test"), if (scam == null) null else "Vosk", null, "test", manipulationScore = score, voiceIntegrity = integrity,
        )
    }

    private fun available(score: Int, conclusion: IntegrityConclusion) = VoiceIntegrityResult.Available(
        score, conclusion, 70, .5f, 3_000, 4, .9f, emptyList(),
        IntegrityModelMetadata("aasist", "AASIST-L", "test", "AASIST-L", "official", "MIT", "a".repeat(64), "model.onnx", 1, 1, "voice-integrity-models/aasist/model.onnx"),
        10, 5,
    )
}
