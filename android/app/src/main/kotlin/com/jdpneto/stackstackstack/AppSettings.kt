package com.jdpneto.stackstackstack

import android.content.SharedPreferences

/**
 * The app's user preferences: a thin observable wrapper over [SharedPreferences] (three keys —
 * no settings framework). Mirrors the iOS [AppSettings] exactly: same keys, same defaults,
 * same format-fallback semantics (unknown/corrupt value → JPEG). (spec §3.1)
 *
 * Change listeners are not wired in B1 (no UI yet); the class is designed to be observed via
 * LiveData/StateFlow in B3.
 */
class AppSettings(private val prefs: SharedPreferences) {

    private object Keys {
        const val SAVE_TO_PHOTOS   = "saveToPhotos"
        const val EXPORT_FORMAT    = "exportFormat"
        const val HAS_SEEN_ONBOARDING = "hasSeenOnboarding"
    }

    /** Mirror every successful save into the system photo library (add-only). Opt-in. */
    var saveToPhotos: Boolean
        get() = prefs.getBoolean(Keys.SAVE_TO_PHOTOS, false)
        set(value) = prefs.edit().putBoolean(Keys.SAVE_TO_PHOTOS, value).apply()

    /**
     * Library/encode format for NEW captures (existing records keep their own format).
     * Unknown/corrupt value falls back to JPEG — matches iOS `flatMap(Format.init(rawValue:)) ?? .jpeg`.
     */
    var exportFormat: ImageEncoder.Format
        get() {
            val raw = prefs.getString(Keys.EXPORT_FORMAT, null)
            return ImageEncoder.Format.fromRawValue(raw) ?: ImageEncoder.Format.JPEG
        }
        set(value) = prefs.edit().putString(Keys.EXPORT_FORMAT, value.rawValue).apply()

    /** First-launch onboarding gate; "Replay Introduction" presents the flow without resetting this. */
    var hasSeenOnboarding: Boolean
        get() = prefs.getBoolean(Keys.HAS_SEEN_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(Keys.HAS_SEEN_ONBOARDING, value).apply()
}
