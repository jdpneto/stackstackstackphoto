import XCTest

/// Live on-DEVICE tests: exercise the REAL camera path (AVCaptureService RAW burst →
/// RawFrameConverter → align → develop → stack → encode → save) that the Simulator can't run.
/// Screenshots are attached so the actual device output can be inspected.
final class DeviceCameraTests: XCTestCase {
    override func setUp() { continueAfterFailure = true }

    /// Tap the system camera-permission alert (Springboard) if/when it appears — deterministic,
    /// unlike an interruption monitor which only fires on the next in-app interaction.
    private func grantCameraIfAsked(timeout: TimeInterval = 10) {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow While Using App", "Allow", "OK", "Allow Once"] {
            let button = springboard.buttons[label]
            if button.waitForExistence(timeout: timeout) { button.tap(); return }
        }
    }

    private func snap(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let att = XCTAttachment(screenshot: shot)
        att.name = name
        att.lifetime = .keepAlways
        add(att)
    }

    func testRealCameraCaptureAndEditFlow() throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("Exercises the real device camera; run on hardware.")
        #endif
        let app = XCUIApplication()
        app.launch()

        // The capture screen should render on the device.
        let shutter = app.buttons["shutter"]
        XCTAssertTrue(shutter.waitForExistence(timeout: 20), "capture screen / shutter not found on device")
        snap("01-capture-screen")

        // Default look is Detail (8-frame noise-reduction). Fire the REAL burst.
        shutter.tap()
        grantCameraIfAsked()   // grant the camera permission alert if this is first launch

        // Real RAW capture + full-resolution develop/align/stack on the CPU is slow (no Metal yet) —
        // allow a generous window. We assert the flow COMPLETES (no crash / no hang).
        let done = app.staticTexts["Done"]
        let finished = done.waitForExistence(timeout: 300)
        snap("02-after-capture")   // whatever state we're in (Done, or still Stacking…)
        XCTAssertTrue(finished, "capture→stack did not reach Done within 300s on device")

        // Open the editor on the real stacked result and adjust exposure.
        let edit = app.buttons["Edit"]
        if edit.waitForExistence(timeout: 10) {
            edit.tap()
            XCTAssertTrue(app.buttons["Save"].waitForExistence(timeout: 15), "editor did not open")
            snap("03-editor")
            // Nudge the first slider (Exposure) and save.
            let sliders = app.sliders
            if sliders.count > 0 { sliders.element(boundBy: 0).adjust(toNormalizedSliderPosition: 0.7) }
            snap("04-editor-adjusted")
            app.buttons["Save"].tap()
            XCTAssertTrue(shutter.waitForExistence(timeout: 30), "did not return to capture after save")
            snap("05-after-edit-saved")
        } else {
            snap("03-no-edit-button")
        }
    }

    func testProPanelRendersOnDevice() throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("Run on hardware.")
        #endif
        let app = XCUIApplication()
        app.launch()
        let pro = app.buttons["pro-toggle"]
        XCTAssertTrue(pro.waitForExistence(timeout: 20), "Pro toggle not found")
        pro.tap()
        snap("10-pro-panel")
        // The Gallery tab should also render.
        if app.buttons["Gallery"].waitForExistence(timeout: 5) {
            app.buttons["Gallery"].tap()
            snap("11-gallery")
        }
    }
}
