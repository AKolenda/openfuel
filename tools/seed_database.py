#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Seed the explicitly selected D1 target and summarize Wrangler's per-query output."""
import argparse
import json
from pathlib import Path
import re
import subprocess
from import_live_data import generate

ROOT = Path(__file__).resolve().parents[1]

def profile_name(value):
    # Wrangler's rule for auth profile names, without a leading hyphen, so a name never reads as a flag.
    if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_-]*', value):
        raise argparse.ArgumentTypeError(f'{value!r} is not a wrangler auth profile name (letters, digits, - and _)')
    return value

def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument('--local', action='store_true')
    target.add_argument('--remote', action='store_true')
    parser.add_argument('--profile', type=profile_name, metavar='NAME', help='wrangler auth profile to run as')
    return parser.parse_args(argv)

def wrangler_command(args, destination):
    command = [str(ROOT / 'node_modules/.bin/wrangler'), 'd1', 'execute', 'openfuel-data',
               '--local' if args.local else '--remote', '--file', str(destination), '--json', '--yes']
    return command + [f'--profile={args.profile}'] if args.profile else command

if __name__ == '__main__':
    args = parse_args()
    destination = ROOT / '.local/real-data.sql'
    generate(destination)
    result = subprocess.run(wrangler_command(args, destination), cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        print(result.stdout)
        print(result.stderr)
        raise SystemExit(result.returncode)
    responses = json.loads(result.stdout)
    if not all(item.get('success') for item in responses):
        raise SystemExit('D1 reported an unsuccessful seed operation.')
    print(f"Seed complete ({'local' if args.local else 'remote'}): imported station/city/reference snapshot; existing pump reports preserved.")
