// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import ImageIO
import UIKit
import OpenFuelCore

private final class LogoRedirectPolicy: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(RemoteBrandLogo.validatedURL(request.url?.absoluteString) == nil ? nil : request)
    }
}

/// Shared between list and map, bounded on-device cache; no server-side image proxy.
private actor BrandLogoStore {
    static let shared = BrandLogoStore()
    private let cache = NSCache<NSURL, NSData>()
    private var requests: [URL: Task<Data?, Never>] = [:]
    private let session: URLSession
    init() {
        cache.countLimit = 64; cache.totalCostLimit = 8 * 1024 * 1024
        let config = URLSessionConfiguration.default
        config.urlCache = URLCache(memoryCapacity: 4 * 1024 * 1024, diskCapacity: 16 * 1024 * 1024, diskPath: "OpenFuelBrandLogos")
        config.httpShouldSetCookies = false; config.httpCookieStorage = nil
        config.timeoutIntervalForRequest = 12; config.timeoutIntervalForResource = 18
        config.httpMaximumConnectionsPerHost = 4
        session = URLSession(configuration: config, delegate: LogoRedirectPolicy(), delegateQueue: nil)
    }
    func data(for url: URL) async -> Data? {
        if let value = cache.object(forKey: url as NSURL) { return value as Data }
        if let pending = requests[url] { return await pending.value }
        let session = self.session
        let task = Task<Data?, Never> {
            do {
                var request = URLRequest(url: url)
                request.setValue("OpenFuel-iOS/0.4 (+https://openfuel.ca)", forHTTPHeaderField: "User-Agent")
                let (bytes, response) = try await session.bytes(for: request)
                guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                      RemoteBrandLogo.validatedURL(http.url?.absoluteString) != nil,
                      ["image/png", "image/jpeg", "image/webp", "image/gif"].contains(http.mimeType ?? ""),
                      response.expectedContentLength <= 2_000_000 else { return nil }
                var data = Data()
                for try await byte in bytes { data.append(byte); if data.count > 2_000_000 { return nil } }
                return data
            } catch { return nil }
        }
        requests[url] = task
        let result = await task.value; requests[url] = nil
        if let result { cache.setObject(result as NSData, forKey: url as NSURL, cost: result.count) }
        return result
    }
}

@MainActor
private enum BrandImageCache {
    static let images: NSCache<NSURL, UIImage> = {
        let cache = NSCache<NSURL, UIImage>(); cache.countLimit = 64; cache.totalCostLimit = 8 * 1024 * 1024; return cache
    }()
    static func image(for url: URL) async -> UIImage? {
        if let image = images.object(forKey: url as NSURL) { return image }
        guard let data = await BrandLogoStore.shared.data(for: url), !Task.isCancelled else { return nil }
        if let image = images.object(forKey: url as NSURL) { return image }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: 128,
                kCGImageSourceCreateThumbnailWithTransform: true
              ] as CFDictionary) else { return nil }
        let image = UIImage(cgImage: thumbnail)
        images.setObject(image, forKey: url as NSURL, cost: thumbnail.bytesPerRow * thumbnail.height)
        return image
    }
}

struct BrandLogoView: View {
    let station: PreviewStation
    var size: CGFloat = 34
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.23).fill(Color.white)
            if let image { Image(uiImage: image).resizable().scaledToFit().padding(3) }
            else { Text(station.initials).font(.system(size: size * 0.36, weight: .bold)).foregroundStyle(Color.fuelGreen) }
        }
        .frame(width: size, height: size).accessibilityHidden(true)
        .task(id: station.brandLogoUrl) {
            image = nil
            guard let url = RemoteBrandLogo.validatedURL(station.brandLogoUrl) else { return }
            image = await BrandImageCache.image(for: url)
        }
    }
}
