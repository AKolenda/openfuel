# Reference price API

FastAPI + SQLite event ledger, retained from the supplied source. The native Swift core consumes its
schema; the Android/web sample apps do not yet. All seed data is fictional. Writes remain disabled
unless a closed-test token is explicitly configured. This is not a public production-write endpoint.

Use root commands `api-seed`, `api`, and `check`. Default data: .local/openfuel.sqlite3, deliberately
excluded from packaging and web serving. The seed refuses a nonempty ledger. Use a fresh disposable
path for tests; do not overwrite an authoritative ledger.

The canonical schema is packages/contracts/openapi.json. Root `contracts --check` and the backend
contract test compare it with the actual app. Root `contracts` regenerates it after a reviewed change.
No database file is required just to generate or check OpenAPI.

The ledger implementation is append-only under normal SQL operations and exports exact payload bytes.
A trusted earlier checkpoint/pinned signing key is needed to detect operator rewrites or rollback;
hash consistency alone cannot prove a pump price or a malicious operator's honesty. See audit.py/tests.

Full source licence: AGPL-3.0-only. Publish a corresponding-source URL for the exact running revision
before operating a public network service. No company-logo or imported-data rights are granted here.
