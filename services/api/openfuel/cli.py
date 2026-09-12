# SPDX-License-Identifier: AGPL-3.0-only
import argparse
import base64
import json
import os
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from .audit import load_key, verify_export
from .store import Store

def main():
    parser = argparse.ArgumentParser(description='OpenFuel ledger utilities')
    parser.add_argument('--db', default=os.getenv('OPENFUEL_DB', 'data/openfuel.sqlite3'))
    subs = parser.add_subparsers(dest='command', required=True)
    subs.add_parser('seed-demo')
    exp = subs.add_parser('export'); exp.add_argument('output'); exp.add_argument('--key')
    ver = subs.add_parser('verify'); ver.add_argument('file'); ver.add_argument('--anchor'); ver.add_argument('--public-key')
    key = subs.add_parser('keygen'); key.add_argument('output')
    ret = subs.add_parser('retract'); ret.add_argument('seq', type=int); ret.add_argument('--reason', default='incorrect_price')
    args = parser.parse_args()
    if args.command == 'keygen':
        key = Ed25519PrivateKey.generate()
        fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as output:
            output.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                           serialization.NoEncryption()))
        print('Public key (publish/pin independently): ' + base64.b64encode(key.public_key().public_bytes(
            serialization.Encoding.Raw, serialization.PublicFormat.Raw)).decode())
    elif args.command == 'verify':
        anchor = json.loads(Path(args.anchor).read_text()) if args.anchor else None
        with open(args.file) as lines:
            result = verify_export(lines, anchor, args.public_key)
        print(json.dumps(result, indent=2))
        if not args.anchor and not args.public_key:
            print('INTERNAL CONSISTENCY ONLY: no independent anchor or signing key was checked.')
    else:
        store = Store(args.db)
        if args.command == 'seed-demo':
            store.seed_demo(); print('Seeded FICTIONAL stations and prices. Not live data.')
        elif args.command == 'retract':
            print(json.dumps(store.retract(args.seq, args.reason), indent=2))
        elif args.command == 'export':
            events, head = store.export(load_key(args.key))
            with open(args.output, 'w') as out:
                for event in events:
                    out.write(json.dumps(event, separators=(',', ':')) + '\n')
                out.write(json.dumps({'checkpoint': head}, separators=(',', ':')) + '\n')
            print(f'Exported {len(events)} events to {args.output}')

if __name__ == '__main__':
    main()
