// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import OpenFuelCore

private final class PrototypeMockProtocol: URLProtocol, @unchecked Sendable {
    static var handler: ((URLRequest) throws -> (Int, Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, body) = try Self.handler!(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

final class PrototypeAPITests: XCTestCase {
    private func client() throws -> PrototypeAPIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [PrototypeMockProtocol.self]
        return try PrototypeAPIClient(server: "https://prototype.example/api/v1",
                                      session: URLSession(configuration: configuration))
    }
    override func tearDown() { PrototypeMockProtocol.handler = nil }

    func testCloudStationResponseAdaptsNullGradesAndFreshness() async throws {
        PrototypeMockProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/stations")
            XCTAssertEqual(request.httpMethod, "GET")
            return (200, Data(#"{"mode":"prototype","is_demo":true,"stations":[{"id":"juniper","name":"Husky (legacy)","brand":"husky","address":"82 Birch Street","distanceMetres":800,"minutes":2,"x":425,"y":319,"open":true,"prices":{"regular":1499,"premium":1729,"diesel":null},"ages":{"regular":0,"premium":18},"age":18,"synthetic":true}]}"#.utf8))
        }
        let values = try await client().stations()
        XCTAssertEqual(values.count, 1)
        XCTAssertEqual(values[0].price(.regular), 1499)
        XCTAssertNil(values[0].price(.diesel))
        XCTAssertEqual(values[0].age(.regular), 0)
        XCTAssertEqual(values[0].age(.premium), 18)
        XCTAssertEqual(values[0].memberDiscount, 0)
    }

    func testReportUsesExactPublicContractAndValidatesReceipt() async throws {
        let identity = UUID()
        let report = try PrototypePriceReport(stationID: "parkside", fuelType: .regular,
                                             priceMilli: 1429, clientID: identity)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(report)) as? [String: Any])
        XCTAssertEqual(Set(body.keys), Set(["station_id", "fuel_type", "price_milli", "client_id", "request_id"]))
        XCTAssertEqual(body["client_id"] as? String, identity.uuidString)
        XCTAssertEqual(body["request_id"] as? String, report.requestID.uuidString)
        XCTAssertEqual(body["price_milli"] as? Int, 1429)
        PrototypeMockProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/reports")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            return (201, Data(#"{"ok":true,"is_demo":true,"report":{"id":"demo-receipt","station_id":"parkside","fuel_type":"regular","price_milli":1429,"observed_at":"2026-09-11T18:00:00Z"}}"#.utf8))
        }
        let receipt = try await client().report(report)
        XCTAssertEqual(receipt.report.priceMilli, 1429)
    }

    func testErrorBodyDoesNotBecomeSuccessfulReport() async throws {
        let report = try PrototypePriceReport(stationID: "parkside", fuelType: .regular,
                                             priceMilli: 1429, clientID: UUID())
        PrototypeMockProtocol.handler = { _ in
            (429, Data(#"{"error":"rate_limited","message":"Please wait before another report."}"#.utf8))
        }
        do {
            _ = try await client().report(report)
            XCTFail("A rate-limited report must not appear saved")
        } catch let PrototypeServiceError.server(status, message) {
            XCTAssertEqual(status, 429)
            XCTAssertEqual(message, "Please wait before another report.")
        }
        PrototypeMockProtocol.handler = { _ in
            (200, Data(#"{"ok":true,"is_demo":true,"report":{"id":"receipt","station_id":"other-station","fuel_type":"regular","price_milli":1429,"observed_at":"2026-09-11T18:00:00Z"}}"#.utf8))
        }
        do {
            _ = try await client().report(report)
            XCTFail("Mismatched acknowledgement must not appear saved")
        } catch PrototypeServiceError.invalidResponse {}
    }

    func testStationCacheAgesAcrossAppRestartsAndIdentityPersists() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = PreviewDiskStore(directory: directory)
        let identity = try await store.clientID()
        let first = PrototypeStationCache(stations: PreviewSamples.stations,
                                          savedAt: Date(timeIntervalSince1970: 1_000))
        try await store.saveStationCache(first)
        let second = PreviewDiskStore(directory: directory)
        let restoredIdentity = try await second.clientID()
        XCTAssertEqual(identity, restoredIdentity)
        let stored = try await second.stationCache()
        let restored = try XCTUnwrap(stored)
        let aged = restored.agedStations(now: Date(timeIntervalSince1970: 4_600))
        XCTAssertEqual(aged[0].age(.regular), first.stations[0].age(.regular) + 60)
        XCTAssertEqual(aged[0].price(.regular), first.stations[0].price(.regular))
        XCTAssertEqual(restored.agedStations(now: Date(timeIntervalSince1970: 0)), first.stations)
    }

    func testLiveDataCannotBeMisrepresentedOnTheFictionalMap() throws {
        let response = try JSONDecoder().decode(PrototypeStationResponse.self,
            from: Data(#"{"mode":"live","is_demo":false,"stations":[]}"#.utf8))
        XCTAssertThrowsError(try response.previewStations())
        XCTAssertThrowsError(try PrototypePriceReport(stationID: "parkside", fuelType: .regular,
                                                    priceMilli: 4000, clientID: UUID()))
        XCTAssertThrowsError(try PrototypeAPIClient(server: "http://prototype.example/api/v1"))
    }
}
