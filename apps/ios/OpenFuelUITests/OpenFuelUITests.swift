// SPDX-License-Identifier: AGPL-3.0-only
import XCTest

/// Real simulator screenshots attached to xcresult. Not executed in this Linux environment.
final class OpenFuelUITests: XCTestCase {
    override func setUpWithError() throws {continueAfterFailure=false}
    func testApprovedScreenStates() {
        for state in ["list","cards","wide","sort","settings","about","detail","report"] {
            let app=XCUIApplication()
            app.launchArguments=["--screenshots","-AppleLanguages","(en)","-AppleLocale","en_CA"]
            if state=="cards"{app.launchArguments.append("--cards")}
            if state=="wide"{app.launchArguments.append("--wide")}
            if !["list","cards","wide"].contains(state){app.launchArguments += ["--screen",state]}
            app.launch()
            XCTAssertTrue(app.wait(for:.runningForeground,timeout:15))
            if ["list","cards","wide"].contains(state){XCTAssertTrue(app.buttons["station-parkside"].waitForExistence(timeout:10))}
            if !["list","cards","wide"].contains(state){XCTAssertTrue(app.otherElements["sheet-\(state)"].waitForExistence(timeout:10))}
            // Stable local fixture; no network and no async location or price changes.
            let attachment=XCTAttachment(screenshot:XCUIScreen.main.screenshot())
            attachment.name="ios-\(state)-en";attachment.lifetime = .keepAlways;add(attachment)
            app.terminate()
        }
    }
    func testSortAndSettingsActuallyRespond() {
        let app=XCUIApplication();app.launchArguments=["--screenshots"];app.launch()
        let sort=app.buttons["open-sort"];XCTAssertTrue(sort.waitForExistence(timeout:10));sort.tap()
        let nearest=app.buttons["sort-nearest"];XCTAssertTrue(nearest.waitForExistence(timeout:5));nearest.tap()
        let settings=app.buttons["open-settings"];XCTAssertTrue(settings.waitForExistence(timeout:5));settings.tap()
        let apply=app.buttons["apply-settings"];if !apply.isHittable{app.swipeUp()};XCTAssertTrue(apply.waitForExistence(timeout:5));apply.tap()
        XCTAssertTrue(app.buttons["station-juniper"].exists)
    }
}
