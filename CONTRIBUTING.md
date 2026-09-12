# Contributing

Work from the monorepo root. The current API is `services/live`; clients are
`apps/web`, `apps/android` and `apps/expo`. The handbook is `apps/docs`.
`apps/ios`, `services/edge`, `services/api`, `services/registry` and `supabase`
retain earlier platform/reference work; check the current status before treating
an older implementation as deployed behavior.

Contribute only material you may provide under the relevant project licence and
preserve third-party notices. Original source/documentation is AGPL-3.0-only;
synthetic test fixtures are CC0. The real station, city and reference-statistics
files have separate licences in [packages/data](packages/data/README.md). Public
availability alone does not grant rights to copy a logo, dataset or package.

## Check a change

- `npm run test:live` for the active Worker, input validation and local D1 behavior.
- `npm run test:edge` for the retained Worker references.
- `npm run build` for website, handbook, contract and source packaging.
- `python3 -m pytest -q tests` for repository and packaging checks.
- `npm --prefix apps/expo run typecheck` and `npm --prefix apps/expo test` for Expo.
- Follow the Android/iOS READMEs for platform builds and device checks.

Run the checks relevant to changed components. Generate contract and handbook
outputs deliberately and inspect them. A browser screenshot or JavaScript bundle
export does not prove native-device behavior. Describe which API, device or
emulator and data were used when reporting a result.

Use synthetic data only in tests and isolated local databases. Do not submit
invented pump prices to production. Never commit production reports, user location
history, private station evidence, local databases or credentials. Check source
and Git history for secrets before publication; see [publication audit](docs/PUBLICATION_AUDIT.md).

Keep stations with missing prices visible, distinguish dated averages from pump
observations, show report age and unverified status, preserve map attribution, and
allow city/map search when location permission is declined. Saved data must retain
its age and should never be silently presented as a fresh network result.
