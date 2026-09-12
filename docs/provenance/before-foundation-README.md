# OpenFuel · one repository

Canada-first fuel-price project: **website + documentation + native Android + native iOS**, with
an executable reference API and a separate station-review model. First-party code is **AGPL-3.0-only**.

This repository consolidates the two supplied source archives and the newer documentation design.
It contains source, not a verified APK, an App Store release, a live price feed, or a deployed database.
All sample station positions, prices, hours and report ages are fictional.

## Start the website and documentation

Python 3.11+ is the only prerequisite for the static website. From the repository root:

```sh
python tools/project.py serve
```

Open `http://127.0.0.1:4173/`. Website variant A is the home page. Documentation lives at `/docs/`,
the interactive map reference at `/preview/`, and the preserved alternative website at
`/designs/variant-b/`. They are one static site, with working relative links and a source ZIP download.
The server serves only `dist/site`, **not the repository or your local database**.

Pages are editable HTML/CSS/JavaScript, not giant base64 copies of one another. Changes to the map
reference are picked up by both the site and the docs through the same `/preview/` route. Re-run
`serve`/`site` after editing: the simple builder intentionally does not run a hidden watch process.

## Repository structure

```text
openfuel/
├── apps/
│   ├── web/                  # Variant A, live-in-browser prototype, retained variant B
│   ├── docs/                 # Cleaned handbook UI and structured page content
│   ├── android/              # Kotlin / Jetpack Compose project
│   └── ios/                  # SwiftUI app + OpenFuelCore package + XcodeGen spec
├── services/
│   ├── api/                  # FastAPI / SQLite event-ledger reference
│   └── registry/             # Review policy tests + proposed PostgreSQL/PostGIS schema
├── packages/
│   ├── contracts/            # Generated OpenAPI, checked against the service
│   ├── fixtures/             # Canonical API fixture, checked against Swift test resources
│   ├── assets/               # Website artwork/screenshots, stored once
│   └── design/               # Visual specification reference (not a shared UI runtime)
├── docs/                     # Build, architecture, scope, provenance and licence notes
├── tests/                    # Monorepo integrity + browser integration checks
├── tools/project.py          # Root build / serve / check / package entry point
├── evidence/                 # Logs from checks actually executed on this consolidation
└── .github/workflows/        # Quality checks, website artifact, Android APK, iOS build
```

## What each app really does

* **Web** is the most developed visual prototype. It uses local fictional data, not the API.
* **Android** is the supplied native Compose prototype with an offline illustrated map and sample data.
  It is not connected to the reference API. The two uploaded ZIPs contain identical Android source.
* **iOS** is the supplied earlier API-driven SwiftUI list/detail starter. It reads the local reference
  API, but **does not yet match the Android/web map-first interface**. Consolidation does not pretend
  that a visual port or native device testing has happened.
* **Registry** is a reviewed-change policy model and SQL draft, not a deployed contribution service.

See [verification status](docs/BUILD_STATUS.md) and [merge report](docs/MERGE_REPORT.md). Older claims
and logs from the incoming bundle are preserved separately under `docs/provenance`/`docs/reference`.

## Root commands

| Command | Purpose |
| --- | --- |
| `python tools/project.py doctor` | Show installed tools and missing native prerequisites |
| `python tools/project.py serve` | Build and serve website + docs locally on port 4173 |
| `python tools/project.py site` | Generate static deployment output at `dist/site/` |
| `python tools/project.py check` | Repository, API, registry and contract checks |
| `python tools/project.py check --web --native-cores` | Also run browser, Kotlin core and Swift package checks |
| `python tools/project.py android-build` | Run native Android tests and build a debug APK when SDK/Gradle are installed |
| `python tools/project.py ios-build` | Generate Xcode project and compile for iOS Simulator on a Mac |
| `python tools/project.py api-seed` | Seed an empty local database with fictional records |
| `python tools/project.py api` | Run the reference API on localhost:8000, write-disabled by default |
| `python tools/project.py package` | Create `dist/openfuel-source.zip`, excluding secrets/build artifacts |

A root `Makefile` wraps these commands (`make dev`, `make check-all`, `make android`, `make ios`).
No pnpm/Turborepo/Nx layer is needed for these native projects. This is one Git history with independent
build systems, **not** a shared React Native application.

## Test prerequisites

```sh
python -m venv .venv
# macOS/Linux:
. .venv/bin/activate
# Windows PowerShell: .venv\Scripts\Activate.ps1
python -m pip install -r requirements-dev.txt
python -m playwright install chromium
python tools/project.py check --web
```

Kotlin core checks additionally require `kotlinc` and a JDK; Swift package checks require Swift.
Native Android uses the imported Gradle 8.11.1 / AGP 8.9.2 / Kotlin 2.1.20 / SDK 35 pins.
A full iOS build requires macOS, Xcode and XcodeGen. Tool checks fail explicitly; they do not report
an absent native build as a passing test. See [build instructions](docs/BUILD.md).

## Add it to GitHub later

Upload **the contents of this folder** as the repository root, or initialize Git here. There are no
submodules and no second `.git` directory inside either app. No repository URL or personal identity
has been guessed. The workflows use root-relative paths and read-only repository permissions.
They are configured but have not been run on GitHub in this delivery.

```sh
git init -b main
git add .
git commit -m "Bring OpenFuel apps, website and docs into one repository"
# Add your own remote, then push. Configure signing/deployment separately.
```

Review [SECURITY.md](SECURITY.md), [licence scope](LICENSE.md), and the included source provenance before
publishing. Do not commit local database files, app signing keys, service tokens or private evidence.
