/*
 * ExampleInstrumentedTest.kt
 *
 * Purpose: Android Studio sample instrumented test that checks the app package name.
 * Role: Template test kept as a device sanity check.
 * Outstanding issues: None.
 */
package com.example.legion_events

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
        assertEquals("com.example.legion_events", appContext.packageName)
    }
}