package com.voxit.app.phase2

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class DetectedVoskIdentity(val language: String, val languageCode: String)

object VoskModelIdentity {
    fun detect(name: String): DetectedVoskIdentity? {
        val normalized = name.lowercase()
        return when {
            "vosk-model-small-en-in-0.4" in normalized -> DetectedVoskIdentity("Indian English", "en-IN")
            "vosk-model-small-en-us-0.15" in normalized -> DetectedVoskIdentity("English", "en-US")
            Regex("(^|[-_])hi([-_.]|$)").containsMatchIn(normalized) || "hindi" in normalized -> DetectedVoskIdentity("Hindi", "hi-IN")
            Regex("(^|[-_])en([-_.]|$)").containsMatchIn(normalized) || "english" in normalized -> DetectedVoskIdentity("English", "en")
            else -> null
        }
    }

    fun validateExpected(requested: String, detected: DetectedVoskIdentity?): String? {
        if (detected == null || requested == "Auto / Hinglish" || requested == "Hinglish") return null
        val matches = when (requested) {
            "English" -> detected.languageCode.startsWith("en")
            "Hindi" -> detected.languageCode.startsWith("hi")
            else -> true
        }
        return if (matches) null else "Model language mismatch: ${detected.language} archive cannot be registered as $requested. Choose the matching language and import again."
    }

    fun stableId(originalName: String, languageCode: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(originalName.lowercase().toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }
        val prefix = languageCode.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "model" }
        return "$prefix-$digest"
    }
}

class VoskModelStore(
    private val context: Context,
    private val storageRoot: File = context.filesDir,
    preferencesName: String = "vosk_models",
    private val migrateLegacy: Boolean = storageRoot == context.filesDir,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val legacyPreferences = context.getSharedPreferences("vosk_model", Context.MODE_PRIVATE)
    private val modelsRoot = File(storageRoot, "vosk-models")
    private val legacyTarget = File(storageRoot, "vosk-model")

    init {
        modelsRoot.mkdirs()
        if (migrateLegacy) migrateLegacyModelIfNeeded()
    }

    fun catalog(): ModelCatalog {
        val models = modelIds().mapNotNull(::metadata).sortedBy { it.displayName.lowercase() }
        val selectedId = selectedModelId()
        return ModelCatalog(models, selectedId, selectedId?.let(::metadata))
    }

    fun allModels(): List<InstalledModel> = catalog().models
    fun selectedModelId(): String? = preferences.getString(KEY_SELECTED_ID, null)
    fun selectedModelMetadata(): InstalledModel? = selectedModelId()?.let(::metadata)
    fun installedModel(): InstalledModel? = selectedModelMetadata()?.takeIf { it.validationStatus == ModelValidationStatus.VALID }

    fun resolve(modelId: String?): InstalledModel? {
        if (modelId == null) return null
        return metadata(modelId)?.takeIf { it.validationStatus == ModelValidationStatus.VALID }
    }

    fun selectModel(modelId: String): InstalledModel? {
        val model = resolve(modelId) ?: return null
        preferences.edit().putString(KEY_SELECTED_ID, modelId).apply()
        return model
    }

    suspend fun importZip(uri: Uri, expectedLanguage: String, onProgress: (Float) -> Unit): InstalledModel {
        val size = querySize(uri)
        if (size == 0L) throw AudioPipelineException("The selected model archive is empty.")
        if (size > AudioLimits.MAX_MODEL_ARCHIVE_BYTES) throw AudioPipelineException("The model archive exceeds the 250 MB import limit.")
        val originalName = archiveName(uri)
        var detected = VoskModelIdentity.detect(originalName)
        VoskModelIdentity.validateExpected(expectedLanguage, detected)?.let { throw AudioPipelineException(it) }
        val staging = File(storageRoot, "vosk-model-importing")
        staging.deleteRecursively(); staging.mkdirs()
        var extracted = 0L; var entries = 0
        try {
            val source = context.contentResolver.openInputStream(uri) ?: throw AudioPipelineException("The selected model archive cannot be opened.")
            ZipInputStream(source.buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (++entries > 10_000) throw AudioPipelineException("The model archive contains too many files.")
                    val output = File(staging, entry.name)
                    val safeRoot = staging.canonicalPath + File.separator
                    if (!output.canonicalPath.startsWith(safeRoot)) throw AudioPipelineException("The model archive contains an unsafe path.")
                    if (entry.isDirectory) output.mkdirs() else {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).use { sink ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = zip.read(buffer)
                                if (read < 0) break
                                extracted += read
                                if (extracted > AudioLimits.MAX_MODEL_EXTRACTED_BYTES) throw AudioPipelineException("The extracted model exceeds the 350 MB safety limit.")
                                sink.write(buffer, 0, read)
                                onProgress(if (size > 0) (extracted.toFloat() / (size * 3f)).coerceIn(0f, .95f) else .5f)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val root = staging.walkTopDown().firstOrNull(::isValidModel)
                ?: throw AudioPipelineException("This ZIP is not a valid Vosk model. Expected non-empty am/final.mdl and conf/model.conf.")
            if (detected == null) detected = detectFromReadme(root)
            VoskModelIdentity.validateExpected(expectedLanguage, detected)?.let { throw AudioPipelineException(it) }
            val identity = detected ?: identityFromExpected(expectedLanguage)
            val id = VoskModelIdentity.stableId(originalName, identity.languageCode)
            val target = File(modelsRoot, id)
            if (!isValidModel(target)) {
                if (target.exists()) target.deleteRecursively()
                if (!root.renameTo(target) && !root.copyRecursively(target, overwrite = false)) {
                    throw AudioPipelineException("The model could not be moved into private app storage.")
                }
            }
            if (!isValidModel(target)) throw AudioPipelineException("The imported model failed validation.")
            val model = InstalledModel(
                id = id,
                directory = target.absolutePath,
                displayName = originalName,
                language = identity.language,
                languageCode = identity.languageCode,
                version = originalName,
                originalName = originalName,
                importedAtEpochMs = System.currentTimeMillis(),
                validationStatus = ModelValidationStatus.VALID,
                pathIdentifier = "vosk-models/$id",
            )
            save(model)
            preferences.edit().putString(KEY_SELECTED_ID, id).apply()
            onProgress(1f)
            return model
        } catch (error: CancellationException) { throw error }
        catch (error: AudioPipelineException) { throw error }
        catch (error: SecurityException) { throw AudioPipelineException("Permission to read the model archive was lost. Select it again.", error) }
        catch (error: Exception) { throw AudioPipelineException("The model archive is corrupt or could not be imported.", error) }
        finally { staging.deleteRecursively() }
    }

    fun deleteModel(modelId: String? = selectedModelId()) {
        if (modelId == null) return
        File(modelsRoot, modelId).deleteRecursively()
        // Keep identity metadata and selection so consumers report unavailable instead of falling back.
    }

    private fun save(model: InstalledModel) {
        val json = JSONObject()
            .put("id", model.id)
            .put("displayName", model.displayName)
            .put("language", model.language)
            .put("languageCode", model.languageCode)
            .put("version", model.version)
            .put("originalName", model.originalName)
            .put("importedAt", model.importedAtEpochMs)
            .put("directoryName", model.id)
        val ids = modelIds().toMutableSet().apply { add(model.id) }
        preferences.edit().putStringSet(KEY_IDS, ids).putString(KEY_MODEL_PREFIX + model.id, json.toString()).apply()
    }

    private fun metadata(id: String): InstalledModel? {
        val raw = preferences.getString(KEY_MODEL_PREFIX + id, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val directoryName = json.optString("directoryName", id)
            val directory = File(modelsRoot, directoryName)
            val status = when { !directory.exists() -> ModelValidationStatus.MISSING; isValidModel(directory) -> ModelValidationStatus.VALID; else -> ModelValidationStatus.INVALID }
            InstalledModel(
                id = id,
                directory = directory.absolutePath,
                displayName = json.optString("displayName", id),
                language = json.optString("language", "Unknown"),
                languageCode = json.optString("languageCode", "und"),
                version = json.optString("version", "Imported"),
                originalName = json.optString("originalName", json.optString("displayName", id)),
                importedAtEpochMs = json.optLong("importedAt", 0L),
                validationStatus = status,
                pathIdentifier = "vosk-models/$directoryName",
            )
        } catch (_: Exception) { null }
    }

    private fun migrateLegacyModelIfNeeded() {
        if (!isValidModel(legacyTarget)) return
        val originalName = legacyPreferences.getString("name", "Imported Vosk model") ?: "Imported Vosk model"
        val detected = VoskModelIdentity.detect(originalName) ?: detectFromReadme(legacyTarget)
            ?: identityFromExpected(legacyPreferences.getString("language", "Auto / Hinglish") ?: "Auto / Hinglish")
        val id = VoskModelIdentity.stableId(originalName, detected.languageCode)
        val target = File(modelsRoot, id)
        if (!target.exists() && !legacyTarget.renameTo(target)) {
            if (!legacyTarget.copyRecursively(target, overwrite = false)) return
        }
        if (!isValidModel(target)) return
        save(InstalledModel(id, target.absolutePath, originalName, detected.language, detected.languageCode, originalName, originalName, System.currentTimeMillis(), ModelValidationStatus.VALID, "vosk-models/$id"))
        if (selectedModelId() == null) preferences.edit().putString(KEY_SELECTED_ID, id).apply()
        if (legacyTarget.exists() && target.exists()) legacyTarget.deleteRecursively()
    }

    private fun modelIds(): Set<String> = preferences.getStringSet(KEY_IDS, emptySet())?.toSet().orEmpty()
    private fun isValidModel(directory: File) = File(directory, "am/final.mdl").let { it.isFile && it.length() > 0 } && File(directory, "conf/model.conf").let { it.isFile && it.length() > 0 }
    private fun detectFromReadme(root: File): DetectedVoskIdentity? = root.walkTopDown().firstOrNull { it.isFile && it.name.equals("README", true) }
        ?.let { runCatching { it.bufferedReader().use { reader -> reader.readLine().orEmpty() } }.getOrNull() }
        ?.let(VoskModelIdentity::detect)
    private fun identityFromExpected(expected: String) = when (expected) {
        "Hindi" -> DetectedVoskIdentity("Hindi", "hi-IN")
        "English" -> DetectedVoskIdentity("English", "en")
        else -> DetectedVoskIdentity("Unknown / experimental", "und")
    }
    private fun archiveName(uri: Uri): String {
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()
        return (displayName ?: uri.lastPathSegment ?: "Imported Vosk model")
            .substringAfterLast('/').substringAfterLast(':').removeSuffix(".zip")
    }
    private fun querySize(uri: Uri): Long {
        runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0) }
        }
        return -1
    }

    companion object {
        private const val KEY_IDS = "model_ids"
        private const val KEY_SELECTED_ID = "selected_model_id"
        private const val KEY_MODEL_PREFIX = "model_"
    }
}
