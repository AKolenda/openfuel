# SPDX-License-Identifier: AGPL-3.0-only
"""Executable station-review policy model, not an authenticated production service.

Caller authentication, actual independence of reports, evidence storage, persistence,
rate limiting and reviewer authorization are intentionally out of scope. This module
shows the intended decisions; it must not be directly exposed as a public API.
"""
from __future__ import annotations
from dataclasses import dataclass, field, asdict, replace
from enum import Enum
import hashlib
import hmac
import json
from math import radians, sin, cos, atan2, sqrt

class Status(str, Enum):
    ACTIVE = "active"
    TEMPORARY_CLOSED = "temporarily_closed"
    PERMANENT_CLOSED = "permanently_closed"
    DUPLICATE = "duplicate"

class Change(str, Enum):
    NEW = "station_added"
    EDIT = "details_corrected"
    TEMP_CLOSE = "temporary_closure_verified"
    PERM_CLOSE = "permanent_closure_verified"
    REOPEN = "reopened"

@dataclass(frozen=True)
class Station:
    id: str
    name: str
    latitude: float
    longitude: float
    status: Status = Status.ACTIVE
    version: int = 1

    def __post_init__(self):
        if not (-90 <= self.latitude <= 90 and -180 <= self.longitude <= 180):
            raise ValueError("Invalid station coordinates")
        if not 2 <= len(self.name.strip()) <= 80:
            raise ValueError("Station name must be 2–80 characters")
        if not self.id or self.version < 1:
            raise ValueError("Station ID and positive version are required")

@dataclass
class Proposal:
    id: str
    change: Change
    proposed: Station
    base_version: int | None
    # Never exported. Per-proposal HMACs reduce cross-proposal linkage in storage.
    _attesters: set[str] = field(default_factory=set, repr=False)
    _reviewers: set[str] = field(default_factory=set, repr=False)
    # This is a moderator assessment, not a user-supplied boolean in a live service.
    evidence_checked: bool = False
    state: str = "pending_review"

    def attest(self, scoped_token: str) -> None:
        if self.state != "pending_review":
            raise ValueError("Proposal already resolved")
        if len(scoped_token) != 64 or any(c not in '0123456789abcdef' for c in scoped_token):
            raise ValueError("Expected a per-proposal HMAC token")
        self._attesters.add(scoped_token)

    @property
    def triage_ready(self) -> bool:
        """Three distinct tokens prioritize a review. They never authorize publishing."""
        return len(self._attesters) >= 3

    def review(self, authorized_reviewer_id: str, *, evidence_checked: bool) -> None:
        if self.state != "pending_review":
            raise ValueError("Proposal already resolved")
        if not authorized_reviewer_id:
            raise ValueError("Authenticated reviewer required")
        if not evidence_checked:
            raise ValueError("Review requires evidence")
        self.evidence_checked = True
        self._reviewers.add(authorized_reviewer_id)

    def publish(self, current: Station | None) -> Station:
        if self.state != "pending_review":
            raise ValueError("Proposal already resolved")
        required_reviews = 2 if self.change == Change.PERM_CLOSE else 1
        if not self.evidence_checked or len(self._reviewers) < required_reviews:
            raise ValueError("Moderator review required; votes alone never publish")
        if self.change == Change.NEW:
            if current is not None or self.base_version is not None:
                raise ValueError("New station conflicts with an existing record")
            result = replace(self.proposed, status=Status.ACTIVE, version=1)
        else:
            if current is None or current.id != self.proposed.id or current.version != self.base_version:
                raise ValueError("Stale proposal: re-review against current station version")
            if self.change == Change.TEMP_CLOSE:
                if current.status != Status.ACTIVE:
                    raise ValueError("Temporary closure requires an active station")
                result = replace(current, status=Status.TEMPORARY_CLOSED, version=current.version + 1)
            elif self.change == Change.PERM_CLOSE:
                if current.status not in (Status.ACTIVE, Status.TEMPORARY_CLOSED):
                    raise ValueError("This station is already closed or superseded")
                result = replace(current, status=Status.PERMANENT_CLOSED, version=current.version + 1)
            elif self.change == Change.REOPEN:
                if current.status not in (Status.PERMANENT_CLOSED, Status.TEMPORARY_CLOSED):
                    raise ValueError("Reopening requires a closed station")
                result = replace(current, status=Status.ACTIVE, version=current.version + 1)
            else:
                # Ordinary edits cannot smuggle in a status change.
                result = replace(self.proposed, status=current.status, version=current.version + 1)
        self.state = "accepted"
        return result

    def reject(self) -> None:
        if self.state != "pending_review":
            raise ValueError("Proposal already resolved")
        self.state = "rejected"


def scoped_attester(secret: bytes, proposal_id: str, account_id: str) -> str:
    if len(secret) < 32:
        raise ValueError("A random server secret of at least 32 bytes is required")
    message = json.dumps([proposal_id, account_id], separators=(",", ":")).encode()
    return hmac.new(secret, message, hashlib.sha256).hexdigest()


def nearby_candidates(proposed: Station, existing: list[Station], radius_m: float = 80) -> list[Station]:
    """Duplicate suggestions, NOT automatic merges (opposite carriageways may be distinct)."""
    if radius_m <= 0:
        raise ValueError("Radius must be positive")
    def distance(s: Station) -> float:
        a,b = radians(proposed.latitude), radians(s.latitude)
        delta_lat = b-a; delta_lon = radians(s.longitude-proposed.longitude)
        v = sin(delta_lat/2)**2 + cos(a)*cos(b)*sin(delta_lon/2)**2
        return 6371008.8 * 2 * atan2(sqrt(max(0,v)), sqrt(max(0,1-v)))
    return sorted((s for s in existing if distance(s) <= radius_m), key=distance)


def canonical(value: dict) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode()


def append_public_event(ledger: list[dict], proposal: Proposal, station: Station) -> dict:
    """No reporter, moderator, evidence, device, network, free-text note or exact time fields."""
    if proposal.state != "accepted":
        raise ValueError("Only a verified decision becomes a public event")
    if station.id != proposal.proposed.id:
        raise ValueError("Station mismatch")
    payload = {"seq":len(ledger)+1, "previous":ledger[-1]["hash"] if ledger else "0"*64,
               "event":proposal.change.value, "station":asdict(station)}
    payload["hash"] = hashlib.sha256(canonical(payload)).hexdigest()
    ledger.append(payload)
    return payload


def verify_ledger(ledger: list[dict], pinned_checkpoint: tuple[int, str] | None = None) -> bool:
    previous="0"*64
    for seq, event in enumerate(ledger,1):
        body={k:v for k,v in event.items() if k!="hash"}
        if set(event) != {"seq","previous","event","station","hash"}:
            return False
        if event["seq"]!=seq or event["previous"]!=previous:
            return False
        if hashlib.sha256(canonical(body)).hexdigest()!=event["hash"]:
            return False
        previous=event["hash"]
    if pinned_checkpoint:
        seq, digest=pinned_checkpoint
        if seq < 1 or len(ledger)<seq or ledger[seq-1]["hash"]!=digest:
            return False
    return True
