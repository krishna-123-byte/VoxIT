package com.voxit.app.phase2

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ModelSwitchingInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val created = mutableListOf<File>()
    private val preferenceNames = mutableListOf<String>()

    @After fun cleanup() {
        created.forEach(File::deleteRecursively)
        preferenceNames.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }

    @Test fun hindiThenEnglishUsesDifferentStableDirectoriesAndPersistsSelection() = runBlocking {
        val store = store("hi-en")
        val hindi = store.importZip(modelZip("vosk-model-small-hi-0.22", "Hindi small model for Vosk"), "Hindi") { }
        val english = store.importZip(modelZip("vosk-model-small-en-us-0.15", "English small model for Vosk"), "English") { }

        assertNotEquals(hindi.id, english.id)
        assertNotEquals(hindi.directory, english.directory)
        assertEquals(english.id, store.selectedModelId())
        assertEquals(english.directory, store.installedModel()?.directory)
        assertEquals(2, store.allModels().size)

        assertEquals(hindi.id, store.selectModel(hindi.id)?.id)
        assertEquals(hindi.directory, store.installedModel()?.directory)
        assertEquals(english.id, store.selectModel(english.id)?.id)
        assertEquals(english.directory, store.installedModel()?.directory)

        val reopened = reopen("hi-en")
        assertEquals(english.id, reopened.selectedModelId())
        assertEquals(english.directory, reopened.installedModel()?.directory)
    }

    @Test fun englishThenHindiSelectionResolvesExactModelWithoutFallback() = runBlocking {
        val store = store("en-hi")
        val english = store.importZip(modelZip("vosk-model-small-en-us-0.15", "English small model for Vosk"), "English") { }
        val hindi = store.importZip(modelZip("vosk-model-small-hi-0.22", "Hindi small model for Vosk"), "Hindi") { }

        assertEquals(hindi.id, store.selectedModelId())
        assertEquals(hindi.directory, store.resolve(hindi.id)?.directory)
        assertEquals(english.directory, store.selectModel(english.id)?.directory)
        assertEquals(english.id, store.selectedModelId())
        assertEquals(english.directory, store.resolve(store.selectedModelId())?.directory)
    }

    @Test fun deletedOrCorruptSelectedModelIsUnavailableAndDoesNotFallback() = runBlocking {
        val store = store("unavailable")
        val english = store.importZip(modelZip("vosk-model-small-en-us-0.15", "English small model for Vosk"), "English") { }
        val hindi = store.importZip(modelZip("vosk-model-small-hi-0.22", "Hindi small model for Vosk"), "Hindi") { }

        store.selectModel(english.id)
        store.deleteModel(english.id)
        assertEquals(english.id, store.selectedModelId())
        assertNull(store.installedModel())
        assertEquals(ModelValidationStatus.MISSING, store.selectedModelMetadata()?.validationStatus)
        assertNotEquals(hindi.id, store.installedModel()?.id)

        store.selectModel(hindi.id)
        File(hindi.directory, "conf/model.conf").writeText("")
        assertNull(store.installedModel())
        assertEquals(ModelValidationStatus.INVALID, store.selectedModelMetadata()?.validationStatus)
    }

    @Test fun knownArchiveLanguageMismatchIsRejectedWithoutDeletingExistingModel() = runBlocking {
        val store = store("mismatch")
        val english = store.importZip(modelZip("vosk-model-small-en-us-0.15", "English small model for Vosk"), "English") { }
        val error = runCatching {
            store.importZip(modelZip("vosk-model-small-hi-0.22", "Hindi small model for Vosk"), "English") { }
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("language mismatch", ignoreCase = true))
        assertEquals(english.id, store.selectedModelId())
        assertEquals(english.directory, store.installedModel()?.directory)
        assertTrue(File(english.directory).isDirectory)
    }

    private fun store(label: String): VoskModelStore {
        val root = File(context.cacheDir, "model-switch-$label").apply { deleteRecursively(); mkdirs() }
        created += root
        val preferences = "model-switch-$label-${System.nanoTime()}"
        preferenceNames += preferences
        labels[label] = root to preferences
        return VoskModelStore(context, root, preferences, migrateLegacy = false)
    }

    private fun reopen(label: String): VoskModelStore {
        val (root, preferences) = labels.getValue(label)
        return VoskModelStore(context, root, preferences, migrateLegacy = false)
    }

    private fun modelZip(name: String, readme: String): Uri {
        val zip = File(context.cacheDir, "$name-${System.nanoTime()}.zip")
        created += zip
        ZipOutputStream(FileOutputStream(zip)).use { output ->
            fun entry(path: String, bytes: ByteArray) {
                output.putNextEntry(ZipEntry("$name/$path")); output.write(bytes); output.closeEntry()
            }
            entry("am/final.mdl", byteArrayOf(1, 2, 3))
            entry("conf/model.conf", "--sample-frequency=16000".toByteArray())
            entry("README", readme.toByteArray())
        }
        return Uri.fromFile(zip)
    }

    companion object { private val labels = mutableMapOf<String, Pair<File, String>>() }
}
