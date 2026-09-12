// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import OpenFuelCore

func tr(_ key: String) -> String { NSLocalizedString(key, comment: "OpenFuel interface") }
extension Color {
    init(hex: UInt) { self.init(red:Double((hex>>16)&255)/255,green:Double((hex>>8)&255)/255,blue:Double(hex&255)/255) }
    static let fuelGreen=Color(hex:PreviewTokens.green), fuelInk=Color(hex:PreviewTokens.ink), fuelMuted=Color(hex:PreviewTokens.muted), fuelRule=Color(hex:PreviewTokens.line), fuelSoft=Color(hex:PreviewTokens.soft), fuelBest=Color(hex:PreviewTokens.best)
}
enum FuelMenu: String, Identifiable {
    case settings, sort, about, detail, report, proposal, maps
    var id: String {rawValue}
    var detents: Set<PresentationDetent> {
        switch self {case .sort:return [.height(340)];case .maps:return [.height(455)];case .settings,.about:return [.fraction(0.86),.large];default:return [.medium,.large]}
    }
}
@MainActor
final class PreviewModel: ObservableObject {
    @Published var stations=PreviewSamples.stations
    @Published var preferences=PreviewPreferences()
    @Published var grade:PreviewGrade = .regular
    @Published var sort:PreviewSort = .best
    @Published var query=""
    @Published var savedOnly=false
    @Published var selectedID:String?
    @Published var menu:FuelMenu?
    @Published var notice:String?
    @Published var navigationURL:URL?
    @Published var drafts:[PreviewDraft]=[]
    @Published var isLoading=false
    @Published var isReporting=false
    @Published var connectionState="sample"
    @Published var reportError:String?
    @Published var lastSyncedAt:Date?
    private var snapshotAt=Date()
    private var api:PrototypeAPIClient?
    private var clientID:UUID?
    private var pendingReport:PrototypePriceReport?
    private var hasCachedStations=false
    var serverURL:String {Bundle.main.object(forInfoDictionaryKey:"OPENFUEL_API_BASE_URL") as? String ?? ""}
    var canReport:Bool {api != nil && clientID != nil && !screenshots}
    private let store:PreviewDiskStore
    private let screenshots:Bool
    init() {
        let base=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask).first ?? FileManager.default.temporaryDirectory
        var screenshotMode=false
        #if DEBUG
        screenshotMode=ProcessInfo.processInfo.arguments.contains("--screenshots")
        #endif
        screenshots=screenshotMode
        store=PreviewDiskStore(directory:base.appendingPathComponent("OpenFuel"))
        if screenshotMode {
            preferences.maps = .google
            #if DEBUG
            let args=ProcessInfo.processInfo.arguments
            if args.contains("--cards"){preferences.cards=true}
            if args.contains("--wide"){preferences.wide=true}
            if let i=args.firstIndex(of:"--screen"),args.indices.contains(i+1),let m=FuelMenu(rawValue:args[i+1]){selectedID=stations.first?.id;menu=m}
            #endif
        } else {
            api=try? PrototypeAPIClient(server:serverURL)
            Task {
                do {preferences=try await store.loadPreferences();drafts=try await store.drafts()}
                catch {notice=tr("storage_error")}
                do {clientID=try await store.clientID()}
                catch {notice=tr("storage_error")}
                do {
                    if let cache=try await store.stationCache() {
                        stations=cache.agedStations();snapshotAt=Date();lastSyncedAt=cache.savedAt
                        hasCachedStations=true;connectionState="cached"
                    }
                } catch {notice=tr("storage_error")}
                await refresh()
            }
        }
    }
    var visible:[PreviewStation]{PreviewRules.visible(stations,grade:grade,sort:sort,filters:preferences.filters,query:query,savedOnly:savedOnly,saved:preferences.saved)}
    var selected:PreviewStation?{stations.first{$0.id==selectedID}}
    var bestID:String?{visible.filter{$0.age(grade)<=60}.min{($0.price(grade,members:preferences.filters.members) ?? .max)<($1.price(grade,members:preferences.filters.members) ?? .max)}?.id}
    func persist() {guard !screenshots else{return};let p=preferences;Task{do{try await store.savePreferences(p)}catch{notice=tr("storage_error")}}}
    func select(_ s:PreviewStation){selectedID=s.id;menu = .detail}
    func toggleSaved(_ s:PreviewStation){if preferences.saved.contains(s.id){preferences.saved.remove(s.id)}else{preferences.saved.insert(s.id)};persist()}
    func requestMaps(_ s:PreviewStation){selectedID=s.id;if preferences.maps == .ask {menu = .maps}else{navigationURL=PreviewRules.sampleMapURL(s,provider:preferences.maps)}}
    func mapsURL(_ provider:PreviewMaps)->URL? {selected.map{PreviewRules.sampleMapURL($0,provider:provider)}}
    func refresh() async {
        guard !screenshots,!isLoading,!isReporting else{return}
        guard let api else{connectionState="unconfigured";return}
        isLoading=true
        defer {isLoading=false}
        do {
            stations=try await api.stations();snapshotAt=Date();lastSyncedAt=snapshotAt
            hasCachedStations=true;connectionState="connected"
            do {try await store.saveStationCache(PrototypeStationCache(stations:stations,savedAt:snapshotAt))}
            catch {notice=tr("cache_save_error")}
        } catch {
            advanceAges()
            connectionState=error is URLError ? (hasCachedStations ? "offline_cached":"offline_sample") : "sync_error"
        }
    }
    private func advanceAges() {
        let now=Date()
        stations=PrototypeStationCache(stations:stations,savedAt:snapshotAt).agedStations(now:now)
        snapshotAt=now
    }
    func report(amount:Int) async {
        guard !isReporting,!isLoading,let api,let clientID,let id=selectedID,
              let proposed=try? PrototypePriceReport(stationID:id,fuelType:grade,priceMilli:amount,clientID:clientID) else {
            reportError=tr("report_unavailable");return
        }
        let submitted:PrototypePriceReport
        if let pending=pendingReport,pending.stationID==id,pending.fuelType==grade,pending.priceMilli==amount {submitted=pending}
        else {submitted=proposed;pendingReport=proposed}
        reportError=nil;isReporting=true
        defer{isReporting=false}
        do {
            let receipt=try await api.report(submitted)
            pendingReport=nil
            advanceAges()
            if let index=stations.firstIndex(where:{$0.id==receipt.report.stationID}) {
                stations[index]=try PreviewRules.updated(stations[index],grade:receipt.report.fuelType,amount:receipt.report.priceMilli)
            }
            lastSyncedAt=Date();hasCachedStations=true;connectionState="connected"
            notice=tr("price_updated");menu = .detail
            do {try await store.saveStationCache(PrototypeStationCache(stations:stations,savedAt:snapshotAt))}
            catch {notice=tr("report_saved_cache_failed")}
        } catch {
            reportError=tr("report_failed")+" "+error.localizedDescription
            if error is URLError {connectionState=hasCachedStations ? "offline_cached":"offline_sample"}
        }
    }
    func addDraft(kind:String,name:String,lat:String,lon:String,note:String) {
        do {
            let draft=try PreviewDraft(stationID:kind=="addition" ? nil : selectedID,kind:kind,name:name,latitude:Double(lat),longitude:Double(lon),note:note)
            Task {do{try await store.saveDraft(draft);drafts=try await store.drafts();notice=tr("draft_saved");menu = .about}catch{notice=tr("storage_error")}}
        }catch{notice=tr("invalid_draft")}
    }
    func deleteDrafts(){Task{do{try await store.deleteDrafts();drafts=[]}catch{notice=tr("storage_error")}}}
}
