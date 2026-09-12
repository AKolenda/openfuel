# Android build evidence

Verified on 2026-09-12 UTC in this workspace. The native Kotlin / Jetpack Compose application was
compiled, installed and exercised in an Android emulator. These are actual Android screenshots
and device tests; no claim of pixel-perfect parity with the web or Swift UI is made.

## Downloadable artifact

- File: `app/build/outputs/apk/debug/app-debug.apk`
- Size: **17,465,610 bytes** (16.66 MiB)
- SHA-256: `b41200c3a0f662c178c1ad2e06a588612298478e8437de8e23d6c5a7d252be20`
- Package: `ca.openfuel.prototype`
- Version: `0.1.0-prototype` / version code 2
- Minimum Android version: Android 8.0 / API 26
- Target / compile SDK: API 35
- Public API: `https://openfuel-prototype.openfuel-monorepo.workers.dev/api/v1`

`apksigner verify --verbose` passed using APK Signature Scheme v2 with one signer. This is a
**debug APK**, signed with this workstation’s generated Android debug key. It is suitable for
sideloading and prototype testing, not a Play Store release. Android may require allowing installs
from the browser or file manager. Updates require the same signing key; independently built debug
APKs can require uninstalling the prior installation, which removes its local state. No production
signing key or credential is included in the repository.

## Executed verification

1. Gradle `:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest` succeeded.
   All **4 JVM unit tests** passed: exact price parsing, stale-report ordering, draft review state
   and safe sample Maps handoff. Log: [android-final-build.txt](../../evidence/android-final-build.txt).
2. Installed the APK and instrumentation APK on `openfuel-api35`, a Pixel 6 profile running an
   Android 15 / API 35 x86_64 Google APIs system image with KVM acceleration.
3. All **3 device tests** passed. The two native UI tests exercised Cards/List and sorting, and
   captured **9 native screens**: List, Cards, wide buttons, sort, settings, about, detail, report
   and empty results. The third test submitted a sample report through the native report form,
   received a Cloudflare acknowledgement, verified the price through a fresh API GET and checked
   persistence using a new repository instance.
   Log: [android-emulator.txt](../../evidence/android-emulator.txt).
   Separate cloud test log: [android-cloud-sync.txt](../../evidence/android-cloud-sync.txt).
4. Manually verified the normal app launch displayed `Sample prices · synced to shared prototype`.
   Disabled Wi-Fi and mobile data **on the emulator**, force-stopped and relaunched the app,
   and verified cached prices remained visible with `Offline · showing saved or sample prices`.
   Emulator connectivity was restored after this check.
5. Visually inspected actual list/report screenshots and the connected/cached-offline states.

Screenshots: [native screen set](../../evidence/android-native/screenshots),
[connected](../../evidence/android-native/android-connected.png),
[cached offline](../../evidence/android-native/android-cached-offline.png).

The complete UI test run supersedes an earlier failure while the newly deployed Cloudflare hostname
was not yet reachable. The final network and device test logs above both pass.

## Toolchain

- Eclipse Temurin JDK 17.0.20.1+1, downloaded with its published SHA-256 verified.
- Gradle 8.11.1, downloaded with its published SHA-256 verified; the generated Gradle wrapper pins
  that distribution checksum.
- Android command-line tools archive `commandlinetools-linux-13114758_latest.zip`.
- Android SDK platform 35, build tools 35.0.0 and platform-tools.
- Android Gradle plugin 8.9.2; Kotlin and Compose compiler plugin 2.1.20.
- Compose BOM 2025.04.01.

The toolchain is installed outside the repository under
`/home/bucic/.local/share/openfuel-toolchains`. See [README.md](README.md) for portable build commands.

## Prototype limits

Stations, geography, distances and starting prices are fictional. Confirmed reports change the
shared Cloudflare sample database; this build is not a source of verified real fuel prices. The
illustrated map has no paid tile service. Favorites, preferences, cached stations and suggestion
drafts persist locally. Suggestions are not submitted for moderation. HTTPS requests run off the
main thread; reports update prices only after acknowledgement. Failed identical reports retain a
request ID for safe retries. This build has no location, advertising or analytics permissions.
