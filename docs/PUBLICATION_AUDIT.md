# Initial source publication review

The review covered the source allowlist in `tools/project.py`, the generated source
ZIP, repository ignore/packaging rules, relevant existing source directories and
original OpenFuel ZIP bundles. Machine-readable results are recorded in
`evidence/publication-scan.json`. Scans run with secret values redacted.

No `.git` directory or Git metadata file was present in the current workspace.
A bounded search of nearby OpenFuel source copies also found no Git repository;
eight original local OpenFuel archives contained no `.git` entries. Therefore
there was no recovered local Git history to scan or rewrite. The checked GitHub
account had no existing owned repository with “fuel” in its name at that time.
This does not establish that no historical copy exists elsewhere.

The initial source scan used Gitleaks 8.30.1, downloaded from its official release
and verified against its upstream SHA-256 checksum, plus a supplemental scan for
private-key blocks, provider tokens, JWTs, credential-bearing URLs and secret
assignments. The supplemental matches were synthetic test data and code syntax.
The unconfigured Gitleaks scan reported two reviewed false positives:

- A deliberately fake Supabase publishable-key fixture in `services/edge/worker.test.mjs`.
- A Python `Ed25519PrivateKey` parameter type annotation in `services/api/openfuel/audit.py`.

`.gitleaks.toml` limits exceptions to those exact values/matches in those exact
paths; it does not exclude either entire file or all test files. The configured
source and archive scans found no credential leaks. A clean scanner result is
not proof that every conceivable secret format is absent.

Ignore and packaging rules exclude `.local`, `.wrangler`, environment values,
Expo/device state, dependency caches, native builds, databases and journals,
private signing keys/profiles, credential files and symlinked trees. Public
Cloudflare account/database identifiers and API origins are retained because
they are configuration, not authentication material. Sample `.env.example`
files document public configuration and placeholders. Production database
contents, provider authentication files and Android signing state stay local.

## Repeat before publishing

Install Gitleaks 8.30.1 from the official release and verify its checksum, then:

```sh
# Once a Git repository exists, scan every reachable commit/ref:
gitleaks git --redact --no-banner --log-opts="--all" .
python3 tools/project.py package
gitleaks dir dist/openfuel-source.zip --config .gitleaks.toml --redact --no-banner --max-archive-depth 2
```

The pinned `secret-scan.yml` workflow repeats the full-history and source-archive
checks on GitHub. It is a guard for future changes, not evidence of an executed
remote workflow until GitHub reports a result. Publishing a new clean initial
commit does not scan or sanitize unknown archives/repositories elsewhere. If
older Git history is recovered later, inspect every branch/tag and unreachable
objects before incorporating or redistributing it. Revoke a discovered real
credential before cleaning history and replacing published archives.
