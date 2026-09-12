// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

public enum PreviewGrade: String, Codable, CaseIterable, Identifiable, Sendable {
    case regular, premium, diesel
    public var id: String { rawValue }
}
public enum PreviewSort: String, Codable, CaseIterable, Identifiable, Sendable {
    case best, price, nearest
    public var id: String { rawValue }
}
public enum PreviewMaps: String, Codable, CaseIterable, Identifiable, Sendable {
    case apple, google, ask
    public var id: String { rawValue }
    public var label: String { self == .apple ? "Apple Maps" : self == .google ? "Google Maps" : "Ask each time" }
}
public struct PreviewStation: Identifiable, Codable, Equatable, Sendable {
    public let id: String
    public var name: String; public var brand: String; public var address: String
    public var distanceMetres: Int; public var minutes: Int; public var x: Double; public var y: Double
    public var open: Bool; public var prices: [String:Int]; public var ages: [String:Int]; public var memberDiscount: Int
    public func price(_ grade: PreviewGrade, members: Bool = false) -> Int? { prices[grade.rawValue].map { $0 - (members ? memberDiscount : 0) } }
    public func age(_ grade: PreviewGrade) -> Int { ages[grade.rawValue] ?? Int.max }
    public var initials: String { brand == "Petro-Canada" ? "PC" : String(brand.prefix(1)) }
}
public struct PreviewFilters: Codable, Equatable, Sendable {
    public var radiusMetres = 5000
    public var fresh = false; public var open = false; public var members = false
    public init(radiusMetres: Int = 5000, fresh: Bool = false, open: Bool = false, members: Bool = false) {
        self.radiusMetres=radiusMetres;self.fresh=fresh;self.open=open;self.members=members
    }
}
public struct PreviewPreferences: Codable, Equatable, Sendable {
    public var maps: PreviewMaps = .ask
    public var cards = false; public var wide = false; public var saved = Set<String>()
    public var filters = PreviewFilters()
    public init() {}
}
public struct PreviewDraft: Codable, Identifiable, Equatable, Sendable {
    public let id: UUID
    public let stationID: String?
    public let kind: String
    public let name: String
    public let latitude: Double?
    public let longitude: Double?
    public let note: String
    public let status: String
    public init(id: UUID = UUID(), stationID: String?, kind: String, name: String, latitude: Double? = nil, longitude: Double? = nil, note: String = "") throws {
        guard ["addition","correction","temporary_closure","permanent_closure","reopening"].contains(kind), (2...80).contains(name.trimmingCharacters(in:.whitespaces).count), note.count <= 500 else { throw PreviewError.invalidDraft }
        if kind == "addition" {
            guard let lat=latitude,let lon=longitude,lat.isFinite,lon.isFinite,(-90...90).contains(lat),(-180...180).contains(lon) else { throw PreviewError.invalidDraft }
        } else if stationID == nil { throw PreviewError.invalidDraft }
        self.id=id;self.stationID=stationID;self.kind=kind;self.name=name;self.latitude=latitude;self.longitude=longitude;self.note=note;self.status="local_pending_review"
    }
}
public enum PreviewError: Error { case invalidDraft, invalidPrice, invalidConfiguration, tooManyDrafts, conflictingDraft }
public enum PreviewRules {
    public static func cents(_ value: Int) -> String { "\(value/10).\(value%10)" }
    public static func parseCents(_ text: String) -> Int? {
        let s=text.trimmingCharacters(in:.whitespacesAndNewlines)
        guard s.range(of:"^\\d{1,3}(?:\\.\\d)?$",options:.regularExpression) != nil else{return nil}
        let parts=s.split(separator:".");guard let whole=Int(parts[0]) else{return nil}
        let amount=whole*10+(parts.count==2 ? Int(parts[1]) ?? 0 : 0)
        return (500...3999).contains(amount) ? amount : nil
    }
    public static func visible(_ stations: [PreviewStation], grade: PreviewGrade, sort: PreviewSort = .best, filters: PreviewFilters = PreviewFilters(), query: String = "", savedOnly: Bool = false, saved: Set<String> = []) -> [PreviewStation] {
        let q=query.trimmingCharacters(in:.whitespacesAndNewlines).lowercased()
        let values=stations.filter { s in
            s.price(grade,members:filters.members) != nil && s.distanceMetres <= filters.radiusMetres && (!filters.fresh || s.age(grade)<=60) && (!filters.open || s.open) && (!savedOnly || saved.contains(s.id)) && (q.isEmpty || "\(s.name) \(s.address)".lowercased().contains(q))
        }
        return values.sorted { a,b in
            if sort == .nearest {return a.distanceMetres==b.distanceMetres ? a.id<b.id : a.distanceMetres<b.distanceMetres}
            if sort == .best && (a.age(grade)>60) != (b.age(grade)>60) {return a.age(grade)<=60}
            let pa=a.price(grade,members:filters.members) ?? Int.max,pb=b.price(grade,members:filters.members) ?? Int.max
            if pa != pb {return pa<pb}
            if sort == .best && a.distanceMetres != b.distanceMetres {return a.distanceMetres<b.distanceMetres}
            return a.id<b.id
        }
    }
    public static func sampleMapURL(_ station: PreviewStation, provider: PreviewMaps) -> URL {
        var c=URLComponents(string:provider == .apple ? "https://maps.apple.com/" : "https://www.google.com/maps/search/")!
        let query=station.brand+" Canada"
        c.queryItems=provider == .apple ? [.init(name:"q",value:query)] : [.init(name:"api",value:"1"),.init(name:"query",value:query)]
        return c.url! // Fixed schemes/host + URLComponents escaping; not untrusted arbitrary destinations.
    }
    public static func updated(_ s: PreviewStation, grade: PreviewGrade, amount: Int) throws -> PreviewStation {
        guard (500...3999).contains(amount) else {throw PreviewError.invalidPrice}
        var station=s;station.prices[grade.rawValue]=amount;station.ages[grade.rawValue]=0;return station
    }
}
public struct PreviewCamera: Equatable, Sendable {
    public var cx: Double; public var cy: Double; public var scale: Double
    public init(cx: Double=600,cy: Double=500,scale: Double=1){self.cx=cx;self.cy=cy;self.scale=scale}
    public static func fit(width: Double,height: Double,sheetHeight: Double,stations: [PreviewStation]) -> Self {
        guard !stations.isEmpty else{return Self()}
        let minX=stations.map(\.x).min()!-36,maxX=stations.map(\.x).max()!+36
        let minY=stations.map(\.y).min()!-45,maxY=stations.map(\.y).max()!+45
        let left=42.0,right=width-43,top=154.0,bottom=max(226,height-sheetHeight-34)
        let scale=max(0.27,min(0.83,max(150,right-left)/max(380,maxX-minX),max(150,bottom-top)/max(400,maxY-minY)))
        return Self(cx:(minX+maxX)/2+(width/2-(left+right)/2)/scale,cy:(minY+maxY)/2+(height/2-(top+bottom)/2)/scale,scale:scale)
    }
}
/// Configuration goes into a shipped app binary: this accepts a PUBLIC HTTPS base URL, never a key.
public struct PublicAPIConfiguration: Equatable, Sendable {
    public let baseURL: URL
    public init(_ text: String) throws {
        guard let c=URLComponents(string:text),c.scheme=="https",c.host != nil,c.user==nil,c.password==nil,c.query==nil,c.fragment==nil,let u=c.url else{throw PreviewError.invalidConfiguration}
        self.baseURL=u
    }
}
/// Serialized actor + atomic file replacement. Corrupt files throw; UI shows a warning rather than reporting a successful load.
public actor PreviewDiskStore {
    private let directory: URL
    public init(directory: URL){self.directory=directory}
    public func loadPreferences() throws -> PreviewPreferences {try read("preferences.json",default:PreviewPreferences())}
    public func savePreferences(_ value: PreviewPreferences) throws {try write(value,"preferences.json")}
    public func drafts() throws -> [PreviewDraft] {try read("drafts.json",default:[])}
    public func saveDraft(_ draft: PreviewDraft) throws {
        var values=try drafts()
        if let old=values.first(where:{$0.id==draft.id}) {if old != draft {throw PreviewError.conflictingDraft};return}
        guard values.count<100 else {throw PreviewError.tooManyDrafts}
        values.append(draft);try write(values,"drafts.json")
    }
    public func deleteDrafts() throws {try write([PreviewDraft](),"drafts.json")}
    public func stationCache() throws -> PrototypeStationCache? {try read("stations.json",default:Optional<PrototypeStationCache>.none)}
    public func saveStationCache(_ value: PrototypeStationCache) throws {try write(value,"stations.json")}
    public func clientID() throws -> UUID {
        if let existing: UUID = try read("client-id.json",default:Optional<UUID>.none) {return existing}
        let value=UUID();try write(value,"client-id.json");return value
    }
    private func read<T:Decodable>(_ name: String,default value:T) throws -> T {
        let url=directory.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath:url.path) else{return value}
        return try JSONDecoder().decode(T.self,from:Data(contentsOf:url))
    }
    private func write<T:Encodable>(_ value:T,_ name:String)throws {
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        try JSONEncoder().encode(value).write(to:directory.appendingPathComponent(name),options:.atomic)
    }
}
