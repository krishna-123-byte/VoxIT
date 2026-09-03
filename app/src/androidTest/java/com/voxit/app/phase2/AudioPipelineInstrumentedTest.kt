package com.voxit.app.phase2

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AudioPipelineInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun validPcmWavDecodesAndProducesRealWaveform() = runBlocking {
        val file = writeWav("valid-tone.wav", 3_000) { index ->
            if (index < 11_200 || index > 36_800) 0f
            else ((.12 + .14 * kotlin.math.abs(sin(2 * PI * 2.2 * index / 16_000))) * sin(2 * PI * 180 * index / 16_000)).toFloat()
        }
        try {
            val selected = SelectedAudio(Uri.fromFile(file), file.name, "audio/wav", file.length())
            val decoded = AndroidAudioDecoder(context.contentResolver).decode(selected)
            assertEquals(16_000, decoded.metadata.sampleRate)
            assertEquals(1, decoded.metadata.channelCount)
            assertEquals(48_000, decoded.monoSamples.size)
            val result = UploadedAudioPipeline(context).analyse(selected, "English") { }
            assertTrue(result.waveform.isNotEmpty())
            assertTrue(result.speechRegions.isNotEmpty())
            assertNull(result.manipulationScore)
            assertNull(result.conversationRisk.score)
            assertTrue(result.transcriptionMessage.contains("not installed", true))
            assertFalse(result.transcriptionMessage.contains("demo", true))
        } finally { file.delete() }
    }

    @Test fun viewModelSessionResetClearsSelectedFile() {
        val file = writeWav("reset.wav", 1_000) { 0f }
        try {
            val viewModel = Phase2ViewModel(context)
            viewModel.selectAudio(Uri.fromFile(file))
            assertTrue(viewModel.uiState.value is Phase2UiState.FileSelected)
            viewModel.reset()
            assertTrue(viewModel.uiState.value is Phase2UiState.Idle)
        } finally { file.delete() }
    }

    @Test fun silenceOnlyWavReturnsInsufficientSpeech() = runBlocking {
        val file = writeWav("silence.wav", 2_000) { 0f }
        try {
            val selected = SelectedAudio(Uri.fromFile(file), file.name, "audio/wav", file.length())
            val result = UploadedAudioPipeline(context).analyse(selected, "English") { }
            assertEquals(RealAudioQuality.INSUFFICIENT_SPEECH, result.quality.quality)
            assertTrue(result.speechRegions.isEmpty())
            assertTrue(result.transcript.isEmpty())
        } finally { file.delete() }
    }

    @Test fun tooShortWavFailsWithClearError() = runBlocking {
        val file = writeWav("too-short.wav", 200) { 0f }
        try {
            val selected = SelectedAudio(Uri.fromFile(file), file.name, "audio/wav", file.length())
            val error = runCatching { AndroidAudioDecoder(context.contentResolver).decode(selected) }.exceptionOrNull()
            assertTrue(error is AudioPipelineException)
            assertTrue(error?.message?.contains("0.5 second") == true)
        } finally { file.delete() }
    }

    private fun writeWav(name: String, durationMs: Int, sample: (Int) -> Float): File {
        val sampleRate = 16_000
        val sampleCount = sampleRate * durationMs / 1000
        val file = File(context.cacheDir, name)
        DataOutputStream(FileOutputStream(file)).use { output ->
            fun littleShort(value: Int) { output.writeByte(value and 0xff); output.writeByte(value ushr 8 and 0xff) }
            fun littleInt(value: Int) { littleShort(value and 0xffff); littleShort(value ushr 16 and 0xffff) }
            output.writeBytes("RIFF"); littleInt(36 + sampleCount * 2); output.writeBytes("WAVEfmt "); littleInt(16)
            littleShort(1); littleShort(1); littleInt(sampleRate); littleInt(sampleRate * 2); littleShort(2); littleShort(16)
            output.writeBytes("data"); littleInt(sampleCount * 2)
            repeat(sampleCount) { littleShort((sample(it).coerceIn(-1f, 1f) * 32767).toInt()) }
        }
        return file
    }
}
