// SPDX-License-Identifier: AGPL-3.0-only
import XCTest

/// Requires Xcode/iOS simulator. These controls use an empty real-data state, never sample stations.
final class OpenFuelUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }
    private func app() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "-AppleLanguages", "(en)", "-AppleLocale", "en_CA"]
        app.launch(); return app
    }
    func testStationSheetHidesCompletelyAndCanBeRestored() {
        let app = app()
        XCTAssertTrue(app.buttons["hide-stations"].waitForExistence(timeout: 10))
        app.buttons["hide-stations"].tap()
        XCTAssertTrue(app.buttons["show-stations"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["hide-stations"].exists)
        app.buttons["show-stations"].tap()
        XCTAssertTrue(app.buttons["hide-stations"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["station-parkside"].exists, "Synthetic stations must not appear in the live app")
    }
    func testCityFallbackAndSettingsAreAvailableWithoutLocationPermission() {
        let app = app()
        XCTAssertTrue(app.buttons["choose-area"].waitForExistence(timeout: 10))
        app.buttons["choose-area"].tap()
        XCTAssertTrue(app.textFields["search-city"].waitForExistence(timeout: 5))
        app.buttons["Close"].tap()
        app.buttons["open-settings"].tap()
        XCTAssertTrue(app.buttons["apply-settings"].waitForExistence(timeout: 5))
        app.buttons["apply-settings"].tap()
        XCTAssertTrue(app.buttons["fuel-diesel"].exists)
    }
}
