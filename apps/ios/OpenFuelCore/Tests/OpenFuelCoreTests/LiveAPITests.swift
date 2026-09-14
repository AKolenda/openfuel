// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import OpenFuelCore

private final class LiveMockProtocol: URLProtocol, @unchecked Sendable {
    static var handler: ((URLRequest) throws -> (Int, Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, data) = try Self.handler!(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}
final class LiveAPITests: XCTestCase {
    let fixture = #"{"mode":"live","is_demo":false,"stations":[{"id":"osm-node-123","name":"Test station <script>","brand":"Test brand","address":"Test fixture only","latitude":53.5461,"longitude":-113.4938,"distanceMetres":1200,"synthetic":false,"open":null,"prices":{"regular":null,"premium":1629,"diesel":null},"ages":{"premium":15},"observedAt":{"premium":"2026-09-12T05:00:00.000Z"},"priceSources":{"premium":"community-unverified"},"brandKey":"test","brandLogoUrl":"https://thumb.wikimedia.org/test.png"}]}"#
    private func client() throws -> LiveAPIClient {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [LiveMockProtocol.self]
        return try LiveAPIClient(server: "https://openfuel.example/api/v1", session: URLSession(configuration: config))
    }
    private func stations() throws -> [PreviewStation] {
        try JSONDecoder().decode(LiveStationResponse.self, from: Data(fixture.utf8)).validatedStations(now: Date(timeIntervalSince1970: 1_789_188_000))
    }
    override func tearDown() { LiveMockProtocol.handler = nil }
    func testRealCoordinatesNullablePriceAndUnknownOpenSurviveDecoding() throws {
        let station = try XCTUnwrap(stations().first)
        XCTAssertEqual(station.latitude, 53.5461); XCTAssertEqual(station.longitude, -113.4938)
        XCTAssertNil(station.open); XCTAssertNil(station.price(.regular)); XCTAssertEqual(station.price(.premium), 1629)
        XCTAssertEqual(station.brandLogoUrl, "https://thumb.wikimedia.org/test.png")
        XCTAssertEqual(PreviewRules.visible([station], grade: .regular).count, 1, "Unreported stations stay on map/list")
    }
    func testRejectsSampleInvalidCoordinatesAndPriceRanges() throws {
        for (old, new) in [("\"live\"", "\"prototype\""), ("\"synthetic\":false", "\"synthetic\":true"),
                           ("53.5461", "153.5461"), ("1629", "499"), ("osm-node-123", "parkside")] {
            let input = fixture.replacingOccurrences(of: old, with: new)
            XCTAssertThrowsError(try JSONDecoder().decode(LiveStationResponse.self, from: Data(input.utf8)).validatedStations())
        }
    }
    func testCoordinatesRequiredRoundedAndNoImplicitDefaultArea() async throws {
        for (lat, lon) in [(Double.nan, 0.0), (91.0, 0.0), (0.0, 181.0)] { XCTAssertThrowsError(try SearchArea(latitude: lat, longitude: lon, label: "Test")) }
        let area = try SearchArea(latitude: 53.546123, longitude: -113.493812, label: "Test area")
        LiveMockProtocol.handler = { request in
            let components = try XCTUnwrap(URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false))
            XCTAssertEqual(components.path, "/api/v1/stations")
            let query = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
            XCTAssertEqual(query, ["lat": "53.546", "lon": "-113.494", "radius": "10000"])
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            return (200, Data(self.fixture.utf8))
        }
        let result = try await client().stations(near: area)
        XCTAssertEqual(result.count, 1)
    }
    func testCitySearchEscapesUserInputAndDropsInvalidCoordinates() async throws {
        LiveMockProtocol.handler = { request in
            let q = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first?.value
            XCTAssertEqual(q, "Montréal & area")
            return (200, Data(#"{"results":[{"name":"Montréal","latitude":45.5,"longitude":-73.5},{"name":"Invalid","latitude":99,"longitude":0}]}"#.utf8))
        }
        let cities = try await client().cities(matching: "Montréal & area")
        XCTAssertEqual(cities.map(\.name), ["Montréal"])
    }
    func testLiveReportReceiptMustMatchRequestIDAndPayload() async throws {
        let value = try PrototypePriceReport(stationID: "osm-node-123", fuelType: .regular, priceMilli: 1499, clientID: UUID())
        let receipt: [String: Any] = ["ok": true, "is_demo": false, "verification": "unverified", "report": ["id": value.requestID.uuidString, "station_id": value.stationID, "fuel_type": "regular", "price_milli": 1499, "observed_at": "2026-09-12T05:00:00.000Z"]]
        LiveMockProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "POST"); XCTAssertEqual(request.url?.path, "/api/v1/reports")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            return (201, try JSONSerialization.data(withJSONObject: receipt))
        }
        let result = try await client().report(value); XCTAssertEqual(result.report.id, value.requestID.uuidString)
        LiveMockProtocol.handler = { _ in
            var changed = receipt; var report = receipt["report"] as! [String: Any]; report["id"] = "different-request"; changed["report"] = report
            return (200, try JSONSerialization.data(withJSONObject: changed))
        }
        do { _ = try await client().report(value); XCTFail("Mismatched receipt must fail") } catch LiveServiceError.invalidResponse { }
    }
    func testRateLimitNeverLooksLikeSuccess() async throws {
        LiveMockProtocol.handler = { _ in (429, Data(#"{"message":"Wait before another report"}"#.utf8)) }
        let value = try PrototypePriceReport(stationID: "osm-node-123", fuelType: .regular, priceMilli: 1499, clientID: UUID())
        do { _ = try await client().report(value); XCTFail("Rate limit must fail") }
        catch LiveServiceError.server(let status, _) { XCTAssertEqual(status, 429) }
    }
    func testLastAreaCacheIsNamespacedRejectsOtherAreaAndKeepsPriceAges() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let area = try SearchArea(latitude: 53.5461, longitude: -113.4938, label: "Edmonton")
        let now = try XCTUnwrap(LiveDates.date("2026-09-12T06:00:00.000Z"))
        let cache = LiveStationCache(stations: try stations(), area: area, savedAt: now, apiOrigin: "https://openfuel.ca/api/v1")
        let store = PreviewDiskStore(directory: directory); try await store.saveLiveStationCache(cache)
        let restored = try await store.liveStationCache(); let loaded = try XCTUnwrap(restored)
        XCTAssertEqual(loaded.area.latitude, 53.55); XCTAssertEqual(loaded.area.longitude, -113.49)
        XCTAssertEqual(loaded.area.label, "Saved area")
        XCTAssertNotEqual(loaded.stations[0].distanceMetres, cache.stations[0].distanceMetres)
        let aged = try loaded.validatedStations(apiOrigin: cache.apiOrigin, area: area, now: now.addingTimeInterval(3600))
        XCTAssertEqual(aged[0].age(.premium), 120)
        XCTAssertThrowsError(try loaded.validatedStations(apiOrigin: "https://other.example/api/v1"))
        XCTAssertThrowsError(try loaded.validatedStations(apiOrigin: cache.apiOrigin, area: SearchArea(latitude: 51.04, longitude: -114.07, label: "Calgary")))
        let sample = LiveStationCache(stations: PreviewSamples.stations, area: area, apiOrigin: cache.apiOrigin)
        XCTAssertThrowsError(try sample.validatedStations(apiOrigin: cache.apiOrigin))
    }
    func testSerializedCacheDropsPreciseFixAndMigratesAnOlderSavedOrigin() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let precise = try SearchArea(latitude: 53.546129, longitude: -113.493876, label: "53.546129, -113.493876")
        let cache = LiveStationCache(stations: try stations(), area: precise, apiOrigin: "https://openfuel.ca/api/v1")
        let bytes = try JSONEncoder().encode(cache)
        let text = try XCTUnwrap(String(data: bytes, encoding: .utf8))
        XCTAssertFalse(text.contains("53.546129")); XCTAssertFalse(text.contains("113.493876"))
        let decoded = try JSONDecoder().decode(LiveStationCache.self, from: bytes)
        XCTAssertEqual(decoded.area.latitude, 53.55); XCTAssertEqual(decoded.area.longitude, -113.49)
        XCTAssertEqual(decoded.area.label, "Saved area")
        XCTAssertEqual(cache.area, precise, "Coarsening disk output must not replace the live in-memory GPS fix")
        XCTAssertNotEqual(decoded.stations[0].distanceMetres, cache.stations[0].distanceMetres)
        var old = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        old["area"] = ["latitude": precise.latitude, "longitude": precise.longitude, "label": precise.label]
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let path = directory.appendingPathComponent("live-stations-v1.json")
        try JSONSerialization.data(withJSONObject: old).write(to: path)
        let loaded = try await PreviewDiskStore(directory: directory).liveStationCache()
        XCTAssertEqual(loaded?.area.latitude, 53.55)
        let migrated = try String(contentsOf: path, encoding: .utf8)
        XCTAssertFalse(migrated.contains("53.546129")); XCTAssertFalse(migrated.contains("113.493876"))
    }
    func testRemoteLogoURLsAreRestrictedToVerifiedHostsWithoutCredentials() {
        for url in ["https://thumb.wikimedia.org/brand.png", "https://www.fuel.crs/brand.png", "https://www.shell.ca/brand.png"] { XCTAssertNotNil(RemoteBrandLogo.validatedURL(url)) }
        for url in ["http://thumb.wikimedia.org/brand.png", "https://thumb.wikimedia.org.evil.example/brand.png", "https://user:pass@www.shell.ca/brand.png", "file:///tmp/logo.png", "https://www.shell.ca:8080/brand.png"] { XCTAssertNil(RemoteBrandLogo.validatedURL(url)) }
    }
}
