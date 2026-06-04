import XCTest

final class StackFlowUITests: XCTestCase {
    func testTapShutterProducesAStack() {
        let app = XCUIApplication()
        app.launch()

        let shutter = app.buttons["shutter"]
        XCTAssertTrue(shutter.waitForExistence(timeout: 10), "shutter button not found")
        shutter.tap()

        // The status label becomes "Done" once capture → align → stack → encode → save finishes.
        let done = app.staticTexts["Done"]
        XCTAssertTrue(done.waitForExistence(timeout: 60), "stack did not complete")

        // Save the result screen as a test attachment so we can extract it.
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = "result-screen"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
