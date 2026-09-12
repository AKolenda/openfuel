# API contracts

`prototype-openapi.json` describes the deployed Cloudflare API used by website, Android and iOS.
The website publishes it at `/openapi.json`. See docs/PROTOTYPE_API.md for examples and units.

`openapi.json` is generated from the retained FastAPI service, not the deployed prototype.
Run `python3 tools/project.py contracts` to regenerate that reference or `contracts --check`
to verify it. Native prototype clients use the Cloudflare contract.
