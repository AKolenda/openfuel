# Canonical API example

api-stations.json is the exact fictional response fixture supplied with the Swift package.
Its bundled copy remains in apps/ios/OpenFuelCore/Tests/OpenFuelCoreTests/stations.json.
The root fixtures command and repository tests enforce byte parity. It is CC0 synthetic data.

Android has six offline brand-reference examples in its own Core.kt; the reference API has a
three-station synthetic fixture. They are not silently conflated. A future shared-data migration
must define the adapter and behaviour semantics and run both clients' tests.
