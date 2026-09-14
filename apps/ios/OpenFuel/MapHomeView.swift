// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import OpenFuelCore

struct MapHomeView: View {
    @StateObject private var model = PreviewModel()
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @State private var expanded = false
    @State private var sheetHidden = false
    var body: some View {
        GeometryReader { geometry in
            let bottom: CGFloat = sheetHidden ? 0 : expanded ? max(300, geometry.size.height - 142) : min(320, geometry.size.height * 0.42)
            ZStack(alignment: .top) {
                if let area = model.area {
                    LiveMapView(model: model, area: area).padding(.bottom, bottom)
                } else {
                    Color.fuelSoft
                    VStack(spacing: 14) {
                        Image(systemName: "location.magnifyingglass").font(.system(size: 36)).foregroundStyle(Color.fuelGreen)
                        Text(tr("choose_area")).font(.title3.bold())
                        Text(tr("location_choice_note")).font(.callout).multilineTextAlignment(.center).foregroundStyle(Color.fuelMuted)
                        Button(tr("choose_area")) { model.menu = .location }.buttonStyle(.borderedProminent)
                    }.padding(28).padding(.top, 154)
                }
                VStack(spacing: 9) { search; compactControls }.padding(.horizontal, 14).padding(.top, 8)
                if !sheetHidden { VStack { Spacer(); results(height: bottom) } }
                VStack {
                    Spacer().frame(height: 125)
                    HStack {
                        Spacer()
                        VStack(alignment: .trailing, spacing: 8) {
                            Button { model.requestLocation() } label: {
                                Group { if model.isLocating { ProgressView() } else { Image(systemName: "location.fill") } }.frame(width: 44, height: 44)
                            }.disabled(model.isLocating).background(.white, in: RoundedRectangle(cornerRadius: 12)).accessibilityLabel(tr("use_location"))
                            Button { model.menu = .about } label: { Image(systemName: "info.circle").frame(width: 44, height: 44) }
                                .background(.white, in: RoundedRectangle(cornerRadius: 12)).accessibilityLabel(tr("about")).accessibilityIdentifier("open-about")
                            if model.canSearchMap {
                                Button { model.searchMap() } label: { Label(tr("search_this_area"), systemImage: "magnifyingglass").font(.footnote.weight(.semibold)).padding(12) }
                                    .background(.white, in: Capsule()).accessibilityIdentifier("search-this-area")
                            }
                        }
                    }.padding(.trailing, 14)
                    Spacer()
                    if sheetHidden {
                        Button { withAnimation { sheetHidden = false; expanded = false; model.preferences.cards = false } } label: {
                            Label(tr("show_stations"), systemImage: "list.bullet").font(.callout.weight(.semibold)).padding(.horizontal, 18).frame(height: 48)
                        }.background(.white, in: Capsule()).padding(.bottom, 22).accessibilityIdentifier("show-stations")
                    }
                }
                if let notice = model.notice {
                    VStack { Spacer(); Text(notice).font(.footnote).padding(12).foregroundStyle(.white).background(Color.fuelGreen, in: RoundedRectangle(cornerRadius: 12)).padding(.horizontal, 18).onTapGesture { model.notice = nil }; Spacer().frame(height: bottom + 66) }
                        .allowsHitTesting(true)
                }
            }
            .foregroundStyle(Color.fuelInk).background(Color.fuelSoft)
            .sheet(item: $model.menu) { menu in
                FuelMenuView(model: model, menu: menu).presentationDetents(menu.detents).presentationDragIndicator(.visible)
                    .presentationCornerRadius(24).interactiveDismissDisabled(model.isReporting)
            }
        }
        // Keep the status bar visible and legible, with controls below the safe area.
        .preferredColorScheme(.light).tint(Color.fuelGreen)
        .task {
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(60)) } catch { break }
                if scenePhase == .active { await model.refresh() }
            }
        }
        .onChange(of: scenePhase) { _, phase in if phase == .active { Task { await model.refresh() } } }
        .onChange(of: model.navigationURL) { _, url in
            guard let url else { return }; model.navigationURL = nil
            openURL(url) { accepted in if !accepted { model.notice = tr("maps_unavailable") } }
        }
    }
    private var search: some View {
        HStack(spacing: 10) {
            HStack(spacing: 3) {
                Image("OpenFuelMark").resizable().scaledToFit().frame(width: 27, height: 27)
                Text("openfuel").font(.system(size: 22, weight: .bold)).tracking(-1).fixedSize()
            }.accessibilityElement(children: .ignore).accessibilityLabel("OpenFuel")
            Rectangle().fill(Color.fuelRule).frame(width: 1, height: 22)
            TextField(tr("search"), text: $model.query).font(.system(size: 13)).autocorrectionDisabled().textInputAutocapitalization(.never).accessibilityIdentifier("search-stations")
            Button { model.menu = .settings } label: { Image(systemName: "slider.horizontal.3").font(.system(size: 20)).frame(width: 36, height: 44) }.accessibilityLabel(tr("settings")).accessibilityIdentifier("open-settings")
        }.padding(.leading, 14).padding(.trailing, 5).frame(height: 56).background(.white, in: RoundedRectangle(cornerRadius: 18)).shadow(color: .black.opacity(0.05), radius: 8, y: 2)
    }
    private var compactControls: some View {
        HStack(spacing: 3) {
            Button { model.menu = .location } label: {
                HStack(spacing: 3) { Image(systemName: "mappin"); Text(model.areaLabel).lineLimit(1); Image(systemName: "chevron.down").font(.system(size: 8)) }
                    .font(.system(size: 11, weight: .semibold)).frame(maxWidth: .infinity, minHeight: 42)
            }.accessibilityLabel(tr("choose_area")).accessibilityIdentifier("choose-area")
            ForEach(PreviewGrade.allCases) { grade in
                Button { model.grade = grade } label: { Text(tr(grade.rawValue)).font(.system(size: 10, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.85).padding(.horizontal, 7).frame(height: 36) }
                    .foregroundStyle(model.grade == grade ? .white : Color.fuelInk).background(model.grade == grade ? Color.fuelGreen : Color.clear, in: Capsule()).accessibilityIdentifier("fuel-\(grade.rawValue)")
            }
            Button { model.savedOnly.toggle() } label: { Image(systemName: model.savedOnly ? "bookmark.fill" : "bookmark").frame(width: 35, height: 42) }
                .accessibilityLabel(tr("saved"))
        }.padding(.horizontal, 5).background(.white, in: Capsule())
    }
    private func results(height: CGFloat) -> some View {
        VStack(spacing: 0) {
            Capsule().fill(Color.fuelRule).frame(width: 39, height: 5).frame(maxWidth: .infinity).frame(height: 26).contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 15).onEnded { value in
                    withAnimation(.easeInOut(duration: 0.2)) {
                        if value.translation.height > 35 { if expanded { expanded = false } else { sheetHidden = true } }
                        else if value.translation.height < -25 { expanded = true }
                    }
                })
            HStack {
                Text(tr("fuel_nearby")).font(.system(size: 22, weight: .bold))
                Spacer()
                Button { withAnimation { expanded.toggle() } } label: { Image(systemName: expanded ? "chevron.down" : "chevron.up").frame(width: 36, height: 36) }.accessibilityLabel(tr("expand_results"))
                Button { withAnimation { sheetHidden = true; expanded = false } } label: { Image(systemName: "xmark").frame(width: 36, height: 36) }.accessibilityLabel(tr("hide_stations")).accessibilityIdentifier("hide-stations")
            }.padding(.horizontal, 16)
            HStack {
                Text("\(model.visible.count) \(tr("demo_stations")) · CAD ¢/L").font(.system(size: 11)).foregroundStyle(Color.fuelMuted)
                Spacer()
                Button { model.menu = .sort } label: { Label(sortTitle(model.sort), systemImage: "arrow.up.arrow.down").font(.system(size: 11)) }.accessibilityIdentifier("open-sort")
            }.padding(.horizontal, 18).padding(.vertical, 6)
            connectionStatus.padding(.horizontal, 18).padding(.bottom, 7)
            ScrollView {
                LazyVStack(spacing: 5) {
                    ForEach(model.visible) { station in
                        StationRowView(station: station, grade: model.grade, members: false, cards: model.preferences.cards, wide: model.preferences.wide, best: station.id == model.bestID,
                                       detail: { model.select(station) }, go: { model.requestMaps(station) })
                    }
                    if model.visible.isEmpty { Text(tr("empty_results")).font(.callout).foregroundStyle(Color.fuelMuted).padding(20) }
                    Button { model.selectedID = nil; model.menu = .proposal } label: { Label(tr("suggest_station"), systemImage: "plus").font(.footnote).frame(minHeight: 44) }
                }.padding(.horizontal, 12).padding(.bottom, 20)
            }.refreshable { await model.refresh() }.accessibilityIdentifier("station-list")
        }.frame(maxWidth: .infinity).frame(height: height).background(.white, in: UnevenRoundedRectangle(topLeadingRadius: 25, topTrailingRadius: 25)).shadow(color: .black.opacity(0.07), radius: 12, y: -3)
    }
    private var connectionStatus: some View {
        HStack(spacing: 6) {
            if model.isLoading { ProgressView().controlSize(.mini) }
            else { Circle().fill(model.connectionState == "connected" ? Color.fuelGreen : Color(hex: 0x9b835b)).frame(width: 6, height: 6) }
            VStack(alignment: .leading, spacing: 2) {
                Text(tr(model.isLoading ? "loading_prices" : "status_" + model.connectionState)).font(.system(size: 10))
                if model.connectionState != "connected", let saved = model.lastSyncedAt { Text(saved, style: .relative).font(.system(size: 10)) }
            }
            Spacer()
            Button { Task { await model.refresh() } } label: { Image(systemName: "arrow.clockwise").frame(width: 32, height: 28) }.disabled(model.isLoading || model.isReporting || model.area == nil).accessibilityLabel(tr("refresh_prices")).accessibilityIdentifier("refresh-prices")
        }.foregroundStyle(Color.fuelMuted).accessibilityIdentifier("connection-status")
    }
}
func sortTitle(_ mode: PreviewSort) -> String { tr(mode == .best ? "best_price" : mode == .price ? "lowest_price" : "nearest") }

struct StationRowView: View {
    let station: PreviewStation; let grade: PreviewGrade; let members: Bool; let cards: Bool; let wide: Bool; let best: Bool; let detail: () -> Void; let go: () -> Void
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Button(action: detail) {
                    HStack(spacing: 10) {
                        BrandLogoView(station: station)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(station.name).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                            Text(String(format: "%.1f km", Double(station.distanceMetres) / 1000) + " · \(station.address)").font(.system(size: 11)).foregroundStyle(Color.fuelMuted).lineLimit(cards ? 2 : 1)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        if !cards { priceBlock(size: 23) }
                    }.contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityIdentifier("station-\(station.id)")
                if !wide && !cards { goButton }
            }.padding(12)
            if cards { HStack { priceBlock(size: 34); Spacer(); if !wide { goButton } }.padding(.horizontal, 12).padding(.bottom, 12) }
            if wide { Button(action: go) { Label(tr("open_maps"), systemImage: "location").font(.system(size: 12, weight: .semibold)).frame(maxWidth: .infinity, minHeight: 48) }.accessibilityIdentifier("go-\(station.id)") }
        }.background(best ? Color.fuelBest : .white, in: RoundedRectangle(cornerRadius: 15)).overlay(RoundedRectangle(cornerRadius: 15).stroke(Color.fuelRule, lineWidth: 1))
    }
    private func priceBlock(size: CGFloat) -> some View {
        VStack(alignment: .trailing, spacing: 4) {
            Text(station.price(grade).map(PreviewRules.cents) ?? "—").font(.system(size: size, weight: .bold)).monospacedDigit()
            Text(station.price(grade) == nil ? tr("price_unknown") : "¢/L · \(ageTitle(station.age(grade)))").font(.system(size: 9)).foregroundStyle(Color.fuelMuted).lineLimit(1)
            if station.price(grade) != nil { Text(tr("unverified")).font(.system(size: 9)).foregroundStyle(Color.fuelMuted) }
        }
    }
    private var goButton: some View {
        Button(action: go) { VStack(spacing: 3) { Image(systemName: "location"); Text(tr("go")).font(.system(size: 10, weight: .bold)) }.frame(width: 44, height: 48) }
            .foregroundStyle(Color.fuelGreen).background(Color.fuelSoft, in: RoundedRectangle(cornerRadius: 13)).accessibilityLabel("\(tr("open_maps")) · \(station.name)").accessibilityIdentifier("go-\(station.id)")
    }
}
func ageTitle(_ age: Int) -> String {
    if age == .max || age >= 1_051_200 { return tr("time_unavailable") }
    if age == 0 { return tr("reported_now") }
    if age < 60 { return String(format: tr("reported_minutes"), age) }
    if age < 1440 { return String(format: tr("reported_hours"), age / 60) }
    return String(format: tr("reported_days"), age / 1440)
}
