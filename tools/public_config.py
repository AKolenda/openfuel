# SPDX-License-Identifier: AGPL-3.0-only
"""Explicit public build allowlist. Not a general dotenv loader or secret exporter."""
from pathlib import Path
from urllib.parse import urlsplit
import html
import json
import os

KEYS = ('OPENFUEL_PUBLIC_ENV', 'OPENFUEL_PUBLIC_API_BASE_URL', 'OPENFUEL_PUBLIC_SOURCE_URL')
DEFAULTS = dict(zip(KEYS, ('development', '/api/v1', '/downloads/openfuel-source.zip')))
# Optional donation page for the running costs (database, map tiles). Without it no donate UI appears.
DONATE_KEY = 'OPENFUEL_PUBLIC_DONATE_URL'

def _read_values(root: Path, environ: dict | None, keys: tuple, defaults: dict) -> dict:
    values = dict(defaults)
    envfile = root / '.env'
    if envfile.exists():
        for line in envfile.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith('#'): continue
            if '=' not in line: raise ValueError('Invalid .env assignment')
            key, value = line.split('=', 1)
            if key.strip() in keys:
                values[key.strip()] = value.strip().strip('"\'')
    environment = os.environ if environ is None else environ
    for key in keys:
        if key in environment: values[key] = environment[key]
    return values

def read_donate_url(root: Path, environ: dict | None = None) -> str:
    url = _read_values(root, environ, (DONATE_KEY,), {DONATE_KEY: ''})[DONATE_KEY]
    if not url: return ''
    u = urlsplit(url)
    if u.scheme != 'https' or not u.hostname or u.username or u.password or any(c in url for c in ' "\'<>\r\n\t'):
        raise ValueError(f'{DONATE_KEY} must be an HTTPS URL without credentials')
    return url

def read_public_config(root: Path, environ: dict | None = None) -> dict:
    values = _read_values(root, environ, KEYS, DEFAULTS)
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

def emit_public_config(root: Path, site: Path, environ: dict | None = None) -> None:
    config = read_public_config(root, environ)
    donate = read_donate_url(root, environ)
    (site / 'config.json').write_text(json.dumps({**config, 'donateURL': donate}, indent=2)+'\n')
    page = site / 'preview/index.html'
    if donate and page.exists():
        text = page.read_text()
        if text.count('</head>') != 1: raise ValueError('The station map page needs exactly one </head>')
        page.write_text(text.replace('</head>', f'  <meta name="openfuel-donate-url" content="{html.escape(donate)}">\n</head>'))
