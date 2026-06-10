import XCTest

final class StackFlowUITests: XCTestCase {

    /// Onboarding may cover the UI on a fresh simulator install — dismiss it before flow tests.
    private func dismissOnboardingIfPresent(_ app: XCUIApplication) {
        if app.buttons["onboarding-skip"].waitForExistence(timeout: 2) { app.buttons["onboarding-skip"].tap() }
    }

    func testTapShutterProducesAStack() throws {
        #if !targetEnvironment(simulator)
        throw XCTSkip("Relies on the Simulator fake-capture path; the device camera path needs permissions and real hardware.")
        #endif
        let app = XCUIApplication()
        app.launch()
        dismissOnboardingIfPresent(app)

        let shutter = app.buttons["shutter"]
        XCTAssertTrue(shutter.waitForExistence(timeout: 10), "shutter button not found")
        shutter.tap()

        // The status label becomes "Saved ✓" once capture → align → stack → encode → save finishes.
        let done = app.staticTexts["Saved ✓"]
        XCTAssertTrue(done.waitForExistence(timeout: 60), "stack did not complete")

        // Save the result screen as a test attachment so we can extract it.
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = "result-screen"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testEditorOpensAfterCapture() throws {
        #if !targetEnvironment(simulator)
        throw XCTSkip("Relies on the Simulator fake-capture path; the device camera path needs permissions and real hardware.")
        #endif
        let app = XCUIApplication()
        app.launch()
        dismissOnboardingIfPresent(app)

        let shutter = app.buttons["shutter"]
        XCTAssertTrue(shutter.waitForExistence(timeout: 10), "shutter button not found")
        shutter.tap()
        XCTAssertTrue(app.staticTexts["Saved ✓"].waitForExistence(timeout: 60), "stack did not complete")

        let edit = app.buttons["Edit"]
        XCTAssertTrue(edit.waitForExistence(timeout: 5), "Edit button not found")
        edit.tap()

        // The editor sheet shows the adjustment sliders + Save.
        XCTAssertTrue(app.buttons["Save"].waitForExistence(timeout: 10), "editor did not open")
        XCTAssertTrue(app.staticTexts["Exposure"].exists)

        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = "editor-screen"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testGalleryOpensAStackWithActions() throws {
        #if !targetEnvironment(simulator)
        throw XCTSkip("Relies on the Simulator fake-capture path.")
        #endif
        let app = XCUIApplication()
        app.launch()
        dismissOnboardingIfPresent(app)

        // Capture one stack so the gallery isn't empty.
        let shutter = app.buttons["shutter"]
        XCTAssertTrue(shutter.waitForExistence(timeout: 10), "shutter button not found")
        shutter.tap()
        XCTAssertTrue(app.staticTexts["Saved ✓"].waitForExistence(timeout: 60), "stack did not complete")

        // Switch to the Gallery tab and tap the stack — it should open the full-screen viewer.
        app.tabBars.buttons["Gallery"].tap()
        let cell = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'stack-'")).firstMatch
        XCTAssertTrue(cell.waitForExistence(timeout: 10), "no stack cell in the gallery")
        cell.tap()

        // The viewer exposes Done / Share / Edit / Delete.
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5), "detail viewer did not open")
        XCTAssertTrue(app.buttons["Share"].exists, "Share action missing")
        XCTAssertTrue(app.buttons["Edit"].exists, "Edit action missing")
        XCTAssertTrue(app.buttons["Delete"].exists, "Delete action missing")
    }

    func testLightTrailsLookProducesAResult() throws {
        #if !targetEnvironment(simulator)
        throw XCTSkip("Relies on the Simulator fake-capture path.")
        #endif
        let app = XCUIApplication()
        app.launch()
        dismissOnboardingIfPresent(app)
        let trails = app.buttons["look-lightTrails"]
        XCTAssertTrue(trails.waitForExistence(timeout: 10), "Trails look chip not found")
        trails.tap()
        let shutter = app.buttons["shutter"]
        XCTAssertTrue(shutter.waitForExistence(timeout: 5))
        shutter.tap()
        XCTAssertTrue(app.staticTexts["Saved ✓"].waitForExistence(timeout: 90), "light-trails stack did not complete")

        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = "trails-result"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testDepthLookProducesASavedStack() throws {
        #if !targetEnvironment(simulator)
        throw XCTSkip("Relies on the Simulator fake-capture path.")
        #endif
        let app = XCUIApplication()
        app.launch()
        dismissOnboardingIfPresent(app)

        let depth = app.buttons["look-depthOfField"]
        XCTAssertTrue(depth.waitForExistence(timeout: 10), "Depth look chip not found")
        depth.tap()
        let shutter = app.buttons["shutter"]
        XCTAssertTrue(shutter.waitForExistence(timeout: 5), "shutter button not found")
        shutter.tap()
        // Capture (10 fake brackets) + background focus stack — generous timeout for CI simulators.
        XCTAssertTrue(app.staticTexts["Saved ✓"].waitForExistence(timeout: 60),
                      "Depth shoot must produce a saved stack")
    }

    func testFreshInstallShowsOnboardingAndSkipLandsOnCapture() throws {
        #if !targetEnvironment(simulator)
        throw XCTSkip("Relies on the Simulator fake-capture path.")
        #endif
        let app = XCUIApplication()
        app.launchArguments += ["-resetOnboarding"]
        app.launch()
        XCTAssertTrue(app.buttons["onboarding-skip"].waitForExistence(timeout: 10),
                      "fresh install must show the onboarding cover")
        app.buttons["onboarding-skip"].tap()
        XCTAssertTrue(app.buttons["shutter"].waitForExistence(timeout: 10), "skip lands on Capture")
    }

    func testOnboardingDoesNotReappearAfterSkip() throws {
        #if !targetEnvironment(simulator)
        throw XCTSkip("Relies on the Simulator fake-capture path.")
        #endif
        // Make this test self-sufficient: reset, skip, terminate, relaunch — no onboarding.
        let app = XCUIApplication()

        // Phase 1: reset and skip onboarding to persist hasSeenOnboarding = true.
        app.launchArguments = ["-resetOnboarding"]
        app.launch()
        XCTAssertTrue(app.buttons["onboarding-skip"].waitForExistence(timeout: 10),
                      "onboarding must appear after reset")
        app.buttons["onboarding-skip"].tap()
        XCTAssertTrue(app.buttons["shutter"].waitForExistence(timeout: 10), "shutter must appear after skip")
        app.terminate()

        // Phase 2: relaunch without the reset arg — onboarding must NOT appear.
        app.launchArguments = []
        app.launch()
        XCTAssertTrue(app.buttons["shutter"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["onboarding-skip"].exists,
                       "onboarding must not reappear after it has been seen")
    }
}
