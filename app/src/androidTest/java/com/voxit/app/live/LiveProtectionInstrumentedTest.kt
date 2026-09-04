package com.voxit.app.live

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voxit.app.phase2.VoskModelStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveProtectionInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun manifestContainsOnlyExpectedSensitivePermissionsAndMicrophoneServiceType() {
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.RECORD_AUDIO in requested)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in requested)
        assertTrue(Manifest.permission.SYSTEM_ALERT_WINDOW in requested)
        assertFalse(Manifest.permission.READ_CALL_LOG in requested)
        assertFalse(Manifest.permission.READ_CONTACTS in requested)
        assertFalse(Manifest.permission.READ_SMS in requested)
        assertFalse(Manifest.permission.READ_PHONE_STATE in requested)
        assertFalse(Manifest.permission.CAPTURE_AUDIO_OUTPUT in requested)
        val service = context.packageManager.getServiceInfo(ComponentName(context, LiveProtectionService::class.java), 0)
        if (Build.VERSION.SDK_INT >= 29) assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
    }

    @Test fun overlayDenialAndRemovalAreSafe() {
        val controller = FloatingBubbleController(context) { }
        val shown = controller.show()
        if (!android.provider.Settings.canDrawOverlays(context)) assertFalse(shown)
        controller.hide(); controller.hide()
        assertFalse(controller.isShowing())
    }

    @Test fun missingLiveModelIsReportedWithoutDemoFallback() {
        val root = java.io.File(context.cacheDir, "missing-live-model").apply { deleteRecursively(); mkdirs() }
        val installed = try { VoskModelStore(context, root, "missing-live-model-test", migrateLegacy = false).installedModel() } finally {
            root.deleteRecursively(); context.getSharedPreferences("missing-live-model-test", 0).edit().clear().commit()
        }
        assertNull(installed)
        val state = LiveProtectionState(status = LiveSessionStatus.LISTENING, transcriptionStatus = "Transcription model not installed")
        assertTrue(state.transcriptionStatus.contains("not installed"))
        assertFalse(state.transcriptionStatus.contains("demo", true))
    }

    @Test fun partialAndFinalVoskJsonAreSeparatedAndRedacted() {
        assertEquals("tell me otp", LiveVoskResultParser.partial("""{"partial":"tell me otp"}"""))
        val final = LiveVoskResultParser.final("""{"text":"otp is 123456","result":[{"start":1.2,"end":1.5},{"start":1.6,"end":2.0}]}""", "English")!!
        assertTrue(final.confirmed)
        assertEquals(1_200, final.startMs)
        assertEquals(2_000, final.endMs)
        assertFalse(final.text.contains("123456"))
    }

    @Test fun boundedPcmMemoryDoesNotCreateRawAudioFiles() {
        val before = context.filesDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(context.filesDir).path }.toSet()
        val ring = FloatRingBuffer(32_000)
        repeat(20) { ring.add(FloatArray(1_600) { .1f }) }
        assertEquals(32_000, ring.size)
        ring.clear()
        assertEquals(0, ring.size)
        val after = context.filesDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(context.filesDir).path }.toSet()
        assertEquals(before, after)
    }

    @Test fun liveCaptureCanBeReplacedByATestFactoryWithoutAProductionUiSwitch() {
        val fake = object : LiveAudioSource {
            override val sourceSampleRate = 16_000
            override suspend fun start() = Unit
            override suspend fun readFrame(): FloatArray = FloatArray(320)
            override suspend fun pause() = Unit
            override suspend fun resume() = Unit
            override fun close() = Unit
        }
        try {
            LiveAudioSourceProvider.installForTests(LiveAudioSourceFactory { fake })
            assertSame(fake, LiveAudioSourceProvider.create(context))
        } finally {
            LiveAudioSourceProvider.installForTests(null)
        }
        assertTrue(LiveAudioSourceProvider.create(context) is MicrophoneAudioSource)
    }
}
