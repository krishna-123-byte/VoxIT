package com.voxit.app

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.voxit.app", appContext.packageName)
    }

    @Test
    fun officialBrandAssetsAreCroppedAndTransparent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.voxit_logo_full)
        assertNotNull(logo)
        assertTrue(logo.width < 1254)
        assertTrue(logo.height < 500)
        assertEquals(0, logo.getPixel(0, 0) ushr 24)

        val notification = BitmapFactory.decodeResource(context.resources, R.drawable.ic_stat_voxit)
        assertNotNull(notification)
        assertEquals(0, notification.getPixel(0, 0) ushr 24)
        assertTrue((0 until notification.width).any { x -> (0 until notification.height).any { y -> notification.getPixel(x, y) ushr 24 > 0 } })
    }
}
