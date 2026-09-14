// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import OpenFuelCore

func tr(_ key: String) -> String { NSLocalizedString(key, comment: "OpenFuel interface") }
extension Color {
    init(hex: UInt) { self.init(red:Double((hex>>16)&255)/255,green:Double((hex>>8)&255)/255,blue:Double(hex&255)/255) }
    static let fuelGreen=Color(hex:PreviewTokens.green), fuelInk=Color(hex:PreviewTokens.ink), fuelMuted=Color(hex:PreviewTokens.muted), fuelRule=Color(hex:PreviewTokens.line), fuelSoft=Color(hex:PreviewTokens.soft), fuelBest=Color(hex:PreviewTokens.best)
}
enum FuelMenu: String, Identifiable {
    case settings, sort, about, detail, report, proposal, maps, location
    var id: String { rawValue }
    var detents: Set<PresentationDetent> {
        switch self { case .sort: return [.height(340)]; case .maps: return [.height(455)]
        case .settings, .about, .location: return [.fraction(0.86), .large]; default: return [.medium, .large] }
    }
}
@MainActor
final class PreviewModel: ObservableObject {
    @Published var stations: [PreviewStation] = []
    @Published var preferences = PreviewPreferences()
    @Published var grade: PreviewGrade = .regular
    @Published var sort: PreviewSort = .nearest
    @Published var query = ""
    @Published var savedOnly = false
    @Published var selectedID: String?
    @Published var menu: FuelMenu?
    @Published var notice: String?
    @Published var navigationURL: URL?
    @Published var drafts: [PreviewDraft] = []
    @Published var isLoading = false
    @Published var isReporting = false
    @Published var isLocating = false
    @Published var connectionState = "choose_area"
    @Published var reportError: String?
    @Published var lastSyncedAt: Date?
    @Published var area: SearchArea?
    @Published var mapArea: SearchArea?
    @Published var deviceArea: SearchArea?
    @Published var centerRequest = 0
    @Published var isSavedArea = false
    var areaLabel: String { isSavedArea ? tr("last_area") : area?.label ?? tr("choose_area") }
    @Published var cityQuery = ""
    @Published var cityError: String?
    @Published var cities: [LiveCity] = []
    private let location = LocationRequest()
    private var api: LiveAPIClient?
    private var clientID: UUID?
    private var pendingReport: PrototypePriceReport?
    private var loadSequence = 0
    private var citySequence = 0
    private var snapshotAt = Date()
    private let store: PreviewDiskStore
    var serverURL: String { Bundle.main.object(forInfoDictionaryKey: "OPENFUEL_API_BASE_URL") as? String ?? "https://openfuel.ca/api/v1" }
    var canReport: Bool { api != nil && clientID != nil && selected != nil }
    var visible: [PreviewStation] { PreviewRules.visible(stations, grade: grade, sort: sort, filters: preferences.filters, query: query, savedOnly: savedOnly, saved: preferences.saved) }
    var selected: PreviewStation? { stations.first { $0.id == selectedID } }
    var bestID: String? { visible.filter { $0.price(grade) != nil && $0.age(grade) <= 60 }.min { ($0.price(grade) ?? .max) < ($1.price(grade) ?? .max) }?.id }
    var canSearchMap: Bool {
        guard let mapArea else { return false }; guard let area else { return true }
        let dy = (area.latitude - mapArea.latitude) * 111_000
        let dx = (area.longitude - mapArea.longitude) * 111_000 * cos(area.latitude * .pi / 180)
        return hypot(dx, dy) > 750
    }
    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        store = PreviewDiskStore(directory: base.appendingPathComponent("OpenFuel"))
        api = try? LiveAPIClient(server: serverURL)
        Task {
            do { preferences = try await store.loadPreferences(); preferences.filters.members = false; preferences.filters.open = false; drafts = try await store.drafts() }
            catch { notice = tr("storage_error") }
            do { clientID = try await store.clientID() } catch { notice = tr("storage_error") }
            do {
                if let cache = try await store.liveStationCache(), loadSequence == 0 {
                    stations = try cache.validatedStations(apiOrigin: serverURL)
                    area = cache.area; isSavedArea = true; snapshotAt = Date(); lastSyncedAt = cache.savedAt
                    connectionState = "cached"; centerRequest += 1
                }
            } catch { notice = tr("cache_unavailable") }
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("--ui-testing") { return }
            #endif
            // Restore the last real area before asking permission; never open on sample geography.
            if loadSequence == 0 {
                requestLocation()
                if area != nil { await refresh() }
            }
        }
    }
    func persist() { let value = preferences; Task { do { try await store.savePreferences(value) } catch { notice = tr("storage_error") } } }
    func select(_ station: PreviewStation) { selectedID = station.id; menu = .detail }
    func toggleSaved(_ station: PreviewStation) {
        if preferences.saved.contains(station.id) { preferences.saved.remove(station.id) } else { preferences.saved.insert(station.id) }; persist()
    }
    func requestMaps(_ station: PreviewStation) {
        selectedID = station.id
        if preferences.maps == .ask { menu = .maps } else { navigationURL = mapsURL(preferences.maps) }
    }
    func mapsURL(_ provider: PreviewMaps) -> URL? {
        guard let station = selected, let lat = station.latitude, let lon = station.longitude else { return nil }
        var c = URLComponents(string: provider == .apple ? "https://maps.apple.com/" : "https://www.google.com/maps/dir/")!
        c.queryItems = provider == .apple ? [.init(name: "daddr", value: "\(lat),\(lon)"), .init(name: "dirflg", value: "d")] : [.init(name: "api", value: "1"), .init(name: "destination", value: "\(lat),\(lon)"), .init(name: "travelmode", value: "driving")]
        return c.url
    }
    func requestLocation() {
        guard !isLocating else { return }
        isLocating = true
        location.request { [weak self] result in
            guard let self else { return }; self.isLocating = false
            switch result {
            case .success(let fix):
                guard let point = try? SearchArea(latitude: fix.coordinate.latitude, longitude: fix.coordinate.longitude, label: tr("your_location")) else { return }
                self.deviceArea = point; self.notice = nil; self.choose(point, cancelLocation: false)
            case .failure(let error):
                self.notice = error.localizedDescription
                if self.area == nil { self.menu = .location }
            }
        }
    }
    func choose(_ point: SearchArea, cancelLocation: Bool = true, recenter: Bool = true) {
        if cancelLocation { location.cancel(); isLocating = false }
        guard point.isValid else { return }
        let changed = area.map { !$0.matches(point) } ?? true
        area = point; isSavedArea = false; mapArea = nil; menu = nil
        if recenter { centerRequest += 1 }
        if changed { stations = []; lastSyncedAt = nil; selectedID = nil }
        loadSequence += 1
        Task { await refresh() }
    }
    func searchMap() { if let mapArea { choose(mapArea, recenter: false) } }
    func searchCities() async {
        citySequence += 1; let sequence = citySequence
        let text = cityQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        cities = []; cityError = nil
        guard (2...80).contains(text.count), let api else { return }
        do {
            try await Task.sleep(for: .milliseconds(300)); try Task.checkCancellation()
            let results = try await api.cities(matching: text)
            guard sequence == citySequence, !Task.isCancelled else { return }
            cities = results; if results.isEmpty { cityError = tr("no_city_results") }
        } catch is CancellationError { } catch { if sequence == citySequence { cityError = tr("city_search_unavailable") } }
    }
    func refresh() async {
        guard let area, let api, !isReporting else { return }
        loadSequence += 1; let sequence = loadSequence
        isLoading = true
        defer { if sequence == loadSequence { isLoading = false } }
        do {
            let result = try await api.stations(near: area)
            guard sequence == loadSequence, !Task.isCancelled else { return }
            stations = result; snapshotAt = Date(); lastSyncedAt = snapshotAt; connectionState = "connected"
            do { try await store.saveLiveStationCache(LiveStationCache(stations: result, area: area, savedAt: snapshotAt, apiOrigin: serverURL)) }
            catch { notice = tr("cache_save_error") }
        } catch {
            guard sequence == loadSequence, !Task.isCancelled else { return }
            advanceAges(); connectionState = stations.isEmpty ? "offline_empty" : "offline_cached"
            notice = error.localizedDescription
        }
    }
    private func advanceAges() {
        guard let area else { return }
        if let aged = try? LiveStationCache(stations: stations, area: area, savedAt: snapshotAt, apiOrigin: serverURL).validatedStations(apiOrigin: serverURL) { stations = aged }
        snapshotAt = Date()
    }
    func report(amount: Int) async {
        guard !isReporting, !isLoading, let api, let clientID, let id = selectedID,
              let proposed = try? PrototypePriceReport(stationID: id, fuelType: grade, priceMilli: amount, clientID: clientID) else { reportError = tr("report_unavailable"); return }
        let submitted: PrototypePriceReport
        if let pending = pendingReport, pending.stationID == id, pending.fuelType == grade, pending.priceMilli == amount { submitted = pending }
        else { submitted = proposed; pendingReport = proposed }
        reportError = nil; isReporting = true
        defer { isReporting = false }
        do {
            let receipt = try await api.report(submitted); pendingReport = nil; advanceAges()
            if let index = stations.firstIndex(where: { $0.id == receipt.report.stationID }) {
                let fuel = receipt.report.fuelType.rawValue
                stations[index].prices[fuel] = receipt.report.priceMilli
                stations[index].observedAt = (stations[index].observedAt ?? [:]).merging([fuel: receipt.report.observedAt]) { _, new in new }
                stations[index].ages[fuel] = LiveDates.age(receipt.report.observedAt) ?? 0
                stations[index].priceSources = (stations[index].priceSources ?? [:]).merging([fuel: "community-unverified"]) { _, new in new }
            }
            connectionState = "connected"; notice = tr("price_updated"); menu = .detail
            if let area { try? await store.saveLiveStationCache(LiveStationCache(stations: stations, area: area, savedAt: snapshotAt, apiOrigin: serverURL)) }
        } catch { reportError = tr("report_failed") + " " + error.localizedDescription }
    }
    func addDraft(kind: String, name: String, lat: String, lon: String, note: String) {
        do {
            let draft = try PreviewDraft(stationID: kind == "addition" ? nil : selectedID, kind: kind, name: name, latitude: Double(lat), longitude: Double(lon), note: note)
            Task { do { try await store.saveDraft(draft); drafts = try await store.drafts(); notice = tr("draft_saved"); menu = .about } catch { notice = tr("storage_error") } }
        } catch { notice = tr("invalid_draft") }
    }
    func deleteDrafts() { Task { do { try await store.deleteDrafts(); drafts = [] } catch { notice = tr("storage_error") } } }
}
