# Security

OpenFuel is a public alpha for real station discovery and unverified community
price reports. It is not independently security-audited or an authenticated
station-moderation service. See [current verification](docs/BUILD_STATUS.md) and
[privacy behavior](docs/PRIVACY.md).

Keep Cloudflare/GitHub OAuth tokens, API secrets, local databases, report archives,
private evidence and signing keys outside the source tree. Public account IDs,
D1 database IDs and API base URLs are configuration, not credentials. Clients do
not need a secret. `.gitignore` and source packaging exclude private environment,
CLI, signing and build state. Review new files and run a secret scanner before
publishing a release; ignore rules cannot remove a secret already committed.

The current API validates bounded input, uses parameterized D1 queries, publishes
reports atomically and limits report volume. Those controls do not authenticate
contributors or verify pump prices. Test submissions must use local/isolated D1,
not real production stations. Production backups and report data stay private.

For a public GitHub repository, use its **Security → Report a vulnerability**
control when private vulnerability reporting is enabled. If that control is
unavailable, ask the maintainer for a private channel without including sensitive
details. No response-time SLA is promised. Do not post credentials, private data
or exploitable vulnerability details in a public issue.

The initial publication review is documented in [PUBLICATION_AUDIT.md](docs/PUBLICATION_AUDIT.md).
A clean automated scan reduces risk; it cannot prove that every possible secret
or unknown historical copy has been found. If a real credential is discovered,
revoke/rotate it before removing it from Git history and distribution archives.
