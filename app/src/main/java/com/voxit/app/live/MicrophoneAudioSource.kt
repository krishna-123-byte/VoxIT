package com.voxit.app.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.voxit.app.phase2.AudioLimits
import com.voxit.app.phase2.AudioPipelineException
import com.voxit.app.phase2.PcmProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MicrophoneAudioSource(private val context: Context) : LiveAudioSource {
    private var recorder: AudioRecord? = null
    private var readBuffer = ShortArray(0)
    override var sourceSampleRate: Int = AudioLimits.TARGET_SAMPLE_RATE
        private set

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is not granted.")
        }
        if (recorder != null) return@withContext
        var lastError: Exception? = null
        for (rate in listOf(16_000, 48_000, 44_100)) {
            try {
                val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) continue
                val bufferBytes = maxOf(minimum * 2, rate / 5 * 2)
                val candidate = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
                if (candidate.state != AudioRecord.STATE_INITIALIZED) { candidate.release(); continue }
                recorder = candidate
                sourceSampleRate = rate
                readBuffer = ShortArray((rate / 10).coerceAtLeast(320))
                candidate.startRecording()
                if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw AudioPipelineException("Android did not start microphone recording.")
                return@withContext
            } catch (error: Exception) {
                lastError = error
                close()
            }
        }
        throw AudioPipelineException("Microphone is unavailable or could not be initialized on this device.", lastError)
    }

    override suspend fun readFrame(): FloatArray? = withContext(Dispatchers.IO) {
        val active = recorder ?: return@withContext null
        val count = active.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
        when {
            count > 0 -> {
                val source = FloatArray(count) { readBuffer[it] / 32768f }
                if (sourceSampleRate == AudioLimits.TARGET_SAMPLE_RATE) source else PcmProcessor.resampleLinear(source, sourceSampleRate)
            }
            count == 0 -> FloatArray(0)
            count == AudioRecord.ERROR_DEAD_OBJECT -> throw AudioPipelineException("The microphone was disconnected or its audio route changed.")
            count == AudioRecord.ERROR_INVALID_OPERATION -> throw AudioPipelineException("Microphone recording is no longer active.")
            else -> throw AudioPipelineException("Android returned a microphone read error ($count).")
        }
    }

    override suspend fun pause() = withContext(Dispatchers.IO) { recorder?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }; Unit }

    override suspend fun resume() = withContext(Dispatchers.IO) {
        recorder?.let { if (it.recordingState != AudioRecord.RECORDSTATE_RECORDING) { it.startRecording(); if (it.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw AudioPipelineException("Microphone could not resume.") } }
            ?: throw AudioPipelineException("Microphone is not initialized.")
    }

    override fun close() {
        val current = recorder
        recorder = null
        try { if (current?.recordingState == AudioRecord.RECORDSTATE_RECORDING) current.stop() } catch (_: Exception) { }
        try { current?.release() } catch (_: Exception) { }
        readBuffer.fill(0)
        readBuffer = ShortArray(0)
    }
}
