package com.voxit.app.integrity

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.net.Uri
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class IntegrityModelInstrumentedTest {
    @Test fun missingModelIsExplicitAndNeverARealScore() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "integrity-missing-${System.nanoTime()}").apply { mkdirs() }
        try {
            val state = IntegrityModelStore(context, root).state()
            assertEquals(IntegrityModelStatus.NOT_INSTALLED, state.status)
            assertEquals("Voice-integrity model not installed", state.message)
        } finally { root.deleteRecursively() }
    }

    @Test fun modelIdentityAndChecksumArePinned() {
        assertEquals("aasist-l-asvspoof2019-la-v1", IntegrityModelStore.MODEL_ID)
        assertEquals(64, IntegrityModelStore.EXPECTED_ONNX_SHA256.length)
        assertTrue(IntegrityModelStore.SOURCE.startsWith("https://github.com/clovaai/aasist@"))
    }

    @Test fun corruptModelIsRejectedAndDeletionLeavesUnavailableState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "integrity-corrupt-${System.nanoTime()}").apply { mkdirs() }
        val corrupt = File(root, "aasist-l.onnx").apply { writeBytes(ByteArray(512) { it.toByte() }) }
        try {
            val store = IntegrityModelStore(context, root)
            val imported = store.importModel(Uri.fromFile(corrupt)) { }
            assertEquals(IntegrityModelStatus.INCOMPATIBLE, imported.status)
            assertNull(store.modelFileOrNull())
            store.delete()
            assertEquals(IntegrityModelStatus.NOT_INSTALLED, store.state().status)
        } finally { root.deleteRecursively() }
    }
}
