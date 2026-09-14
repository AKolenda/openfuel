// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import MapKit
import OpenFuelCore

struct LiveMapView: View {
    @ObservedObject var model: PreviewModel
    let area: SearchArea
    @State private var position: MapCameraPosition
    init(model: PreviewModel, area: SearchArea) {
        self.model = model; self.area = area
        _position = State(initialValue: .region(Self.region(area)))
    }
    private static func region(_ area: SearchArea) -> MKCoordinateRegion {
        MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: area.latitude, longitude: area.longitude), latitudinalMeters: 8_000, longitudinalMeters: 8_000)
    }
    var body: some View {
        Map(position: $position, interactionModes: [.pan, .zoom, .rotate]) {
            ForEach(model.visible) { station in
                if let lat = station.latitude, let lon = station.longitude {
                    Annotation(station.name, coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon), anchor: .bottom) {
                        Button { model.select(station) } label: {
                            VStack(spacing: 3) {
                                if let price = station.price(model.grade) { Text(PreviewRules.cents(price)).font(.system(size: 13, weight: .bold)).monospacedDigit() }
                                BrandLogoView(station: station, size: 25)
                            }
                            .padding(5).foregroundStyle(Color.fuelGreen).background(.white, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.fuelGreen, lineWidth: station.id == model.bestID ? 3 : 1))
                            .shadow(color: .black.opacity(0.12), radius: 3, y: 2)
                        }.buttonStyle(.plain).accessibilityLabel("\(station.name), \(station.price(model.grade).map { PreviewRules.cents($0) + " cents per litre" } ?? tr("price_unknown"))")
                    }.annotationTitles(.hidden)
                }
            }
            if let location = model.deviceArea {
                Annotation(tr("your_location"), coordinate: CLLocationCoordinate2D(latitude: location.latitude, longitude: location.longitude)) {
                    Circle().fill(Color.blue).frame(width: 14, height: 14).overlay(Circle().stroke(.white, lineWidth: 3))
                }.annotationTitles(.hidden)
            }
        }
        .mapStyle(.standard(elevation: .flat, pointsOfInterest: .excludingAll))
        .mapControls { MapCompass(); MapScaleView() }
        .onMapCameraChange(frequency: .onEnd) { context in
            let center = context.region.center
            let longitude = ((center.longitude + 180).truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) - 180
            model.mapArea = try? SearchArea(latitude: center.latitude, longitude: longitude, label: tr("map_area"))
        }
        .onChange(of: model.centerRequest) { _, _ in
            if let current = model.area { position = .region(Self.region(current)) }
        }
    }
}
