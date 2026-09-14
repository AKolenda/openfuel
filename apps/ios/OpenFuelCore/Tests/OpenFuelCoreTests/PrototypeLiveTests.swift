// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import XCTest
@testable import OpenFuelCore

/// Read-only opt-in; never writes made-up prices into public station records.
final class LiveReadOnlyTests: XCTestCase {
    func testRealCanadianStationsAndCitySearch() async throws {
        guard let base = ProcessInfo.processInfo.environment["OPENFUEL_READ_ONLY_TEST_API_BASE_URL"] else {
            throw XCTSkip("Set OPENFUEL_READ_ONLY_TEST_API_BASE_URL for read-only current API verification.")
        }
        let client = try LiveAPIClient(server: base)
        let area = try SearchArea(latitude: 53.5461, longitude: -113.4938, label: "Edmonton test area")
        let stations = try await client.stations(near: area)
        XCTAssertGreaterThan(stations.count, 10)
        XCTAssertTrue(stations.allSatisfy { $0.latitude != nil && $0.longitude != nil && $0.id.hasPrefix("osm-") })
        XCTAssertTrue(stations.allSatisfy { $0.distanceMetres <= 10000 })
        let cities = try await client.cities(matching: "Montréal")
        XCTAssertTrue(cities.contains { $0.name.hasPrefix("Montréal") })
        print("Read-only live Swift check passed: \(stations.count) nearby real stations, \(cities.count) city matches; zero reports submitted.")
    }
}
