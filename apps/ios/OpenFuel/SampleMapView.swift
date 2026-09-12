// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import OpenFuelCore

/// Native map asset + native price buttons; no WebView or rendered HTML screenshot.
/// The PNG is the original map illustration extracted from the approved SVG, not live geography.
struct SampleMapView:View {
    let stations:[PreviewStation];let grade:PreviewGrade;let members:Bool;let bestID:String?;let sheetHeight:CGFloat;let select:(PreviewStation)->Void
    @State private var zoom:CGFloat=1
    @State private var pan:CGSize = .zero
    @GestureState private var drag:CGSize = .zero
    @GestureState private var magnification:CGFloat=1
    var body:some View {
        GeometryReader{g in
            let fit=PreviewCamera.fit(width:Double(g.size.width),height:Double(g.size.height),sheetHeight:Double(sheetHeight),stations:PreviewSamples.stations)
            let scale=CGFloat(fit.scale)*zoom*magnification
            let tx=g.size.width/2-CGFloat(fit.cx)*scale+pan.width+drag.width
            let ty=g.size.height/2-CGFloat(fit.cy)*scale+pan.height+drag.height
            ZStack(alignment:.topLeading){
                Color(hex:0xeeefe7)
                Image("demo_basemap").resizable().interpolation(.high).frame(width:1800*scale,height:1400*scale).position(x:tx+600*scale,y:ty+500*scale).accessibilityHidden(true)
                ZStack{Circle().fill(Color(hex:0x4485ed).opacity(0.12)).frame(width:46,height:46);Circle().fill(Color(hex:0x4485ed)).frame(width:14,height:14).overlay{Circle().stroke(.white,lineWidth:3)}}.position(x:tx+627*scale,y:ty+652*scale).accessibilityLabel(tr("sample_position"))
                ForEach(stations){s in
                    Button{select(s)}label:{Text(s.price(grade,members:members).map(PreviewRules.cents) ?? "—").font(.system(size:17,weight:.bold)).tracking(-0.5).monospacedDigit().frame(width:76,height:39)}.foregroundStyle(s.id==bestID ? .white : s.age(grade)>60 ? Color(hex:0x9b835b):Color.fuelInk).background(s.id==bestID ? Color.fuelGreen : .white,in:Capsule()).shadow(color:.black.opacity(0.12),radius:4,y:3).position(x:tx+CGFloat(s.x)*scale,y:ty+CGFloat(s.y)*scale-18).accessibilityIdentifier("pin-\(s.id)")
                }
                VStack {Spacer();HStack{Spacer();Button{zoom=1;pan = .zero}label:{Image(systemName:"location.viewfinder").frame(width:44,height:44)}.background(.white,in:RoundedRectangle(cornerRadius:12)).accessibilityLabel(tr("recenter"))}.padding(.trailing,17).padding(.bottom,sheetHeight+67)}
            }.clipped().contentShape(Rectangle())
                .gesture(DragGesture().updating($drag){v,state,_ in state=v.translation}.onEnded{v in pan.width+=v.translation.width;pan.height+=v.translation.height})
                .simultaneousGesture(MagnificationGesture().updating($magnification){value,state,_ in state=value}.onEnded{value in zoom=min(3,max(0.7,zoom*value))})
        }
    }
}
