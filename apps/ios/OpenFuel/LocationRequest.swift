// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import CoreLocation
import OpenFuelCore

/// One foreground fix per explicit request. A manual area cancels an outstanding fix.
@MainActor
final class LocationRequest: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var completion: ((Result<CLLocation, Error>) -> Void)?
    private var timeout: Task<Void, Never>?
    private var waitingForAuthorization = false
    override init() {
        super.init(); manager.delegate = self; manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }
    func request(_ completion: @escaping (Result<CLLocation, Error>) -> Void) {
        cancel(); self.completion = completion
        switch manager.authorizationStatus {
        case .notDetermined: waitingForAuthorization = true; manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways: startFix()
        default: finish(.failure(LocationFailure.permissionDenied))
        }
    }
    func cancel() { timeout?.cancel(); timeout = nil; completion = nil; waitingForAuthorization = false; manager.stopUpdatingLocation() }
    private func startFix() {
        waitingForAuthorization = false
        timeout?.cancel()
        timeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(20)) } catch { return }
            self?.finish(.failure(LocationFailure.unavailable))
        }
        manager.requestLocation()
    }
    private func finish(_ value: Result<CLLocation, Error>) {
        let callback = completion; cancel(); callback?(value)
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard completion != nil, waitingForAuthorization else { return }
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: startFix()
        case .denied, .restricted: finish(.failure(LocationFailure.permissionDenied))
        default: break
        }
    }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let fix = locations.last, fix.horizontalAccuracy >= 0,
              abs(fix.timestamp.timeIntervalSinceNow) < 120,
              CLLocationCoordinate2DIsValid(fix.coordinate) else { finish(.failure(LocationFailure.unavailable)); return }
        finish(.success(fix))
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) { finish(.failure(error)) }
}
private enum LocationFailure: Error, LocalizedError {
    case permissionDenied, unavailable
    var errorDescription: String? {
        switch self {
        case .permissionDenied: return tr("location_denied")
        case .unavailable: return tr("location_unavailable")
        }
    }
}
