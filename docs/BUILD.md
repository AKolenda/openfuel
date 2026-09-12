# Build from one repository root

## Website + documentation

For the complete website + API + local SQLite prototype, use Node 24 and Python 3.11+:

```sh
npm ci
npm run dev
# http://localhost:8787/
```

For a static-only design preview:

```sh
python tools/project.py serve
# http://127.0.0.1:4173/
# http://127.0.0.1:4173/docs/
# http://127.0.0.1:4173/preview/
```

`site` builds a portable dist/site tree with relative links, so it can also be hosted under a URL
subdirectory (such as a repository-named Pages path). The builder copies public assets only, includes
an honest source download, and derives the docs evidence panel from actually recorded local checks.
It never publishes a running API, a database or environment files. Deploying hosting is a separate step.
`dist/site` is generated and ignored; make source changes under apps/web and apps/docs, then rebuild.

## Test dependencies

```sh
python -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements-dev.txt
python -m playwright install chromium
python tools/project.py check --web
```

On Windows use `.venv\Scripts\Activate.ps1`. Set CHROMIUM_PATH for a system Chromium executable;
otherwise Playwright's installed Chromium is used. Browser tests serve only the built static site and
block requests outside that test origin, including remote brand logos.

## Android

Imported pins: Gradle 8.11.1, Android Gradle Plugin 8.9.2, Kotlin 2.1.20, SDK 35 / build-tools 35.0.0,
JDK 17 or a compatible newer runtime. Install the SDK and accept its licences interactively first.

```sh
export ANDROID_HOME=/path/to/Android/Sdk
python tools/project.py android-core
python tools/project.py android-build
```

The core command needs kotlinc/JDK but not the Android SDK. The full command requires the pinned Gradle
and outputs apps/android/app/build/outputs/apk/debug/app-debug.apk only after a successful build.
There is no fake Gradle wrapper: the input archive did not include its JAR. With Gradle installed,
`cd apps/android && gradle wrapper --gradle-version 8.11.1` generates a real wrapper for review.
Run the manual native-Android workflow after publishing the root tree to your GitHub repository.

## iOS

The input contains a valid Swift package and an XcodeGen spec, not a pre-generated Xcode project.
On macOS with Xcode and XcodeGen installed:

```sh
python tools/project.py ios-core
python tools/project.py ios-generate
open apps/ios/OpenFuel.xcodeproj
# Or compile for a generic simulator without a signing identity:
python tools/project.py ios-build
```

Swift package tests run on Linux as well. Full SwiftUI/iOS builds do not. Choose an installed simulator
and configure your own team for a device build. apps/ios/project.yml is the committed source of project
configuration; generated Xcode output is ignored, and the CI job generates it before building.

The native apps default to the deployed HTTPS prototype API. No local FastAPI server is needed.
Override the public endpoint using the platform README; never embed database credentials.

## API / registry

`python tools/project.py check` executes tests for both. The API is a development FastAPI/SQLite
reference. Registry SQL is a draft requiring a real PostgreSQL/PostGIS validation pass. Registry
review rules are pure tested logic, not an authenticated public-write endpoint.

## Artifact boundaries

- `python tools/project.py package`: source ZIP with a SHA256SUMS manifest; no platform binaries.
- `android-build`: debug APK only if the full native build actually succeeds.
- `ios-build`: unsigned simulator build output, not an App Store archive.
- CI jobs are configuration until a real workflow run produces evidence/artifacts.

For a launch, add reviewed signing, pinned CI action SHAs, dependency inventories, device UI tests,
actual map-data rights, backend permission checks, privacy assessment and a public source offer.
