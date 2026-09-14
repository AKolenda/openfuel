// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import OpenFuelCore

struct FuelMenuView:View {
    @ObservedObject var model:PreviewModel
    let menu:FuelMenu
    @Environment(\.openURL) private var openURL
    @State private var draftPrefs=PreviewPreferences()
    @State private var amount=""
    @State private var observed=false
    @State private var proposalKind="addition"
    @State private var name="",latitude="",longitude="",note=""
    var body:some View {
        VStack(spacing:0){
            HStack{Text(title).font(.system(size:24,weight:.bold));Spacer();Button{model.menu=nil}label:{Image(systemName:"xmark").font(.system(size:18)).frame(width:44,height:44)}.disabled(model.isReporting).accessibilityLabel(tr("close"))}.padding(.horizontal,22).padding(.top,24).padding(.bottom,12)
            ScrollView{VStack(alignment:.leading,spacing:17){content}.padding(.horizontal,22).padding(.bottom,24)}
            if menu == .settings {
                HStack(spacing:14){Button(tr("reset")){draftPrefs=PreviewPreferences()}.buttonStyle(.bordered);Spacer();Button(tr("apply")){model.preferences=draftPrefs;model.persist();model.menu=nil}.buttonStyle(.borderedProminent).accessibilityIdentifier("apply-settings")}
                    .padding(.horizontal,22).padding(.vertical,16).tint(Color.fuelGreen)
            }
        }.foregroundStyle(Color.fuelInk).background(.white).accessibilityIdentifier("sheet-\(menu.rawValue)")
        .onAppear{draftPrefs=model.preferences;amount="";observed=false;proposalKind=model.selected==nil ? "addition":"correction";name=model.selected?.name ?? ""}
    }
    private var title:String{switch menu{case .settings:return tr("settings");case .sort:return tr("sort_stations");case .about:return tr("about");case .detail:return model.selected?.name ?? tr("station");case .report:return tr("report_price");case .proposal:return tr("suggest_station");case .maps:return tr("choose_maps");case .location:return tr("choose_area")}}
    @ViewBuilder private var content:some View {
        switch menu {
        case .sort:
            ForEach(PreviewSort.allCases){s in option(sortTitle(s),selected:model.sort==s){model.sort=s;model.menu=nil}.accessibilityIdentifier("sort-\(s.rawValue)")}
        case .settings:
            Group{
                Text("Fuel type").font(.subheadline.weight(.semibold))
                Picker("Fuel type", selection: $model.grade) { ForEach(PreviewGrade.allCases) { grade in Text(tr(grade.rawValue)).tag(grade) } }.pickerStyle(.segmented)
                Text("Pull down at the top of the station list to refresh. Drag its handle to resize or hide it.").font(.footnote).foregroundStyle(Color.fuelMuted)
                Text("Station locations: OpenStreetMap contributors. Community pump prices are unverified; confirm at the pump.").font(.footnote).foregroundStyle(Color.fuelMuted)
                Button("About OpenFuel and data sources") { model.menu = .about }.accessibilityIdentifier("open-about")
                Button(tr("refresh_prices")) { Task { await model.refresh() } }.disabled(model.isLoading)
                Divider()

                Text(tr("open_with")).font(.subheadline.weight(.semibold))
                Picker(tr("open_with"),selection:$draftPrefs.maps){ForEach(PreviewMaps.allCases){p in Text(p == .ask ? tr("ask_maps") : p.label).tag(p)}}.pickerStyle(.menu)
                Text(tr("maps_choice_note")).font(.footnote).foregroundStyle(Color.fuelMuted)
                Divider()
                Text(tr("maps_layout")).font(.subheadline.weight(.semibold))
                Picker(tr("maps_layout"),selection:$draftPrefs.wide){Text(tr("beside_price")).tag(false);Text(tr("full_width")).tag(true)}.pickerStyle(.segmented)
                Text(tr("distance")).font(.subheadline.weight(.semibold))
                Picker(tr("distance"),selection:$draftPrefs.filters.radiusMetres){ForEach([2000,5000,10000],id:\.self){n in Text("\(n/1000) km").tag(n)}}.pickerStyle(.segmented)
                Toggle(tr("recently_reported"),isOn:$draftPrefs.filters.fresh)
                Text(tr("sample_preferences")).font(.footnote).foregroundStyle(Color.fuelMuted)
                
            }.tint(Color.fuelGreen)
        case .about:
            Text(tr("prototype_explanation")).font(.callout).lineSpacing(5)
            Text(tr("connected_backend_note")).font(.callout).foregroundStyle(Color.fuelMuted)
            Text(tr("status_"+model.connectionState)).font(.footnote.weight(.semibold))
            if let synced=model.lastSyncedAt {Text("\(tr("last_sync")) \(synced.formatted(date:.abbreviated,time:.shortened))").font(.footnote).foregroundStyle(Color.fuelMuted)}
            Text(model.serverURL).font(.caption).textSelection(.enabled).foregroundStyle(Color.fuelMuted)
            Text("AGPL-3.0-only").font(.footnote)
            Link("© OpenStreetMap contributors · ODbL",destination:URL(string:"https://www.openstreetmap.org/copyright")!).font(.footnote)
            Link("GeoNames · CC BY 4.0",destination:URL(string:"https://www.geonames.org/")!).font(.footnote)
            Text(tr("brand_notice")).font(.footnote).foregroundStyle(Color.fuelMuted)
            Text("\(model.drafts.count) \(tr("local_drafts"))").font(.headline)
            ForEach(model.drafts){d in Text("\(d.name) · \(d.kind)").font(.footnote)}
            Button(tr("delete_drafts")){model.deleteDrafts()}.buttonStyle(.bordered)
        case .maps:
            Text(tr("sample_handoff")).font(.callout).foregroundStyle(Color.fuelMuted)
            ForEach([PreviewMaps.apple,.google]){p in
                Button{model.preferences.maps=p;model.persist();if let u=model.mapsURL(p){openURL(u)};model.menu=nil}label:{HStack{Image(systemName:p == .apple ? "map":"mappin.and.ellipse");Text("\(tr("open_in")) \(p.label)");Spacer();Image(systemName:"arrow.up.right")}.frame(minHeight:48).padding(.horizontal,15).background(Color.fuelSoft,in:RoundedRectangle(cornerRadius:12))}
            }
            Text(tr("maps_choice_note")).font(.footnote).foregroundStyle(Color.fuelMuted)
        case .detail:
            if let s=model.selected{
                Text(s.address).foregroundStyle(Color.fuelMuted)
                HStack(alignment:.firstTextBaseline){Text(s.price(model.grade,members:model.preferences.filters.members).map(PreviewRules.cents) ?? "—").font(.system(size:42,weight:.bold));Text("¢/L").font(.footnote)}
                Text(s.price(model.grade)==nil ? tr("price_unknown") : ageTitle(s.age(model.grade))+" · "+tr("unverified")).font(.footnote).foregroundStyle(Color.fuelMuted)
                Button{model.requestMaps(s)}label:{Label(tr("open_maps"),systemImage:"location").frame(maxWidth:.infinity,minHeight:48)}.buttonStyle(.borderedProminent)
                HStack{Button(tr("report_price")){model.reportError=nil;model.menu = .report}.accessibilityIdentifier("open-report");Spacer();Button(tr(model.preferences.saved.contains(s.id) ? "unsave":"save")){model.toggleSaved(s)}}.buttonStyle(.bordered)
                Button(tr("suggest_correction")){model.menu = .proposal}
                Text(tr("sample_handoff")).font(.footnote).foregroundStyle(Color.fuelMuted)
            }
        case .report:
            Text(tr("report_cloud_notice")).font(.callout).foregroundStyle(Color.fuelMuted)
            TextField("142.9",text:$amount).font(.system(size:32,weight:.semibold)).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).disabled(model.isReporting).accessibilityIdentifier("report-amount")
            Text("CAD ¢/L · \(tr(model.grade.rawValue))").font(.footnote)
            Toggle(tr("observed_today"),isOn:$observed).disabled(model.isReporting)
            if let error=model.reportError {Text(error).font(.callout).foregroundStyle(Color.red).accessibilityIdentifier("report-error")}
            if !model.canReport {Text(tr("report_unavailable")).font(.footnote).foregroundStyle(Color.fuelMuted)}
            Button {if let value=PreviewRules.parseCents(amount){Task{await model.report(amount:value)}}}label:{
                HStack{if model.isReporting{ProgressView().tint(.white)};Text(tr(model.isReporting ? "submitting_price":"submit_price"))}
            }.buttonStyle(.borderedProminent).disabled(!observed || PreviewRules.parseCents(amount)==nil || !model.canReport || model.isReporting || model.isLoading).accessibilityIdentifier("submit-price")
        case .location:
            Button { model.menu=nil;model.requestLocation() } label: { Label(tr("use_location"),systemImage:"location.fill").frame(maxWidth:.infinity,minHeight:44) }.buttonStyle(.borderedProminent)
            Text(tr("location_choice_note")).font(.footnote).foregroundStyle(Color.fuelMuted)
            TextField(tr("search_city"),text:$model.cityQuery).textFieldStyle(.roundedBorder).autocorrectionDisabled().accessibilityIdentifier("search-city")
                .task(id:model.cityQuery) { await model.searchCities() }
            ForEach(model.cities) { city in Button(city.name) { if let area=city.area { model.choose(area) } }.frame(minHeight:44) }
            if let error=model.cityError { Text(error).font(.footnote).foregroundStyle(Color.fuelMuted) }
            Divider()
            Text(tr("enter_coordinates")).font(.headline)
            TextField(tr("latitude"),text:$latitude).keyboardType(.numbersAndPunctuation).textFieldStyle(.roundedBorder)
            TextField(tr("longitude"),text:$longitude).keyboardType(.numbersAndPunctuation).textFieldStyle(.roundedBorder)
            Button(tr("use_coordinates")) {
                if let lat=Double(latitude),let lon=Double(longitude),let area=try? SearchArea(latitude:lat,longitude:lon,label:tr("manual_area")) { model.choose(area) }
            }.buttonStyle(.bordered).disabled(Double(latitude).flatMap { lat in Double(longitude).flatMap { lon in try? SearchArea(latitude:lat,longitude:lon,label:tr("manual_area")) } } == nil)
        case .proposal:
            Text(tr("proposal_local_notice")).font(.callout).foregroundStyle(Color.fuelMuted)
            if model.selected != nil {Picker(tr("change_type"),selection:$proposalKind){ForEach(["correction","temporary_closure","permanent_closure","reopening"],id:\.self){k in Text(tr(k)).tag(k)}}}
            TextField(tr("station_name"),text:$name).textFieldStyle(.roundedBorder)
            if proposalKind=="addition"{TextField(tr("latitude"),text:$latitude).keyboardType(.numbersAndPunctuation).textFieldStyle(.roundedBorder);TextField(tr("longitude"),text:$longitude).keyboardType(.numbersAndPunctuation).textFieldStyle(.roundedBorder)}
            TextField(tr("note"),text:$note,axis:.vertical).lineLimit(3...6).textFieldStyle(.roundedBorder)
            Button(tr("save_draft")){model.addDraft(kind:proposalKind,name:name,lat:latitude,lon:longitude,note:note)}.buttonStyle(.borderedProminent)
        }
    }
    private func option(_ title:String,selected:Bool,action:@escaping()->Void)->some View {
        Button(action:action){HStack{Text(title).font(.system(size:15,weight:.semibold));Spacer();if selected{Image(systemName:"checkmark")}}.padding(17).frame(minHeight:60).background(selected ? Color.fuelSoft:.white,in:RoundedRectangle(cornerRadius:14))}
    }
}
