# Remaining integration and release gates

The prototype website and API are deployed on Cloudflare, and the shared D1 database is provisioned
with sample stations and persistent test reports. Browser, Android and Swift clients use the same
prototype contract. An Android debug APK is built for direct installation; the Swift client has
passed a real HTTPS fetch/report/retry/fetch check. These are implemented prototype capabilities,
not evidence of nationwide coverage or a finished store release. See [BUILD_STATUS.md](BUILD_STATUS.md)
and [the iOS evidence](../apps/ios/BUILD_EVIDENCE.md) for the executed checks and their limits.

1. **Real station and price data.** The map, station addresses, distances, opening states and initial
   prices are fictional. Shared contributions are test reports. A real release still needs licensed
   station geography, a real-map adapter, price sourcing and verification, freshness rules and
   coverage checks. No device location is currently acquired or sent to the API.
2. **Contribution operations.** Random installation IDs and edge/database limits bound prototype
   reporting; they do not establish trusted publishers or verified observations. Native station
   suggestions and closure reports remain local drafts. A production intake and moderation workflow,
   contributor roles and source-rights review remain to be implemented. There is no offline upload
   queue; submitting a price needs a connection.
3. **Native release validation.** Build and run the SwiftUI app on macOS with Xcode, then capture real
   simulator/device evidence. Linux Swift tests and syntax parsing do not validate the Apple SDK UI.
   The Android debug APK is a prototype build, not a signed store release. Both platforms still need
   wider device, accessibility, large-text, French-language, lifecycle and network-failure testing,
   plus signing and store-distribution work. Native screenshots alone do not constitute approved
   visual parity.
4. **Data operations and privacy.** The shared D1 prototype needs tested backup/restore procedures,
   report retention or archival, deletion handling and operational ownership before broader use.
   Its report capacity ceiling requires maintainer action when full. Review real hosting metadata,
   provider configuration and asset permissions for the intended release. See [PRIVACY.md](PRIVACY.md)
   for current local and remote data flows; no data-residency or legal-compliance guarantee is implied.
5. **Optional reference backend.** The repository's FastAPI/SQLite and Supabase designs are separate
   from the deployed D1 prototype. Supabase migrations, pgTAP checks, RPC integration, publisher and
   moderator authorization, private-evidence expiry and public audit/export mechanisms still need
   their own execution and review if that architecture is adopted. They are not required to run the
   current Cloudflare prototype and are not claimed as deployed capabilities.

Committed tests and workflows make further checks reproducible. A workflow file is not evidence
that a hosted workflow ran, and a successful prototype submission does not verify a real fuel price.
