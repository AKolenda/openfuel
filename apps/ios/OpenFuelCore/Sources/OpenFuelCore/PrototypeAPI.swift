// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Cloudflare's public prototype contract. Null means that a grade is unavailable.
public struct PrototypeStationResponse: Decodable, Sendable {
    public let mode: String
    public let isDemo: Bool
    public let stations: [PrototypeStation]
    enum CodingKeys: String, CodingKey { case mode, isDemo = "is_demo", stations }

    public func previewStations() throws -> [PreviewStation] {
        guard mode == "prototype", isDemo, stations.allSatisfy(\.synthetic),
              Set(stations.map(\.id)).count == stations.count else { throw PrototypeServiceError.invalidResponse }
        return stations.map { $0.preview }
    }
}

public struct PrototypeStation: Decodable, Sendable {
    public let id: String
    public let name: String
    public let brand: String
    public let address: String
    public let distanceMetres: Int
    public let minutes: Int
    public let x: Double
    public let y: Double
    public let open: Bool
    public let prices: [String: Int?]
    public let ages: [String: Int]?
    public let age: Int
    public let memberDiscount: Int?
    public let synthetic: Bool

    public var preview: PreviewStation {
        let available = prices.compactMapValues { $0 }
        let displayBrand = brand == "petro-canada" ? "Petro-Canada" : brand.capitalized
        return PreviewStation(id: id, name: name, brand: displayBrand, address: address,
            distanceMetres: distanceMetres, minutes: minutes, x: x, y: y, open: open,
            prices: available, ages: Dictionary(uniqueKeysWithValues: available.keys.map {
                ($0, max(0, ages?[$0] ?? age))
            }), memberDiscount: memberDiscount ?? 0)
    }
}

public struct PrototypePriceReport: Encodable, Sendable {
    public let stationID: String
    public let fuelType: PreviewGrade
    public let priceMilli: Int
    public let clientID: UUID
    public let requestID: UUID
    enum CodingKeys: String, CodingKey {
        case stationID = "station_id", fuelType = "fuel_type", priceMilli = "price_milli", clientID = "client_id"
        case requestID = "request_id"
    }
    public init(stationID: String, fuelType: PreviewGrade, priceMilli: Int, clientID: UUID, requestID: UUID = UUID()) throws {
        guard !stationID.isEmpty, (500...3999).contains(priceMilli) else { throw PreviewError.invalidPrice }
        self.stationID = stationID; self.fuelType = fuelType; self.priceMilli = priceMilli; self.clientID = clientID
        self.requestID = requestID
    }
}

public struct PrototypeReportReceipt: Decodable, Sendable {
    public let ok: Bool
    public let isDemo: Bool
    public let report: Report
    enum CodingKeys: String, CodingKey { case ok, isDemo = "is_demo", report }
    public struct Report: Decodable, Sendable {
        public let id: String
        public let stationID: String
        public let fuelType: PreviewGrade
        public let priceMilli: Int
        public let observedAt: String
        enum CodingKeys: String, CodingKey {
            case id, stationID = "station_id", fuelType = "fuel_type", priceMilli = "price_milli", observedAt = "observed_at"
        }
    }
}

public enum PrototypeServiceError: Error, LocalizedError {
    case invalidResponse, server(Int, String)
    public var errorDescription: String? {
        switch self {
        case .invalidResponse: return "The server returned an unexpected prototype response."
        case .server(let status, let message):
            return message.isEmpty ? "The server returned HTTP \(status). Please try again." : String(message.prefix(240))
        }
    }
}

public final class PrototypeAPIClient: @unchecked Sendable {
    private let baseURL: URL
    private let session: URLSession

    public convenience init(server: String) throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 12
        configuration.timeoutIntervalForResource = 20
        try self.init(server: server, session: URLSession(configuration: configuration))
    }

    /// Injectable transport allows core tests without an Apple SDK or a running server.
    public init(server: String, session: URLSession) throws {
        baseURL = try PublicAPIConfiguration(server).baseURL
        self.session = session
    }

    public func stations() async throws -> [PreviewStation] {
        let data = try await send(URLRequest(url: baseURL.appendingPathComponent("stations")))
        return try JSONDecoder().decode(PrototypeStationResponse.self, from: data).previewStations()
    }

    public func report(_ value: PrototypePriceReport) async throws -> PrototypeReportReceipt {
        var request = URLRequest(url: baseURL.appendingPathComponent("reports"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(value)
        let data = try await send(request)
        let receipt = try JSONDecoder().decode(PrototypeReportReceipt.self, from: data)
        guard receipt.ok, receipt.isDemo, !receipt.report.id.isEmpty,
              receipt.report.stationID == value.stationID,
              receipt.report.fuelType == value.fuelType,
              receipt.report.priceMilli == value.priceMilli else { throw PrototypeServiceError.invalidResponse }
        return receipt
    }

    private func send(_ request: URLRequest) async throws -> Data {
        var request = request
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw PrototypeServiceError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            struct ErrorBody: Decodable { let message: String? }
            let message = (try? JSONDecoder().decode(ErrorBody.self, from: data))?.message ?? ""
            throw PrototypeServiceError.server(http.statusCode, message)
        }
        return data
    }
}

/// The cache ages while offline; yesterday's report must not look newly reported on relaunch.
public struct PrototypeStationCache: Codable, Sendable {
    public let stations: [PreviewStation]
    public let savedAt: Date
    public init(stations: [PreviewStation], savedAt: Date = Date()) { self.stations = stations; self.savedAt = savedAt }
    public func agedStations(now: Date = Date()) -> [PreviewStation] {
        let elapsed = max(0, Int(now.timeIntervalSince(savedAt) / 60))
        return stations.map { original in
            var station = original
            station.ages = station.ages.mapValues { min(525_600, $0) + min(525_600, elapsed) }
            return station
        }
    }
}
