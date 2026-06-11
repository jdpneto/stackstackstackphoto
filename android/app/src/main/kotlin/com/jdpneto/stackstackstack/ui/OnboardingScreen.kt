package com.jdpneto.stackstackstack.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.jdpneto.stackstackstack.AppSettings

/**
 * The camera page's tri-state: the system prompt is reachable ("Enable Camera") unless the
 * prompt has been shown before AND the permission is still missing — only then is the user's
 * answer a real denial that requires the Settings deep-link. Pure logic, unit-tested.
 */
internal fun shouldShowOpenSettings(requestedBefore: Boolean, permissionGranted: Boolean): Boolean =
    requestedBefore && !permissionGranted

// ---------------------------------------------------------------------------
// Onboarding page model (mirrors iOS OnboardingPage)
// ---------------------------------------------------------------------------

private data class OnboardingPage(
    val id: String,
    val emoji: String,
    val tint: Color,
    val title: String,
    val what: String,
    val `when`: String
)

private val looks = listOf(
    OnboardingPage("detail", "✨", Color.Cyan, "Detail",
        "Stacks a burst into one clean, low-noise shot.",
        "Everyday shots, low light, anywhere you want maximum quality."),
    OnboardingPage("smooth", "🌊", Color.Blue, "Smooth",
        "Averages motion into silky blur while still things stay sharp.",
        "Waterfalls, rivers, clouds, busy crowds."),
    OnboardingPage("trails", "🚗", Color(0xFFFF9500), "Trails",
        "Keeps the bright paths moving lights leave behind.",
        "Night traffic, fairground rides, sparklers."),
    OnboardingPage("night", "🌙", Color(0xFF5856D6), "Night",
        "Stacks and brightens a dark scene without the noise.",
        "Dusk, dim rooms, city nights."),
    OnboardingPage("depth", "🔭", Color.Green, "Depth",
        "Sweeps focus near→far and keeps the sharpest of each.",
        "Close subjects with a background you also want sharp.")
)

// ---------------------------------------------------------------------------
// OnboardingScreen
// ---------------------------------------------------------------------------

/**
 * First-launch onboarding pager: welcome → the five looks → camera permission page.
 * Skippable everywhere; finishing or skipping calls [onFinish].
 *
 * Camera page requests [Manifest.permission.CAMERA] at runtime. The tri-state matters:
 * `checkSelfPermission == PERMISSION_DENIED` is ALSO true on a fresh install (never-asked), so
 * "Open Settings" only shows when the prompt was actually shown before and the user denied —
 * [AppSettings.cameraPermissionRequested] && still denied — mirroring iOS
 * `authorizationStatus == .denied` (vs `.notDetermined` → "Enable Camera" runtime prompt).
 *
 * The page indicator lives in its own row (not overlapping the page content) so the iOS-specific
 * compact-height overlap bug class can't occur on Android.
 *
 * Test-tags: `onboarding-skip`, `onboarding-enable-camera`, `onboarding-done`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(settings: AppSettings, onFinish: () -> Unit) {
    val context = LocalContext.current
    val pageCount = 2 + looks.size   // welcome + looks + camera
    val pagerState = rememberPagerState { pageCount }

    // Camera permission state — re-checked on page change so the camera page is always fresh.
    var cameraDenied by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage) {
        cameraDenied = shouldShowOpenSettings(
            requestedBefore   = settings.cameraPermissionRequested,
            permissionGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // The prompt has now been shown once — a future DENIED check means a real user denial.
        settings.cameraPermissionRequested = true
        // Continue regardless of the answer (mirrors iOS `AVCaptureDevice.requestAccess { _ in finish() }`).
        onFinish()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Skip button (top-right, always visible).
        Box(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = onFinish,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp)
                    .testTag("onboarding-skip")
                    .semantics { contentDescription = "onboarding-skip" }
            ) { Text("Skip", color = Color.White.copy(alpha = 0.8f)) }
        }

        // Pager (fills available space above the indicator row).
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            when (page) {
                0 -> PageScaffold(
                    emoji = "📚",
                    tint = Color.White,
                    title = "Stack Stack Stack",
                    line1 = "Shoot a short handheld burst; the app aligns and stacks it into a shot one frame can't make.",
                    line2 = "Everything runs on your phone. No cloud, no account."
                )
                pageCount - 1 -> CameraPage(
                    cameraDenied = cameraDenied,
                    onEnableCamera = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    onDone = onFinish
                )
                else -> {
                    val look = looks[page - 1]
                    PageScaffold(
                        emoji = look.emoji,
                        tint = look.tint,
                        title = look.title,
                        line1 = look.what,
                        line2 = look.`when`
                    )
                }
            }
        }

        // Page indicator in its own row — no overlap with content.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(pageCount) { i ->
                val selected = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (selected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (selected) Color.White else Color.White.copy(alpha = 0.4f))
                )
            }
        }
    }
}

@Composable
private fun PageScaffold(
    emoji: String,
    tint: Color,
    title: String,
    line1: String,
    line2: String,
    footer: @Composable () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 0.dp)
            .padding(bottom = 36.dp),   // keep clear of the indicator row
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        // Stylised art placeholder (emoji + gradient rounded rect).
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(tint.copy(alpha = 0.55f), Color.Black)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, fontSize = 64.sp)
        }

        Spacer(Modifier.height(20.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(line1, color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(line2, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, textAlign = TextAlign.Center)
        footer()
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun CameraPage(
    cameraDenied: Boolean,
    onEnableCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onDone: () -> Unit
) {
    PageScaffold(
        emoji = "📷",
        tint = Color.Yellow,
        title = "One thing first",
        line1 = "Stack Stack Stack is a camera — it needs camera access to shoot.",
        line2 = "Nothing is captured until you press the shutter."
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 16.dp)
        ) {
            if (cameraDenied) {
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow, contentColor = Color.Black)
                ) { Text("Open Settings") }
            } else {
                Button(
                    onClick = onEnableCamera,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow, contentColor = Color.Black),
                    modifier = Modifier
                        .testTag("onboarding-enable-camera")
                        .semantics { contentDescription = "onboarding-enable-camera" }
                ) { Text("Enable Camera") }
            }
            TextButton(
                onClick = onDone,
                modifier = Modifier
                    .testTag("onboarding-done")
                    .semantics { contentDescription = "onboarding-done" }
            ) { Text("Done", color = Color.White.copy(alpha = 0.6f)) }
        }
    }
}
