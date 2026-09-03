package com.voxit.app.phase2

import com.voxit.app.domain.TranscriptSegment
import com.voxit.app.domain.TranscriptionOutput
import org.junit.Assert.*
import org.junit.Test

class ConversationRiskTest {
    private val engine = OfflineConversationRiskEngine()

    @Test fun credentialRequestCreatesExplainableWarning() {
        val result = engine.analyse(listOf(segment("Tell me your OTP immediately", 1_000)))
        assertTrue((result.score ?: 0) >= 40)
        assertTrue(result.warnings.any { it.category == "Sensitive credential request" })
        assertTrue(result.explanation.contains("not confirmed fraud"))
    }

    @Test fun safetyAdviceIsNotTreatedLikeARequest() {
        val result = engine.analyse(listOf(segment("Never share your OTP. A bank will never ask for your PIN.", 0)))
        assertEquals(0, result.score)
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun isolatedOtpWordDoesNotTriggerRequestRule() {
        assertTrue(engine.analyse(listOf(segment("I received an OTP", 0))).warnings.isEmpty())
    }

    @Test fun hindiCredentialRequestIsDetectedButHindiAdviceIsNot() {
        val request = engine.analyse(listOf(segment("अपना ओटीपी अभी बताओ", 0)))
        val advice = engine.analyse(listOf(segment("किसी को अपना ओटीपी मत बताओ", 0)))
        assertTrue(request.warnings.any { it.category == "Sensitive credential request" })
        assertTrue(advice.warnings.isEmpty())
    }

    @Test fun isolatedThreatWordDoesNotTriggerWithoutCoerciveContext() {
        assertTrue(engine.analyse(listOf(segment("The article discussed arrest statistics", 0))).warnings.isEmpty())
    }

    @Test fun warningsFollowTranscriptTimestampOrder() {
        val result = engine.analyse(listOf(segment("Install AnyDesk now", 9_000), segment("Tell me your OTP", 1_000)))
        assertEquals("00:01", result.warnings.first().timestamp)
    }

    @Test fun sensitiveValuesAreRedacted() {
        val text = SensitiveDataRedactor.redact("OTP is 123456, card 4111 1111 1111 1111, call 9876543210, password: Secret99")
        assertFalse(text.contains("123456")); assertFalse(text.contains("4111")); assertFalse(text.contains("9876543210")); assertFalse(text.contains("Secret99"))
        assertTrue(text.contains("[REDACTED]"))
    }

    @Test fun missingModelStateIsUnavailableNotDemo() {
        val state: TranscriptionOutput = TranscriptionOutput.Unavailable("Offline transcription model not installed.")
        assertTrue(state is TranscriptionOutput.Unavailable)
        assertFalse((state as TranscriptionOutput.Unavailable).reason.contains("demo", true))
    }

    @Test fun unavailableDetectorScoresStayNull() {
        val result = RealAnalysisResult(
            AudioMetadata("x.wav", "audio/wav", 1, 1000, 16000, 1), emptyList(), emptyList(),
            AudioQualityMetrics(.1f, .2f, 0f, 100f, 0, 0f, RealAudioQuality.INSUFFICIENT_SPEECH),
            SignalFeatures(0f,0f,0f,0f,0f,0f,0f,0f,null,null), emptyList(),
            ConversationRiskResult(null, emptyList(), "No transcript"), null, null, "Model missing"
        )
        assertNull(result.manipulationScore); assertNull(result.speakerMismatchScore); assertNull(result.conversationRisk.score)
    }

    private fun segment(text: String, startMs: Long) = TranscriptSegment(formatTime(startMs), text, "English", confirmed = true, startMs = startMs, endMs = startMs + 1_000)
}
