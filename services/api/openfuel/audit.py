# SPDX-License-Identifier: AGPL-3.0-only
"""Byte-defined hash chain; no JSON reserialization needed to verify an event."""
from __future__ import annotations
import base64
import hashlib
import json
from pathlib import Path
from typing import Any, Iterable
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives import serialization

ZERO_HASH = '0' * 64
EVENT_DOMAIN = b'openfuel-event-v1\x00'
CHECKPOINT_DOMAIN = b'openfuel-checkpoint-v1\x00'

def canonical(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True,
                      allow_nan=False).encode('utf-8')

def event_hash(seq: int, previous: str, payload: bytes) -> str:
    return hashlib.sha256(EVENT_DOMAIN + bytes.fromhex(previous) + seq.to_bytes(8, 'big') + payload).hexdigest()

def envelope(seq: int, previous: str, payload: bytes, digest: str) -> dict:
    return {'seq': seq, 'previous_hash': previous,
            'payload_base64': base64.b64encode(payload).decode('ascii'), 'hash': digest}

def checkpoint_message(size: int, head: str) -> bytes:
    return CHECKPOINT_DOMAIN + size.to_bytes(8, 'big') + bytes.fromhex(head)

def checkpoint(size: int, head: str, key: Ed25519PrivateKey | None = None) -> dict:
    result = {'format': 'openfuel-chain-v1', 'tree_size': size, 'head_hash': head,
              'public_key_base64': None, 'signature_base64': None}
    if key:
        result['public_key_base64'] = base64.b64encode(key.public_key().public_bytes(
            serialization.Encoding.Raw, serialization.PublicFormat.Raw)).decode('ascii')
        result['signature_base64'] = base64.b64encode(key.sign(checkpoint_message(size, head))).decode('ascii')
    return result

def load_key(path: str | None) -> Ed25519PrivateKey | None:
    if not path:
        return None
    # Startup deliberately fails for an invalid configured key; no silent unsigned fallback.
    key = serialization.load_pem_private_key(Path(path).read_bytes(), password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise ValueError('Checkpoint key must be Ed25519')
    return key

def verify_export(lines: Iterable[str], anchor: dict | None = None,
                  pinned_public_key: str | None = None) -> dict:
    """Anchor must be acquired independently/previously retained. It may be an older prefix."""
    size, head, footer = 0, ZERO_HASH, None
    anchor_seen = anchor is None or (anchor.get('tree_size') == 0 and anchor.get('head_hash') == ZERO_HASH)
    for line in lines:
        if not line.strip():
            continue
        if footer is not None:
            raise ValueError('Data after checkpoint footer')
        item = json.loads(line)
        if set(item) == {'checkpoint'}:
            footer = item['checkpoint']
            continue
        if set(item) != {'seq', 'previous_hash', 'payload_base64', 'hash'}:
            raise ValueError('Invalid event envelope')
        if type(item['seq']) is not int or item['seq'] != size + 1 or item['previous_hash'] != head:
            raise ValueError('Non-contiguous chain')
        payload = base64.b64decode(item['payload_base64'], validate=True)
        digest = event_hash(item['seq'], head, payload)
        if digest != item['hash']:
            raise ValueError('Event hash mismatch')
        size, head = item['seq'], digest
        if anchor and size == anchor['tree_size']:
            if head != anchor['head_hash']:
                raise ValueError('History differs from trusted anchor')
            anchor_seen = True
    if not footer or footer.get('format') != 'openfuel-chain-v1':
        raise ValueError('Missing/invalid checkpoint footer')
    if footer.get('tree_size') != size or footer.get('head_hash') != head:
        raise ValueError('Checkpoint does not match export')
    if not anchor_seen:
        raise ValueError('Export truncated before trusted anchor')
    if pinned_public_key:
        public = Ed25519PublicKey.from_public_bytes(base64.b64decode(pinned_public_key, validate=True))
        if not footer.get('signature_base64'):
            raise ValueError('Expected signed checkpoint')
        public.verify(base64.b64decode(footer['signature_base64'], validate=True), checkpoint_message(size, head))
    return footer
