// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import OpenFuelCore

struct MapHomeView: View {
    @StateObject private var model=PreviewModel()
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @State private var expanded=false
    @State private var collapsed=false
    var body: some View {
        GeometryReader { g in
            let bottom=CGFloat(collapsed ? 82 : expanded ? max(300,g.size.height-145) : min(336,g.size.height*0.43))
            ZStack(alignment:.top) {
                SampleMapView(stations:model.visible,grade:model.grade,members:model.preferences.filters.members,bestID:model.bestID,sheetHeight:bottom) {model.select($0)}
                VStack(spacing:10) {
                    search
                    HStack(spacing:7) {
                        ForEach(PreviewGrade.allCases){grade in
                            Button {model.grade=grade} label:{Text(tr(grade.rawValue)).font(.system(size:12,weight:.semibold)).padding(.horizontal,17).frame(height:35)}
                                .foregroundStyle(model.grade==grade ? .white : Color.fuelInk).background(model.grade==grade ? Color.fuelGreen : .white,in:Capsule()).accessibilityIdentifier("fuel-\(grade.rawValue)")
                        }
                        Button {model.savedOnly.toggle()} label:{Image(systemName:model.savedOnly ? "bookmark.fill":"bookmark").font(.system(size:17)).frame(width:38,height:38)}.background(.white,in:Circle()).accessibilityLabel(tr("saved"))
                        Spacer(minLength:0)
                    }
                }.padding(.horizontal,16).padding(.top,14)
                VStack {Spacer();results(height:bottom)}
                VStack {Spacer();HStack {Spacer();Button {model.menu = .about}label:{Image(systemName:"info.circle").font(.system(size:18)).frame(width:44,height:44)}.background(.white,in:RoundedRectangle(cornerRadius:12)).accessibilityLabel(tr("about")).accessibilityIdentifier("open-about")}.padding(.trailing,17).padding(.bottom,bottom+14)}
                if let notice=model.notice {
                    VStack{Spacer();Text(notice).font(.footnote).padding(13).foregroundStyle(.white).background(Color.fuelGreen,in:RoundedRectangle(cornerRadius:12)).padding(.horizontal,18).onTapGesture{model.notice=nil}.accessibilityAddTraits(.isStaticText);Spacer().frame(height:bottom+65)}
                }
            }.foregroundStyle(Color.fuelInk).background(Color.fuelSoft)
            .sheet(item:$model.menu){menu in
                FuelMenuView(model:model,menu:menu)
                    .presentationDetents(menu.detents)
                    .presentationDragIndicator(.visible)
                    .presentationCornerRadius(CGFloat(PreviewTokens.sheetRadius))
                    .interactiveDismissDisabled(model.isReporting)
            }
        }
        .preferredColorScheme(.light)
        .task {
            while !Task.isCancelled {
                do {try await Task.sleep(for:.seconds(60))} catch {break}
                if scenePhase == .active {await model.refresh()}
            }
        }
        .onChange(of:scenePhase){_,phase in if phase == .active {Task{await model.refresh()}}}
        .onChange(of:model.navigationURL){_,url in
            guard let url else{return};model.navigationURL=nil
            openURL(url){accepted in if !accepted{model.notice=tr("maps_unavailable")}}
        }
    }
    private var search:some View {
        HStack(spacing:12) {
            Text("openfuel").font(.system(size:23,weight:.bold)).tracking(-1.1).fixedSize()
            Rectangle().fill(Color.fuelRule).frame(width:1,height:24)
            TextField(tr("search"),text:$model.query).font(.system(size:13)).autocorrectionDisabled().textInputAutocapitalization(.never).accessibilityIdentifier("search-stations")
            Button {model.menu = .settings}label:{Image(systemName:"slider.horizontal.3").font(.system(size:20)).frame(width:38,height:44)}.accessibilityLabel(tr("settings")).accessibilityIdentifier("open-settings")
        }.padding(.leading,17).padding(.trailing,6).frame(height:CGFloat(PreviewTokens.searchHeight)).background(.white,in:RoundedRectangle(cornerRadius:CGFloat(PreviewTokens.searchRadius))).shadow(color:.black.opacity(0.06),radius:14,y:3)
    }
    private func results(height:CGFloat)->some View {
        VStack(spacing:0) {
            Button {withAnimation(.easeInOut(duration:0.22)){expanded.toggle();collapsed=false}}label:{Capsule().fill(Color.fuelRule).frame(width:39,height:5).frame(maxWidth:.infinity).frame(height:23)}.accessibilityLabel(tr("expand_results"))
                .gesture(DragGesture(minimumDistance:20).onEnded{v in withAnimation(.easeInOut(duration:0.22)){if v.translation.height < -25 {expanded=true;collapsed=false}else if v.translation.height>25{if expanded{expanded=false}else{collapsed=true}}}})
            HStack {
                Button {withAnimation{collapsed.toggle();expanded=false}}label:{HStack(spacing:7){Text(tr("fuel_nearby")).font(.system(size:23,weight:.bold));Image(systemName:collapsed ? "chevron.up":"chevron.down").font(.system(size:11)).foregroundStyle(Color.fuelMuted)}}
                Spacer()
                if !collapsed{layoutSwitch}
            }.padding(.horizontal,18)
            if !collapsed {
                HStack{Text("\(model.visible.count) \(tr("demo_stations")) · CAD ¢/L").font(.system(size:11)).foregroundStyle(Color.fuelMuted);Spacer();Button{model.menu = .sort}label:{HStack(spacing:5){Text(sortTitle(model.sort));Image(systemName:"chevron.down").font(.system(size:10))}.font(.system(size:11))}.accessibilityIdentifier("open-sort")}.padding(.horizontal,18).padding(.top,10).padding(.bottom,8)
                connectionStatus.padding(.horizontal,18).padding(.bottom,8)
                ScrollView {
                    LazyVStack(spacing:model.preferences.cards ? 9 : 4){
                        ForEach(model.visible){s in
                            StationRowView(station:s,grade:model.grade,members:model.preferences.filters.members,cards:model.preferences.cards,wide:model.preferences.wide,best:s.id==model.bestID,detail:{model.select(s)},go:{model.requestMaps(s)})
                        }
                        if model.visible.isEmpty {Text(tr("empty_results")).font(.callout).foregroundStyle(Color.fuelMuted).padding(20)}
                        Button {model.selectedID=nil;model.menu = .proposal}label:{Label(tr("suggest_station"),systemImage:"plus").font(.footnote).frame(minHeight:44)}
                    }.padding(.horizontal,12).padding(.bottom,26)
                }.refreshable{await model.refresh()}.accessibilityIdentifier("station-list")
            }
            Spacer(minLength:0)
        }.frame(maxWidth:.infinity).frame(height:height).background(.white,in:UnevenRoundedRectangle(topLeadingRadius:26,topTrailingRadius:26)).shadow(color:.black.opacity(0.07),radius:16,y:-3)
    }
    private var connectionStatus:some View {
        HStack(spacing:6){
            if model.isLoading {ProgressView().controlSize(.mini)}
            else {Circle().fill(model.connectionState=="connected" ? Color.fuelGreen : Color(hex:0x9b835b)).frame(width:6,height:6)}
            Text(tr(model.isLoading ? "loading_prices":"status_"+model.connectionState)).font(.system(size:10)).lineLimit(2)
            Spacer(minLength:0)
            Button{Task{await model.refresh()}}label:{Image(systemName:"arrow.clockwise").font(.system(size:12)).frame(width:30,height:24)}
                .disabled(model.isLoading || model.isReporting).accessibilityLabel(tr("refresh_prices")).accessibilityIdentifier("refresh-prices")
        }.foregroundStyle(Color.fuelMuted).accessibilityIdentifier("connection-status")
    }
    private var layoutSwitch:some View {
        HStack(spacing:2){ForEach([true,false],id:\.self){cards in
            Button{model.preferences.cards=cards;model.persist()}label:{HStack(spacing:4){Image(systemName:cards ? "rectangle.split.1x2":"list.bullet").font(.system(size:12));Text(tr(cards ? "cards":"list")).font(.system(size:11,weight:.semibold))}.padding(.horizontal,9).frame(height:30)}.background(model.preferences.cards==cards ? .white : Color.clear,in:RoundedRectangle(cornerRadius:8)).accessibilityIdentifier(cards ? "layout-cards":"layout-list")
        }}.padding(3).background(Color.fuelSoft,in:RoundedRectangle(cornerRadius:11))
    }
}
func sortTitle(_ mode:PreviewSort)->String{tr(mode == .best ? "best_price" : mode == .price ? "lowest_price":"nearest")}

struct StationRowView:View {
    let station:PreviewStation;let grade:PreviewGrade;let members:Bool;let cards:Bool;let wide:Bool;let best:Bool;let detail:()->Void;let go:()->Void
    var body:some View {
        VStack(spacing:0){
            HStack(spacing:10){
                Button(action:detail){
                    HStack(spacing:10){
                        Text(station.initials).font(.system(size:13,weight:.bold)).foregroundStyle(Color.fuelGreen).frame(width:34,height:34).background(Color.fuelSoft,in:RoundedRectangle(cornerRadius:10))
                        VStack(alignment:.leading,spacing:4){Text(station.name).font(.system(size:14,weight:.semibold)).lineLimit(1);Text(String(format:"%.1f km",Double(station.distanceMetres)/1000)+" · \(station.address)").font(.system(size:11)).foregroundStyle(Color.fuelMuted).lineLimit(cards ? 2 : 1)}.frame(maxWidth:.infinity,alignment:.leading)
                        if !cards{priceBlock(size:24)}
                    }.contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityIdentifier("station-\(station.id)")
                if !wide && !cards{goButton}
            }.padding(12)
            if cards{HStack{priceBlock(size:36);Spacer();if !wide{goButton}}.padding(.horizontal,12).padding(.bottom,13)}
            if wide{Button(action:go){Label(tr("open_maps"),systemImage:"location").font(.system(size:12,weight:.semibold)).frame(maxWidth:.infinity,minHeight:48)}.overlay(alignment:.top){Rectangle().fill(Color.fuelRule).frame(height:1)}.accessibilityIdentifier("go-\(station.id)")}
        }.background(best ? Color.fuelBest:.white,in:RoundedRectangle(cornerRadius:CGFloat(PreviewTokens.rowRadius))).overlay{RoundedRectangle(cornerRadius:CGFloat(PreviewTokens.rowRadius)).stroke(Color.fuelRule,lineWidth:1)}
    }
    private func priceBlock(size:CGFloat)->some View {
        VStack(alignment:.trailing,spacing:5){HStack(alignment:.firstTextBaseline,spacing:2){Text(station.price(grade,members:members).map(PreviewRules.cents) ?? "—").font(.system(size:size,weight:.bold)).tracking(-0.8).monospacedDigit();Text("¢/L").font(.system(size:8)).foregroundStyle(Color.fuelMuted)};Text(ageTitle(station.age(grade))).font(.system(size:9)).foregroundStyle(station.age(grade)>60 ? Color(hex:0x9b835b):Color.fuelMuted).lineLimit(1)}
    }
    private var goButton:some View {Button(action:go){VStack(spacing:3){Image(systemName:"location").font(.system(size:20));Text(tr("go")).font(.system(size:10,weight:.bold))}.frame(width:48,height:48)}.foregroundStyle(best ? .white:Color.fuelGreen).background(best ? Color.fuelGreen:Color.fuelSoft,in:RoundedRectangle(cornerRadius:14)).accessibilityLabel("\(tr("open_maps")) · \(station.name)").accessibilityIdentifier("go-\(station.id)")}
}
func ageTitle(_ age:Int)->String {if age==0{return tr("reported_now")};return age<60 ? String(format:tr("reported_minutes"),age):String(format:tr("reported_hours"),age/60)}
