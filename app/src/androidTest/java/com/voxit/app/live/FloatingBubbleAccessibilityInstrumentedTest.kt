package com.voxit.app.live

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingBubbleAccessibilityInstrumentedTest {
    @Test
    fun performClickInvokesTheBubbleAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var clicks = 0

        instrumentation.runOnMainSync {
            val badge = AccessibleBubbleFrameLayout(instrumentation.targetContext)
            badge.setOnClickListener { clicks += 1 }

            assertTrue(badge.performClick())
        }

        assertEquals(1, clicks)
    }
}
