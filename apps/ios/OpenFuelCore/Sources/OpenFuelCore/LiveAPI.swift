// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct SearchArea: Codable, Equatable, Sendable {
    public let latitude: Double
    public let longitude: Double
    public let label: String
    public init(latitude: Double, longitude: Double, label: String) throws {
        guard latitude.isFinite, longitude.isFinite, (-90...90).contains(latitude),
              (-180...180).contains(longitude), !label.isEmpty else { throw LiveServiceError.invalidArea }
        self.latitude = latitude; self.longitude = longitude; self.label = String(label.prefix(120))
    }
    public var isValid: Bool { latitude.isFinite && longitude.isFinite && (-90...90).contains(latitude) && (-180...180).contains(longitude) }
    public func matches(_ other: SearchArea) -> Bool {
        abs(latitude - other.latitude) < 0.001 && abs(longitude - other.longitude) < 0.001
    }
    public var queryItems: [URLQueryItem] {
        [.init(name: "lat", value: String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), latitude)),
         .init(name: "lon", value: String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), longitude)),
         .init(name: "radius", value: "10000")]
    }
}

public struct LiveCity: Decodable, Identifiable, Sendable {
    public let name: String; public let latitude: Double; public let longitude: Double
    public var id: String { "\(name):\(latitude):\(longitude)" }
    public var area: SearchArea? { try? SearchArea(latitude: latitude, longitude: longitude, label: name) }
}

/// Remote brand artwork remains on its source host. Invalid URLs use text initials.
public enum RemoteBrandLogo {
    public static func validatedURL(_ text: String?) -> URL? {
        guard let text, let c = URLComponents(string: text), c.scheme == "https",
              c.user == nil, c.password == nil, c.fragment == nil,
              let host = c.host?.lowercased(), ["thumb.wikimedia.org", "www.fuel.crs", "www.shell.ca"].contains(host),
              c.port == nil || c.port == 443, let url = c.url else { return nil }
        return url
    }
}

public struct LiveStationResponse: Decodable, Sendable {
    public let mode: String; public let isDemo: Bool; public let stations: [LiveStation]
    enum CodingKeys: String, CodingKey { case mode, isDemo = "is_demo", stations }
    public func validatedStations(now: Date = Date()) throws -> [PreviewStation] {
        guard mode == "live", !isDemo, stations.count <= 200,
              Set(stations.map(\.id)).count == stations.count else { throw LiveServiceError.invalidResponse }
        return try stations.map { try $0.station(now: now) }
    }
}

public struct LiveStation: Decodable, Sendable {
    public let id: String; public let name: String
    public let brand: String?; public let address: String?
    public let latitude: Double; public let longitude: Double
    public let distanceMetres: Int; public let synthetic: Bool
    public let open: Bool?
    public let prices: [String: Int?]
    public let ages: [String: Int]?
    public let observedAt: [String: String]?
    public let priceSources: [String: String]?
    public let brandKey: String?; public let brandLogoUrl: String?
    public func station(now: Date = Date()) throws -> PreviewStation {
        guard !synthetic, id.range(of: "^osm-(node|way|relation)-[0-9]+$", options: .regularExpression) != nil,
              !name.isEmpty, name.count <= 500, latitude.isFinite, longitude.isFinite,
              (-90...90).contains(latitude), (-180...180).contains(longitude), distanceMetres >= 0 else { throw LiveServiceError.invalidResponse }
        var validPrices: [String: Int] = [:], validAges: [String: Int] = [:]
        for grade in PreviewGrade.allCases {
            if let wrapped = prices[grade.rawValue], let amount = wrapped {
                guard (500...3999).contains(amount) else { throw LiveServiceError.invalidResponse }
                validPrices[grade.rawValue] = amount
                validAges[grade.rawValue] = LiveDates.age(observedAt?[grade.rawValue], now: now)
                    ?? max(0, min(1_051_200, ages?[grade.rawValue] ?? 1_051_200))
            }
        }
        return PreviewStation(id: id, name: name, brand: brand ?? "", address: address ?? "",
            distanceMetres: distanceMetres, minutes: 0, x: 0, y: 0, open: open,
            prices: validPrices, ages: validAges, memberDiscount: 0,
            latitude: latitude, longitude: longitude, brandKey: brandKey,
            brandLogoUrl: RemoteBrandLogo.validatedURL(brandLogoUrl)?.absoluteString,
            observedAt: observedAt, priceSources: priceSources)
    }
}

public enum LiveDates {
    public static func date(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let result = formatter.date(from: value) { return result }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }
    public static func age(_ value: String?, now: Date = Date()) -> Int? {
        guard let value, let observed = date(value) else { return nil }
        return Int(min(1_051_200, max(0, now.timeIntervalSince(observed) / 60)))
    }
}

public struct LiveStationCache: Codable, Sendable {
    public let stations: [PreviewStation]
    public let area: SearchArea
    public let savedAt: Date
    public let apiOrigin: String
    public init(stations: [PreviewStation], area: SearchArea, savedAt: Date = Date(), apiOrigin: String) {
        self.stations = stations; self.area = area; self.savedAt = savedAt; self.apiOrigin = apiOrigin
    }
    /// Disk snapshots retain a broad starting area, not the device fix or distances derived from it.
    public func coarseSnapshot() -> Self {
        guard area.isValid, let coarse = try? SearchArea(latitude: (area.latitude * 100).rounded() / 100,
            longitude: (area.longitude * 100).rounded() / 100, label: "Saved area") else { return self }
        let roundedStations = stations.map { original in
            var station = original
            // Legacy layout/travel fields must not preserve a precise search origin either.
            station.x = 0; station.y = 0; station.minutes = 0
            if let latitude = station.latitude, let longitude = station.longitude,
               latitude.isFinite, longitude.isFinite, (-90...90).contains(latitude), (-180...180).contains(longitude) {
                let radians = Double.pi / 180
                let a = pow(sin((latitude - coarse.latitude) * radians / 2), 2)
                    + cos(coarse.latitude * radians) * cos(latitude * radians)
                    * pow(sin((longitude - coarse.longitude) * radians / 2), 2)
                station.distanceMetres = Int((6_371_000 * 2 * asin(sqrt(min(1, max(0, a))))).rounded())
            } else { station.distanceMetres = 0 }
            return station
        }
        return Self(stations: roundedStations, area: coarse, savedAt: savedAt, apiOrigin: apiOrigin)
    }
    enum CodingKeys: String, CodingKey { case stations, area, savedAt, apiOrigin }
    public func encode(to encoder: Encoder) throws {
        let snapshot = coarseSnapshot()
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(snapshot.stations, forKey: .stations); try values.encode(snapshot.area, forKey: .area)
        try values.encode(savedAt, forKey: .savedAt); try values.encode(apiOrigin, forKey: .apiOrigin)
    }
    public func validatedStations(apiOrigin: String, area expected: SearchArea? = nil, now: Date = Date()) throws -> [PreviewStation] {
        guard self.apiOrigin == apiOrigin, area.isValid,
              expected.map({ $0.isValid && abs(area.latitude - $0.latitude) < 0.01 && abs(area.longitude - $0.longitude) < 0.01 }) ?? true,
              stations.count <= 200, savedAt.timeIntervalSince1970.isFinite,
              Set(stations.map(\.id)).count == stations.count,
              stations.allSatisfy({ station in
                  station.id.hasPrefix("osm-") && station.latitude.map { $0.isFinite && (-90...90).contains($0) } == true &&
                  station.longitude.map { $0.isFinite && (-180...180).contains($0) } == true &&
                  station.prices.values.allSatisfy { (500...3999).contains($0) }
              }) else { throw LiveServiceError.invalidResponse }
        return stations.map { original in
            var result = original
            for (grade, age) in result.ages {
                result.ages[grade] = LiveDates.age(result.observedAt?[grade], now: now)
                    ?? min(1_051_200, max(0, age) + Int(min(525_600, max(0, now.timeIntervalSince(savedAt) / 60))))
            }
            result.brandLogoUrl = RemoteBrandLogo.validatedURL(result.brandLogoUrl)?.absoluteString
            return result
        }
    }
}

public enum LiveServiceError: Error, LocalizedError {
    case invalidResponse, invalidArea, server(Int, String)
    public var errorDescription: String? {
        switch self {
        case .invalidResponse: return "The server did not return valid real-station data."
        case .invalidArea: return "Choose valid latitude and longitude or search a Canadian city."
        case .server(let code, let message): return message.isEmpty ? "The server returned HTTP \(code). Try again." : String(message.prefix(240))
        }
    }
}

private final class LiveNoRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) { completionHandler(nil) }
}

public final class LiveAPIClient: @unchecked Sendable {
    private let baseURL: URL
    private let session: URLSession
    public convenience init(server: String) throws {
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false; config.httpCookieStorage = nil; config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 15; config.timeoutIntervalForResource = 25
        try self.init(server: server, session: URLSession(configuration: config, delegate: LiveNoRedirects(), delegateQueue: nil))
    }
    public init(server: String, session: URLSession) throws {
        baseURL = try PublicAPIConfiguration(server).baseURL; self.session = session
    }
    deinit { session.invalidateAndCancel() }
    public func stations(near area: SearchArea) async throws -> [PreviewStation] {
        guard area.isValid else { throw LiveServiceError.invalidArea }
        var c = URLComponents(url: baseURL.appendingPathComponent("stations"), resolvingAgainstBaseURL: false)!
        c.queryItems = area.queryItems
        let data = try await send(URLRequest(url: c.url!))
        return try JSONDecoder().decode(LiveStationResponse.self, from: data).validatedStations()
    }
    public func cities(matching query: String) async throws -> [LiveCity] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (2...80).contains(q.count) else { throw LiveServiceError.invalidArea }
        var c = URLComponents(url: baseURL.appendingPathComponent("geocode"), resolvingAgainstBaseURL: false)!
        c.queryItems = [.init(name: "q", value: q)]
        struct Response: Decodable { let results: [LiveCity] }
        let data = try await send(URLRequest(url: c.url!))
        return try JSONDecoder().decode(Response.self, from: data).results.prefix(8).filter { $0.area != nil }
    }
    public func report(_ value: PrototypePriceReport) async throws -> PrototypeReportReceipt {
        var request = URLRequest(url: baseURL.appendingPathComponent("reports"))
        request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(value)
        let data = try await send(request)
        let receipt = try JSONDecoder().decode(PrototypeReportReceipt.self, from: data)
        guard receipt.ok, !receipt.isDemo, receipt.report.id == value.requestID.uuidString,
              receipt.report.stationID == value.stationID, receipt.report.fuelType == value.fuelType,
              receipt.report.priceMilli == value.priceMilli, LiveDates.date(receipt.report.observedAt) != nil else { throw LiveServiceError.invalidResponse }
        return receipt
    }
    private func send(_ input: URLRequest) async throws -> Data {
        var request = input; request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, data.count <= 2_000_000 else { throw LiveServiceError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            struct ErrorBody: Decodable { let message: String? }
            throw LiveServiceError.server(http.statusCode, (try? JSONDecoder().decode(ErrorBody.self, from: data))?.message ?? "")
        }
        guard http.mimeType == "application/json" else { throw LiveServiceError.invalidResponse }
        return data
    }
}
