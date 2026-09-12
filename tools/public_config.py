# SPDX-License-Identifier: AGPL-3.0-only
"""Explicit public build allowlist. Not a general dotenv loader or secret exporter."""
from pathlib import Path
from urllib.parse import urlsplit
import json
import os

KEYS = ('OPENFUEL_PUBLIC_ENV', 'OPENFUEL_PUBLIC_API_BASE_URL', 'OPENFUEL_PUBLIC_SOURCE_URL')
DEFAULTS = dict(zip(KEYS, ('development', '/api/v1', '/downloads/openfuel-source.zip')))

def read_public_config(root: Path, environ: dict | None = None) -> dict:
    values = dict(DEFAULTS)
    envfile = root / '.env'
    if envfile.exists():
        for line in envfile.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith('#'): continue
            if '=' not in line: raise ValueError('Invalid .env assignment')
            key, value = line.split('=', 1)
            if key.strip() in KEYS:
                values[key.strip()] = value.strip().strip('"\'')
    environment = os.environ if environ is None else environ
    for key in KEYS:
        if key in environment: values[key] = environment[key]
    if values[KEYS[0]] not in ('development','staging','production'):
        raise ValueError('OPENFUEL_PUBLIC_ENV must be development, staging or production')
    for key in KEYS[1:]:
        v = values[key]
        u = urlsplit(v)
        relative = v.startswith('/') and not v.startswith('//') and '\\' not in v
        absolute = u.scheme == 'https' and bool(u.hostname) and not u.username and not u.password
        if not (relative or absolute) or u.query or u.fragment or any(c in v for c in '\r\n'):
            raise ValueError(f'{key} must be an HTTPS URL or root-relative path without credentials, query or fragment')
        if any(x in v.lower() for x in ('sb_secret_', 'service_role', 'postgresql:', 'postgres:')):
            raise ValueError('A privileged credential was placed in public config')
    return {'environment':values[KEYS[0]],'apiBaseURL':(values[KEYS[1]].rstrip('/') or '/'),
            'sourceURL':values[KEYS[2]],'mode':'live','writesEnabled':True}

def emit_public_config(root: Path, site: Path) -> None:
    (site / 'config.json').write_text(json.dumps(read_public_config(root), indent=2)+'\n')
