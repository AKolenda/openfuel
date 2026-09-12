# Station registry model

Pure Python proposal/review-policy model plus an unexecuted PostgreSQL/PostGIS schema draft.
Run `python -m pytest -q` in this directory, or root `python tools/project.py check`.

Three proposal-scoped attestations increase triage priority, not automatic publication. New records
need checked evidence/review; permanent closure requires two distinct reviewer strings. Those strings
are inputs to a model, NOT an authentication/authorization system. An actual service must establish
who may review, prevent self-review, enforce source rights, and handle coordinated abuse.

Stable station IDs, status/version checking and retained correction history are covered by the model.
Public events omit internal attesters/reviewers. A real PostgreSQL transaction/API, ingestion workers,
source reconciliation, retention jobs, independent checkpoint witnesses and permissions still need
implementation. Do not infer a production deployment from the passing pure-logic tests.

Detailed inherited proposal: docs/reference/DATABASE_AND_STATION_LIFECYCLE.md.
Licence: AGPL-3.0-only for original policy/schema code; source data licensing is a separate decision.
