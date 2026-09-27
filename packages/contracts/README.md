# API contracts

`live-openapi.json` describes the deployed Cloudflare API used by the website, Android, Expo and iOS.
The website publishes it at `/openapi.json`. See docs/LIVE_API.md for examples, units, errors and
the daily limit answer.

`prototype-openapi.json` describes the earlier synthetic-station prototype API and is historical.

`openapi.json` is generated from the retained FastAPI service, not the deployed API.
Run `python3 tools/project.py contracts` to regenerate that reference or `contracts --check`
to verify it.
