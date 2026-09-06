package com.voxit.app.live

import com.voxit.app.domain.TranscriptSegment
import com.voxit.app.phase2.ConversationRiskResult
import com.voxit.app.phase2.TranscriptWarning
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveStatusPresentationTest {
    @Test fun `missing transcript stays monitoring rather than green`() {
        val state = LiveProtectionState(status = LiveSessionStatus.LISTENING, microphoneActive = true)
        assertEquals(LiveHeadline.MONITORING, LiveStatusPresentationPolicy.evaluate(state).headline)
    }

    @Test fun `confirmed low transcript can show no strong scam signs`() {
        val state = LiveProtectionState(
            status = LiveSessionStatus.LISTENING,
            microphoneActive = true,
            confirmedTranscript = listOf(TranscriptSegment("00:01", "ordinary confirmed words", "English", true, false, 1_000, 2_000)),
            conversationRisk = ConversationRiskResult(5, emptyList(), "none"),
        )
        assertEquals(LiveHeadline.NO_STRONG_SCAM_SIGNS, LiveStatusPresentationPolicy.evaluate(state).headline)
    }

    @Test fun `warning pause and blocked audio remain distinct`() {
        val warning = TranscriptWarning("Sensitive credential request", "00:01", "redacted", "test", 88)
        val caution = LiveProtectionState(status = LiveSessionStatus.ALERT, conversationRisk = ConversationRiskResult(80, listOf(warning), "test"))
        assertEquals(LiveHeadline.HIGH_CAUTION, LiveStatusPresentationPolicy.evaluate(caution).headline)
        assertEquals(LiveHeadline.PAUSED, LiveStatusPresentationPolicy.evaluate(LiveProtectionState(status = LiveSessionStatus.PAUSED)).headline)
        assertEquals(LiveHeadline.AUDIO_UNAVAILABLE, LiveStatusPresentationPolicy.evaluate(LiveProtectionState(status = LiveSessionStatus.AUDIO_BLOCKED)).headline)
    }
}
