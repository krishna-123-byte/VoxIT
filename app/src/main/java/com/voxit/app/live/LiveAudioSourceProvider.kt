package com.voxit.app.live

import android.content.Context
import androidx.annotation.VisibleForTesting

/** Keeps production capture real while allowing instrumentation to replace the source deterministically. */
object LiveAudioSourceProvider {
    @Volatile
    private var testFactory: LiveAudioSourceFactory? = null

    fun create(context: Context): LiveAudioSource =
        testFactory?.create() ?: MicrophoneAudioSource(context.applicationContext)

    @VisibleForTesting
    fun installForTests(factory: LiveAudioSourceFactory?) {
        testFactory = factory
    }
}
