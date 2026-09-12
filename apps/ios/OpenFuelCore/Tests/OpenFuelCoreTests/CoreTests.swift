// SPDX-License-Identifier: AGPL-3.0-only
import XCTest
@testable import OpenFuelCore

final class CoreTests: XCTestCase {
    func testExactPriceParsing() {
        XCTAssertEqual(PriceInput.milli("1.479"), 1479)
        XCTAssertEqual(PriceInput.milli("1,479"), 1479)
        XCTAssertEqual(PriceInput.milli("3.499"), 3499)
        XCTAssertEqual(PriceInput.milli(" 1.5 "), 1500)
        XCTAssertEqual(PriceInput.milli("15"), 15000)
        XCTAssertEqual(PriceInput.milli("0.050"), 50)
    }
    func testRejectInvalidPrices() {
        for invalid in ["", "-1", "NaN", "1.2345", "1,234.50", "0.049", "15.001", "1.", "9999999999999999"] {
            XCTAssertNil(PriceInput.milli(invalid), invalid)
        }
    }
    func testRealBackendContract() throws {
        let file = try XCTUnwrap(Bundle.module.url(forResource: "stations", withExtension: "json"))
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        let response = try decoder.decode(StationResponse.self, from: Data(contentsOf: file))
        XCTAssertEqual(response.stations.count, 3)
        XCTAssertEqual(response.region, "demo-region")
        let sorted = StationRanking.sorted(response.stations)
        XCTAssertEqual(sorted.first?.id, "demo-river")
        XCTAssertEqual(sorted.last?.id, "demo-north") // Cheapest fixture is stale, not a current deal.
        XCTAssertEqual(sorted.first?.price?.formatted, "CAD 1.479/L")
    }
    func testReportSnakeCaseContract() throws {
        let report = PriceReport(stationId: "demo-river", fuel: .regular, payment: .cash, priceMilli: 1479)
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoder.encode(report)) as? [String: Any])
        XCTAssertEqual(Set(object.keys), Set(["station_id", "report_id", "fuel_type", "payment_type", "price_milli", "observed_at"]))
        XCTAssertEqual(object["price_milli"] as? Int, 1479)
    }
    func testTransportRules() throws {
        XCTAssertThrowsError(try APIClient(server: "http://example.com"))
        XCTAssertThrowsError(try APIClient(server: "http://example.com", allowLocalHTTP: true))
        XCTAssertThrowsError(try APIClient(server: "https://user:password@example.com"))
        XCTAssertThrowsError(try APIClient(server: "https://example.com?tracking=yes"))
        _ = try APIClient(server: "https://example.com")
        _ = try APIClient(server: "http://127.0.0.1:8000", allowLocalHTTP: true)
    }
}
