package com.jdpneto.stackstackstack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mirrors iOS [AppSettingsTests] 1:1 — same assertions, same semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppSettingsTest {

    /** A throwaway SharedPreferences so tests never touch the app's real defaults. */
    private fun makeSettings(): AppSettings {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("test-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
        return AppSettings(prefs)
    }

    @Test
    fun testDefaultsAreSafeOutOfTheBox() {
        val s = makeSettings()
        assertFalse("Photos export is opt-in", s.saveToPhotos)
        assertEquals("JPEG until the user opts into HEIC", ImageEncoder.Format.JPEG, s.exportFormat)
        assertFalse("fresh install shows onboarding", s.hasSeenOnboarding)
    }

    @Test
    fun testValuesRoundTripThroughPrefs() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val suiteName = "test-${java.util.UUID.randomUUID()}"
        val prefs = ctx.getSharedPreferences(suiteName, Context.MODE_PRIVATE)
        val s = AppSettings(prefs)
        s.saveToPhotos = true
        s.exportFormat = ImageEncoder.Format.HEIC
        s.hasSeenOnboarding = true

        // A second instance over the same prefs sees the persisted values.
        val prefs2 = ctx.getSharedPreferences(suiteName, Context.MODE_PRIVATE)
        val s2 = AppSettings(prefs2)
        assertTrue(s2.saveToPhotos)
        assertEquals(ImageEncoder.Format.HEIC, s2.exportFormat)
        assertTrue(s2.hasSeenOnboarding)
    }

    @Test
    fun testUnknownStoredFormatFallsBackToJPEG() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val suiteName = "test-${java.util.UUID.randomUUID()}"
        val prefs = ctx.getSharedPreferences(suiteName, Context.MODE_PRIVATE)
        prefs.edit().putString("exportFormat", "avif").apply()   // a future/corrupt value
        val s = AppSettings(prefs)
        assertEquals("unknown format must fall back to JPEG", ImageEncoder.Format.JPEG, s.exportFormat)
    }

    @Test
    fun testFormatRawValuesAreStableStorageKeys() {
        // These raw values are the persisted contract — NEVER rename.
        assertEquals("jpeg", ImageEncoder.Format.JPEG.rawValue)
        assertEquals("heic", ImageEncoder.Format.HEIC.rawValue)
    }
}
