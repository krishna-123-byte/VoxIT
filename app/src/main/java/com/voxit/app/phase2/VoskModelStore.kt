package com.voxit.app.phase2

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class VoskModelStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("vosk_model", Context.MODE_PRIVATE)
    private val target = File(context.filesDir, "vosk-model")

    fun installedModel(): InstalledModel? {
        if (!isValidModel(target)) return null
        return InstalledModel(target.absolutePath, preferences.getString("name", "Imported Vosk model")!!, preferences.getString("language", "Auto")!!, preferences.getString("version", "Imported")!!)
    }

    suspend fun importZip(uri: Uri, language: String, onProgress: (Float) -> Unit): InstalledModel {
        val size = querySize(uri)
        if (size == 0L) throw AudioPipelineException("The selected model archive is empty.")
        if (size > AudioLimits.MAX_MODEL_ARCHIVE_BYTES) throw AudioPipelineException("The model archive exceeds the 250 MB import limit.")
        val staging = File(context.filesDir, "vosk-model-importing")
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
            val root = staging.walkTopDown().firstOrNull(::isValidModel) ?: throw AudioPipelineException("This ZIP is not a valid Vosk model. Expected am/final.mdl and conf/model.conf.")
            target.deleteRecursively()
            if (!root.renameTo(target)) {
                if (!root.copyRecursively(target, overwrite = true)) throw AudioPipelineException("The model could not be moved into private app storage.")
            }
            if (!isValidModel(target)) throw AudioPipelineException("The imported model failed validation.")
            val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".zip") ?: "Imported Vosk model"
            preferences.edit().putString("name", name).putString("language", language).putString("version", "Vosk model archive").apply()
            onProgress(1f)
            return installedModel()!!
        } catch (error: CancellationException) { throw error }
        catch (error: AudioPipelineException) { throw error }
        catch (error: SecurityException) { throw AudioPipelineException("Permission to read the model archive was lost. Select it again.", error) }
        catch (error: Exception) { throw AudioPipelineException("The model archive is corrupt or could not be imported.", error) }
        finally { staging.deleteRecursively() }
    }

    fun deleteModel() { target.deleteRecursively(); preferences.edit().clear().apply() }

    private fun isValidModel(directory: File) = File(directory, "am/final.mdl").isFile && File(directory, "conf/model.conf").isFile
    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0) }
        return -1
    }
}
