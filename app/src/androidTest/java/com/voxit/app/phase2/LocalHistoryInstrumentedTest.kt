package com.voxit.app.phase2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voxit.app.integrity.VoiceIntegrityResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalHistoryInstrumentedTest {
    @Test fun retainedHistoryContainsMetadataOnlyAndSupportsIndividualAndAllDeletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "history-test-${System.nanoTime()}"
        val store = LocalHistoryStore(context, name)
        try {
            val entry = store.retain(fixture())
            assertEquals(1, store.entries.value.size)
            val persisted = context.getSharedPreferences(name, 0).all.toString()
            assertFalse(persisted.contains("secret-file"))
            assertFalse(persisted.contains("sensitive transcript"))
            assertFalse(persisted.contains("content://"))
            store.delete(entry.id)
            assertTrue(store.entries.value.isEmpty())
            store.retain(fixture()); store.retain(fixture())
            store.clear()
            assertTrue(store.entries.value.isEmpty())
        } finally { context.getSharedPreferences(name, 0).edit().clear().commit() }
    }

    private fun fixture() = RealAnalysisResult(
        AudioMetadata("secret-file.wav", "audio/wav", 1, 2_000, 16_000, 1), emptyList(), listOf(SpeechRegion(0, 2_000)),
        AudioQualityMetrics(.1f,.2f,0f,0f,2_000,.01f,RealAudioQuality.GOOD), SignalFeatures(0f,0f,0f,0f,0f,0f,0f,0f,null,null),
        listOf(com.voxit.app.domain.TranscriptSegment("00:00", "sensitive transcript", "English", confirmed = true, startMs = 0, endMs = 1_000)),
        ConversationRiskResult(null, emptyList(), "none"), null, null, "none", voiceIntegrity = VoiceIntegrityResult.Unavailable("not installed"),
    )
}
