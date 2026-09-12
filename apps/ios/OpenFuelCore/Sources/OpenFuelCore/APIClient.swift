// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum ServiceError: Error, LocalizedError {
    case invalidServer, http(Int), invalidResponse
    public var errorDescription: String? {
        switch self {
        case .invalidServer: return "Use an HTTPS server URL. Debug builds also allow localhost HTTP."
        case .http(let status):
            if status == 401 { return "Reporting needs a valid closed-test token." }
            if status == 503 { return "Reporting is disabled on this server." }
            if status == 409 { return "This report ID was already used. Start a new report." }
            return "Server returned HTTP \(status). Check the connection and try again."
        case .invalidResponse: return "The server returned an unexpected response."
        }
    }
}
private final class NoRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil) // Do not forward report credentials to a redirected endpoint.
    }
}
public final class APIClient: @unchecked Sendable {
    private let base: URL
    private let session: URLSession
    public init(server: String, allowLocalHTTP: Bool = false) throws {
        guard let parts = URLComponents(string: server), let url = parts.url,
              let host = parts.host, !host.isEmpty, parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil,
              parts.scheme == "https" || (allowLocalHTTP && parts.scheme == "http" &&
                                          ["127.0.0.1", "localhost", "::1"].contains(host)) else {
            throw ServiceError.invalidServer
        }
        base = url
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 12
        configuration.timeoutIntervalForResource = 20
        session = URLSession(configuration: configuration, delegate: NoRedirects(), delegateQueue: nil)
    }
    deinit { session.invalidateAndCancel() }

    public func stations(region: String, fuel: Fuel, payment: Payment) async throws -> [Station] {
        guard var parts = URLComponents(url: base.appendingPathComponent("v1/stations"), resolvingAgainstBaseURL: false) else {
            throw ServiceError.invalidServer
        }
        parts.queryItems = [.init(name: "region", value: region), .init(name: "fuel", value: fuel.rawValue),
                            .init(name: "payment", value: payment.rawValue)]
        guard let url = parts.url else { throw ServiceError.invalidServer }
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let data = try await send(request)
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(StationResponse.self, from: data).stations
    }
    public func report(_ report: PriceReport, token: String) async throws {
        var request = URLRequest(url: base.appendingPathComponent("v1/reports"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        request.httpBody = try encoder.encode(report)
        _ = try await send(request)
    }
    private func send(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw ServiceError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else { throw ServiceError.http(http.statusCode) }
        return data
    }
}
