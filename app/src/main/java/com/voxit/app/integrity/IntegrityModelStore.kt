package com.voxit.app.integrity

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class IntegrityModelStore(private val context: Context, private val root: File = context.filesDir) {
    private val prefs = context.getSharedPreferences("voice_integrity_model", Context.MODE_PRIVATE)
    private val directory = File(root, "voice-integrity-models/aasist-l-asvspoof2019-la-v1")
    private val modelFile = File(directory, "model.onnx")

    fun state(): IntegrityModelState {
        val metadata = readMetadata() ?: return IntegrityModelState()
        if (!modelFile.isFile) return IntegrityModelState(IntegrityModelStatus.NOT_INSTALLED, metadata, "Voice-integrity model not installed")
        if (sha256(modelFile) != metadata.sha256) return IntegrityModelState(IntegrityModelStatus.CORRUPT, metadata, "Installed voice-integrity model failed checksum validation")
        return IntegrityModelState(IntegrityModelStatus.READY, metadata, "Ready for uploaded-recording analysis")
    }

    suspend fun importModel(uri: Uri, onState: (IntegrityModelState) -> Unit): IntegrityModelState = withContext(Dispatchers.IO) {
        onState(IntegrityModelState(IntegrityModelStatus.IMPORTING, message = "Importing voice-integrity model"))
        val name = displayName(uri)
        require(name.endsWith(".onnx", true)) { "Select the reproducibly exported AASIST-L ONNX file." }
        val declaredSize = querySize(uri)
        require(declaredSize in 1..MAX_BYTES) { "Model size is invalid or exceeds the 10 MB safety limit." }
        val staging = File(root, "voice-integrity-importing.onnx")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) { val read = input.read(buffer); if (read < 0) break; total += read; require(total <= MAX_BYTES) { "Model exceeds the 10 MB safety limit." }; output.write(buffer, 0, read) }
                }
            } ?: error("The selected model could not be opened.")
            onState(IntegrityModelState(IntegrityModelStatus.VALIDATING, message = "Validating model identity and tensor contract"))
            val hash = sha256(staging)
            require(hash == EXPECTED_ONNX_SHA256) { "Model checksum does not match VoxIT's verified AASIST-L export." }
            validateOnnx(staging)
            directory.mkdirs()
            if (modelFile.exists()) modelFile.delete()
            require(staging.renameTo(modelFile) || staging.copyTo(modelFile, overwrite = false).isFile) { "Model could not be moved into private storage." }
            val metadata = IntegrityModelMetadata(MODEL_ID, "AASIST-L", "ASVspoof2019-LA official checkpoint / VoxIT ONNX export v1", "AASIST-L", SOURCE, "MIT", hash, name, modelFile.length(), System.currentTimeMillis(), "voice-integrity-models/$MODEL_ID/model.onnx")
            prefs.edit().putString("metadata", JSONObject().put("fileName", name).put("size", metadata.sizeBytes).put("importedAt", metadata.importedAtEpochMs).toString()).apply()
            IntegrityModelState(IntegrityModelStatus.READY, metadata, "Ready for uploaded-recording analysis")
        } catch (error: Exception) {
            IntegrityModelState(if (error is IllegalArgumentException) IntegrityModelStatus.INCOMPATIBLE else IntegrityModelStatus.ERROR, message = error.message ?: "Model import failed safely")
        } finally { if (staging.exists()) staging.delete() }
    }

    fun modelFileOrNull(): File? = state().takeIf { it.status == IntegrityModelStatus.READY }?.let { modelFile }
    fun delete() { modelFile.delete(); directory.delete(); prefs.edit().clear().apply() }

    private fun validateOnnx(file: File) {
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            env.createSession(file.absolutePath, options).use { session ->
                require(session.inputNames == setOf("audio") && session.outputNames == setOf("logits")) { "Unexpected ONNX input or output name." }
                val input = session.inputInfo["audio"]?.info as? TensorInfo ?: error("Audio input is not a tensor.")
                val output = session.outputInfo["logits"]?.info as? TensorInfo ?: error("Logits output is not a tensor.")
                require(input.shape.contentEquals(longArrayOf(1, 64_600)) && output.shape.contentEquals(longArrayOf(1, 2))) { "Unexpected ONNX tensor shape." }
            }
        }
    }

    private fun readMetadata(): IntegrityModelMetadata? {
        val raw = prefs.getString("metadata", null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()?.let { json ->
            IntegrityModelMetadata(MODEL_ID, "AASIST-L", "ASVspoof2019-LA official checkpoint / VoxIT ONNX export v1", "AASIST-L", SOURCE, "MIT", EXPECTED_ONNX_SHA256, json.optString("fileName", "aasist-l.onnx"), json.optLong("size"), json.optLong("importedAt"), "voice-integrity-models/$MODEL_ID/model.onnx")
        }
    }
    private fun displayName(uri: Uri) = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    private fun querySize(uri: Uri): Long = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } }.getOrNull()
        ?: runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()
        ?: uri.path?.let { File(it).takeIf(File::isFile)?.length() } ?: -1L

    companion object {
        const val MODEL_ID = "aasist-l-asvspoof2019-la-v1"
        const val EXPECTED_ONNX_SHA256 = "6a752e443e848b125808a5dd941799c89b572df0db6ca3f610542f24268c5a6f"
        const val SOURCE = "https://github.com/clovaai/aasist@a04c9863f63d44471dde8a6abcb3b082b07cd1d1"
        const val MAX_BYTES = 10L * 1024L * 1024L
        fun sha256(file: File): String { val digest = MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) } }; return digest.digest().joinToString("") { "%02x".format(it) } }
    }
}
