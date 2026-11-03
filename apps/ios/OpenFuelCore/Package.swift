// swift-tools-version: 5.9
// SPDX-License-Identifier: AGPL-3.0-only
import PackageDescription
let package = Package(
    name: "OpenFuelCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "OpenFuelCore", targets: ["OpenFuelCore"])],
    targets: [
        .target(name: "OpenFuelCore")
    ]
)
