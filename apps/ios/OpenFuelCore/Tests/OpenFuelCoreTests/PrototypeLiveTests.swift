// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import XCTest
@testable import OpenFuelCore

/// Explicit opt-in: posts a test price to the shared prototype database.
final class PrototypeLiveTests: XCTestCase {
    func testDeployedCloudflareReadReportRead() async throws {
        guard let base = ProcessInfo.processInfo.environment["OPENFUEL_LIVE_TEST_API_BASE_URL"] else {
            throw XCTSkip("Set OPENFUEL_LIVE_TEST_API_BASE_URL to explicitly test a deployed prototype.")
        }
        let client = try PrototypeAPIClient(server: base)
        let stations = try await client.stations()
        XCTAssertEqual(stations.count, 6)
        let parkside = try XCTUnwrap(stations.first { $0.id == "parkside" })
        let price = try XCTUnwrap(parkside.price(.regular))
        let report = try PrototypePriceReport(stationID: parkside.id, fuelType: .regular,
                                             priceMilli: price, clientID: UUID())
        let receipt = try await client.report(report)
        XCTAssertEqual(receipt.report.stationID, parkside.id)
        XCTAssertEqual(receipt.report.priceMilli, price)
        let retried = try await client.report(report)
        XCTAssertEqual(retried.report.id, receipt.report.id, "An unchanged retry must reuse the original report")
        let refreshed = try await client.stations()
        let saved = try XCTUnwrap(refreshed.first { $0.id == parkside.id })
        XCTAssertEqual(saved.price(.regular), price)
        XCTAssertEqual(saved.age(.regular), 0)
        print("Verified live Cloudflare GET → POST receipt → GET using the Swift prototype client (\(stations.count) stations).")
    }
}
