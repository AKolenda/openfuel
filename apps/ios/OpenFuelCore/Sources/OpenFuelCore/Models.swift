// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

public enum Fuel: String, CaseIterable, Identifiable, Codable, Sendable {
    case regular, midgrade, premium, diesel
    public var id: String { rawValue }
}
public enum Payment: String, CaseIterable, Identifiable, Codable, Sendable {
    case standard, cash, credit, membership
    public var id: String { rawValue }
}
public struct StationResponse: Decodable, Sendable {
    public let region: String
    public let stations: [Station]
}
public struct Station: Decodable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let address: String
    public let currency: String
    public let volumeUnit: String
    public let sourceLicense: String
    public let isDemo: Bool
    public let price: FuelPrice?
}
public struct FuelPrice: Decodable, Sendable {
    public let eventSeq: Int
    public let priceMilli: Int
    public let fuelType: String
    public let paymentType: String
    public let currency: String
    public let volumeUnit: String
    public let observedBucket: String
    public let source: String
    public let verification: String
    public let freshness: String
    public let ageSeconds: Int
    public var formatted: String {
        "\(currency) \(priceMilli / 1000).\(String(format: "%03d", priceMilli % 1000))/\(volumeUnit)"
    }
}
public struct PriceReport: Encodable, Sendable {
    public let reportId: UUID
    public let stationId: String
    public let fuelType: String
    public let paymentType: String
    public let priceMilli: Int
    public let observedAt: String
    public init(stationId: String, fuel: Fuel, payment: Payment, priceMilli: Int,
                reportId: UUID = UUID(), now: Date = Date()) {
        self.reportId = reportId; self.stationId = stationId
        self.fuelType = fuel.rawValue; self.paymentType = payment.rawValue
        self.priceMilli = priceMilli
        self.observedAt = ISO8601DateFormatter().string(from: now)
    }
}
public enum PriceInput {
    /// Currency units per station volume unit: "1.479" CAD/L -> 1479. Never binary floating point.
    public static func milli(_ text: String) -> Int? {
        let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")
        guard cleaned.range(of: "^[0-9]{1,2}(\\.[0-9]{1,3})?$", options: .regularExpression) != nil else { return nil }
        let parts = cleaned.split(separator: ".")
        guard let whole = Int(parts[0]) else { return nil }
        let digits = parts.count == 2 ? String(parts[1]) : ""
        guard let fraction = Int(digits.padding(toLength: 3, withPad: "0", startingAt: 0)) else { return nil }
        let result = whole * 1000 + fraction
        return (50...15000).contains(result) ? result : nil
    }
}
public enum StationRanking {
    /// Comparable prices only: caller has selected one fuel/payment. Stale is never a current bargain.
    public static func sorted(_ stations: [Station]) -> [Station] {
        stations.sorted {
            let a = $0.price.map { $0.freshness == "stale" ? 1 : 0 } ?? 2
            let b = $1.price.map { $0.freshness == "stale" ? 1 : 0 } ?? 2
            if a != b { return a < b }
            let ap = $0.price?.priceMilli ?? Int.max, bp = $1.price?.priceMilli ?? Int.max
            if ap != bp { return ap < bp }
            return $0.id < $1.id
        }
    }
}
