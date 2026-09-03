package com.voxit.app.phase2

import android.content.ContentResolver
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AndroidAudioDecoder(private val resolver: ContentResolver) {
    suspend fun decode(file: SelectedAudio): DecodedAudio {
        validate(file)
        return try {
            if (file.mimeType.equals("audio/wav", true) || file.name.endsWith(".wav", true)) decodeWav(file) else decodeWithMediaCodec(file)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AudioPipelineException) {
            throw error
        } catch (error: SecurityException) {
            throw AudioPipelineException("Permission to read this audio file was lost. Select it again.", error)
        } catch (error: OutOfMemoryError) {
            throw AudioPipelineException("The recording needs too much memory. Choose a shorter or lower-sample-rate file.", error)
        } catch (error: Exception) {
            throw AudioPipelineException("The audio is corrupt, unreadable, or could not be decoded.", error)
        }
    }

    private fun validate(file: SelectedAudio) {
        if (file.sizeBytes == 0L) throw AudioPipelineException("The selected file is empty.")
        if (file.sizeBytes > AudioLimits.MAX_FILE_BYTES) throw AudioPipelineException("The recording is larger than the 100 MB Phase 2 limit.")
    }

    private suspend fun decodeWav(file: SelectedAudio): DecodedAudio {
        val stream = resolver.openInputStream(file.uri) ?: throw AudioPipelineException("The selected URI can no longer be opened.")
        BufferedInputStream(stream).use { input ->
            val riff = input.readExact(12)
            if (riff.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF" || riff.copyOfRange(8, 12).toString(Charsets.US_ASCII) != "WAVE") throw AudioPipelineException("This WAV file has an invalid RIFF header.")
            var formatCode = 0; var channels = 0; var sampleRate = 0; var bits = 0; var dataSize = -1L
            while (dataSize < 0) {
                currentCoroutineContext().ensureActive()
                val chunk = input.readExactOrNull(8) ?: throw AudioPipelineException("The WAV file has no audio data chunk.")
                val id = chunk.copyOfRange(0, 4).toString(Charsets.US_ASCII)
                val size = littleInt(chunk, 4).toLong() and 0xffffffffL
                when (id) {
                    "fmt " -> {
                        if (size < 16 || size > 1024 * 1024) throw AudioPipelineException("The WAV format section is invalid.")
                        val fmt = input.readExact(size.toInt())
                        formatCode = littleShort(fmt, 0); channels = littleShort(fmt, 2); sampleRate = littleInt(fmt, 4); bits = littleShort(fmt, 14)
                    }
                    "data" -> dataSize = size
                    else -> input.skipFully(size)
                }
                if (id != "data" && size % 2L == 1L) input.skipFully(1)
            }
            if (channels !in 1..8 || sampleRate !in 8_000..96_000) throw AudioPipelineException("Unsupported WAV channel count or sample rate.")
            if (!((formatCode == 1 && bits == 16) || (formatCode == 3 && bits == 32))) throw AudioPipelineException("Only PCM 16-bit and float 32-bit WAV files are supported.")
            val bytesPerSample = bits / 8
            val frameBytes = bytesPerSample * channels
            val frames = dataSize / frameBytes
            val durationMs = frames * 1000L / sampleRate
            validateDuration(durationMs)
            validateSampleMemory(frames)
            val accumulator = FloatAccumulator(maxOf(1, frames.coerceAtMost(AudioLimits.MAX_DECODED_MONO_SAMPLES).toInt()))
            val buffer = ByteArray(8192 - 8192 % frameBytes)
            var remaining = dataSize
            while (remaining >= frameBytes) {
                currentCoroutineContext().ensureActive()
                val wanted = minOf(buffer.size.toLong(), remaining).toInt().let { it - it % frameBytes }
                val read = input.read(buffer, 0, wanted)
                if (read <= 0) throw AudioPipelineException("The WAV audio data ends unexpectedly.")
                appendInterleavedPcm(accumulator, ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN), if (bits == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_FLOAT, channels)
                remaining -= read
            }
            return DecodedAudio(accumulator.toArray(), AudioMetadata(file.name, file.mimeType, file.sizeBytes, durationMs, sampleRate, channels))
        }
    }

    private suspend fun decodeWithMediaCodec(file: SelectedAudio): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val descriptor = resolver.openAssetFileDescriptor(file.uri, "r") ?: throw AudioPipelineException("The selected URI can no longer be opened.")
            descriptor.use { afd -> if (afd.length >= 0) extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) else extractor.setDataSource(afd.fileDescriptor) }
            var track = -1; var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = index; inputFormat = format; break }
            }
            if (track < 0 || inputFormat == null) throw AudioPipelineException("No audio track was found in this file.")
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else -1L
            if (durationUs > 0) validateDuration(durationUs / 1000)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw AudioPipelineException("The audio track does not declare a codec.")
            extractor.selectTrack(track)
            codec = try { MediaCodec.createDecoderByType(mime) } catch (error: Exception) { throw AudioPipelineException("No decoder is available for $mime on this device.", error) }
            codec.configure(inputFormat, null, null, 0); codec.start()
            var sampleRate = inputFormat.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 0)
            var channels = inputFormat.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 0)
            if (durationUs > 0 && sampleRate > 0) validateSampleMemory(durationUs * sampleRate / 1_000_000L)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val accumulator = FloatAccumulator(64 * 1024)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false; var outputEnded = false; var idleCount = 0
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: throw AudioPipelineException("Decoder input buffer was unavailable.")
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) { codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0), 0); extractor.advance() }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        sampleRate = output.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = output.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        encoding = output.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        idleCount = 0
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (++idleCount > 500) throw AudioPipelineException("The decoder stopped producing audio.")
                    else -> if (outputIndex >= 0) {
                        idleCount = 0
                        if (info.size > 0) {
                            val output = codec.getOutputBuffer(outputIndex) ?: throw AudioPipelineException("Decoder output buffer was unavailable.")
                            val view = output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            view.position(info.offset); view.limit(info.offset + info.size)
                            appendInterleavedPcm(accumulator, view.slice().order(ByteOrder.LITTLE_ENDIAN), encoding, channels.coerceAtLeast(1))
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            if (sampleRate <= 0 || channels <= 0 || accumulator.size == 0) throw AudioPipelineException("The decoder returned no usable PCM audio.")
            val durationMs = accumulator.size * 1000L / sampleRate
            validateDuration(durationMs)
            return DecodedAudio(accumulator.toArray(), AudioMetadata(file.name, file.mimeType, file.sizeBytes, durationMs, sampleRate, channels))
        } catch (error: AudioPipelineException) { throw error }
        catch (error: SecurityException) { throw AudioPipelineException("Permission to read this audio file was lost. Select it again.", error) }
        catch (error: OutOfMemoryError) { throw AudioPipelineException("The recording needs too much memory. Choose a shorter or smaller file.", error) }
        catch (error: Exception) { throw AudioPipelineException("The audio is corrupt, unsupported, or could not be decoded.", error) }
        finally { try { codec?.stop() } catch (_: Exception) {}; try { codec?.release() } catch (_: Exception) {}; extractor.release() }
    }

    private fun validateDuration(durationMs: Long) {
        if (durationMs in 0 until AudioLimits.MIN_DURATION_MS) throw AudioPipelineException("The recording is shorter than the 0.5 second minimum.")
        if (durationMs > AudioLimits.MAX_DURATION_MS) throw AudioPipelineException("The recording is longer than the 10 minute Phase 2 limit.")
    }

    private fun validateSampleMemory(sampleCount: Long) {
        if (sampleCount > AudioLimits.MAX_DECODED_MONO_SAMPLES) {
            throw AudioPipelineException("The decoded recording exceeds the safe in-memory sample limit.")
        }
        // The pipeline temporarily needs decoded, DC-corrected, resampled, and normalized float buffers.
        val estimatedWorkingBytes = sampleCount * Float.SIZE_BYTES * 4L
        val safeWorkingBudget = (Runtime.getRuntime().maxMemory() * .65).toLong()
        if (estimatedWorkingBytes > safeWorkingBudget) {
            throw AudioPipelineException("This recording may exceed this device's safe processing memory. Choose a shorter or lower-sample-rate file.")
        }
    }

    private fun appendInterleavedPcm(target: FloatAccumulator, bytes: ByteBuffer, encoding: Int, channels: Int) {
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        while (bytes.remaining() >= bytesPerSample * channels) {
            var sum = 0f
            repeat(channels) { sum += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) bytes.float else bytes.short / 32768f }
            target.add((sum / channels).coerceIn(-1f, 1f))
        }
    }

    private class FloatAccumulator(initialCapacity: Int) {
        private var data = FloatArray(initialCapacity.coerceAtLeast(1)); var size: Int = 0; private set
        fun add(value: Float) { if (size >= AudioLimits.MAX_DECODED_MONO_SAMPLES) throw AudioPipelineException("Decoded audio exceeds the safe in-memory sample limit."); if (size == data.size) data = data.copyOf((data.size * 2).coerceAtMost(AudioLimits.MAX_DECODED_MONO_SAMPLES.toInt())); data[size++] = value }
        fun toArray() = data.copyOf(size)
    }
}

private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int = if (containsKey(key)) getInteger(key) else fallback
private fun BufferedInputStream.readExact(size: Int): ByteArray = readExactOrNull(size) ?: throw EOFException()
private fun BufferedInputStream.readExactOrNull(size: Int): ByteArray? { val data = ByteArray(size); var offset = 0; while (offset < size) { val read = read(data, offset, size - offset); if (read < 0) return if (offset == 0) null else throw EOFException(); offset += read }; return data }
private fun BufferedInputStream.skipFully(count: Long) { var remaining = count; while (remaining > 0) { val skipped = skip(remaining); if (skipped <= 0) { if (read() < 0) throw EOFException(); remaining-- } else remaining -= skipped } }
private fun littleShort(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
private fun littleInt(bytes: ByteArray, offset: Int) = littleShort(bytes, offset) or (littleShort(bytes, offset + 2) shl 16)

fun ContentResolver.selectedAudio(uri: android.net.Uri): SelectedAudio {
    var name = "Selected audio"; var size = -1L
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) { name = cursor.getString(0) ?: name; if (!cursor.isNull(1)) size = cursor.getLong(1) }
    }
    return SelectedAudio(uri, name, getType(uri) ?: "audio/unknown", size)
}
