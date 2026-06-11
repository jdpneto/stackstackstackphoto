package com.jdpneto.stackstackstack

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Entry point. B1 scaffold only — no Compose UI yet (screens added in B3).
 * The activity structure matches the iOS [StackStackStackApp]: settings, coordinator, and
 * library are wired here once and passed down.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // B3 will set the Compose content here.
    }
}
