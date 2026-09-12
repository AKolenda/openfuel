# SPDX-License-Identifier: AGPL-3.0-only
from __future__ import annotations
from contextlib import contextmanager
from datetime import datetime, timezone, timedelta
import json
from pathlib import Path
import sqlite3
from uuid import uuid4
from .audit import canonical, envelope, event_hash, checkpoint, ZERO_HASH
from .models import Report, bucket

class Conflict(Exception):
    pass

class Store:
    def __init__(self, path: str):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as conn:
            conn.execute('PRAGMA journal_mode=WAL')
            conn.executescript(Path(__file__).with_name('schema.sql').read_text())
        self.verify_and_rebuild()

    @contextmanager
    def connection(self):
        conn = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute('PRAGMA foreign_keys=ON')
        conn.execute('PRAGMA synchronous=FULL')
        try:
            yield conn
        finally:
            conn.close()

    @contextmanager
    def transaction(self):
        with self.connection() as conn:
            conn.execute('BEGIN IMMEDIATE')
            try:
                yield conn
                conn.commit()
            except Exception:
                conn.rollback()
                raise

    def _append(self, conn, payload: dict) -> dict:
        row = conn.execute('SELECT seq, hash FROM events ORDER BY seq DESC LIMIT 1').fetchone()
        seq, previous = (row['seq'] + 1, row['hash']) if row else (1, ZERO_HASH)
        data = canonical(payload)
        digest = event_hash(seq, previous, data)
        conn.execute('INSERT INTO events(seq, previous_hash, payload, hash) VALUES(?,?,?,?)',
                     (seq, previous, data, digest))
        self._project(conn, seq, payload)
        return envelope(seq, previous, data, digest)

    @staticmethod
    def _project(conn, seq: int, payload: dict):
        kind, data = payload['type'], payload['data']
        if payload['schema_version'] != 1:
            raise ValueError('Unknown event schema')
        if kind == 'station.created':
            conn.execute('INSERT INTO stations(id, region, document, event_seq) VALUES(?,?,?,?)',
                         (data['id'], data['region'], canonical(data).decode(), seq))
        elif kind == 'price.observed':
            conn.execute('''INSERT INTO observations(event_seq, report_id, station_id, fuel_type,
                         payment_type, price_milli, observed_bucket, document) VALUES(?,?,?,?,?,?,?,?)''',
                         (seq, data['report_id'], data['station_id'], data['fuel_type'], data['payment_type'],
                          data['price_milli'], data['observed_bucket'], canonical(data).decode()))
        elif kind == 'price.retracted':
            changed = conn.execute('UPDATE observations SET retracted=1 WHERE event_seq=? AND retracted=0',
                                   (data['target_seq'],)).rowcount
            if changed != 1:
                raise ValueError('Invalid retraction target')
        else:
            raise ValueError('Unknown event type')

    def verify_and_rebuild(self):
        # FastAPI calls this at startup, not on every read. A prior external checkpoint is
        # still needed to detect wholesale rewrites or tail truncation by the operator.
        with self.transaction() as conn:
            conn.execute('DELETE FROM observations')
            conn.execute('DELETE FROM stations')
            previous, expected = ZERO_HASH, 1
            for row in conn.execute('SELECT * FROM events ORDER BY seq').fetchall():
                if row['seq'] != expected or row['previous_hash'] != previous:
                    raise ValueError('Ledger sequence is corrupt')
                if event_hash(row['seq'], previous, row['payload']) != row['hash']:
                    raise ValueError('Ledger payload is corrupt')
                self._project(conn, row['seq'], json.loads(row['payload']))
                previous, expected = row['hash'], expected + 1

    def seed_demo(self):
        now = datetime.now(timezone.utc)
        fixtures = [
            ('demo-river', 'River Co-op (fictional)', '10 Example Avenue', 53544000, -113491000, 1479, 1),
            ('demo-prairie', 'Prairie Fuel (fictional)', '20 Example Road', 53555000, -113480000, 1519, 2),
            ('demo-north', 'North Fuel (fictional)', '30 Example Street', 53566000, -113505000, 1439, 30),
        ]
        with self.transaction() as conn:
            if conn.execute('SELECT 1 FROM events LIMIT 1').fetchone():
                raise Conflict('Seed only an empty database; existing data was not changed')
            for sid, name, address, lat, lon, price, hours in fixtures:
                station = {'id': sid, 'region': 'demo-region', 'name': name, 'address': address,
                           'latitude_e6': lat, 'longitude_e6': lon, 'currency': 'CAD', 'volume_unit': 'L',
                           'source': 'synthetic_fixture', 'source_license': 'CC0-1.0',
                           'source_ref': 'openfuel-demo-v1', 'is_demo': True}
                self._append(conn, {'schema_version': 1, 'type': 'station.created', 'data': station})
                for fuel, offset in [('regular', 0), ('premium', 200), ('diesel', 80)]:
                    data = {'report_id': str(uuid4()), 'station_id': sid, 'fuel_type': fuel,
                            'payment_type': 'standard', 'price_milli': price + offset,
                            'currency': 'CAD', 'volume_unit': 'L', 'source': 'synthetic_fixture',
                            'observed_bucket': bucket(now - timedelta(hours=hours)),
                            'received_bucket': bucket(now), 'data_license': 'CC0-1.0'}
                    self._append(conn, {'schema_version': 1, 'type': 'price.observed', 'data': data})

    def add_report(self, report: Report) -> tuple[dict, bool]:
        with self.transaction() as conn:
            row = conn.execute('SELECT document FROM stations WHERE id=?', (report.station_id,)).fetchone()
            if not row:
                raise KeyError('Unknown station')
            station = json.loads(row['document'])
            data = {'report_id': str(report.report_id), 'station_id': report.station_id,
                    'fuel_type': report.fuel_type.value, 'payment_type': report.payment_type.value,
                    'price_milli': report.price_milli, 'observed_bucket': bucket(report.observed_at),
                    'currency': station['currency'], 'volume_unit': station['volume_unit'],
                    'source': 'community_unverified', 'data_license': 'CC0-1.0'}
            existing = conn.execute('''SELECT e.* FROM events e JOIN observations o ON o.event_seq=e.seq
                                     WHERE o.report_id=?''', (str(report.report_id),)).fetchone()
            if existing:
                old = json.loads(existing['payload'])['data']
                old.pop('received_bucket')
                if old != data:
                    raise Conflict('Report ID already used with different public data')
                return envelope(existing['seq'], existing['previous_hash'], existing['payload'], existing['hash']), False
            data['received_bucket'] = bucket(datetime.now(timezone.utc))
            return self._append(conn, {'schema_version': 1, 'type': 'price.observed', 'data': data}), True

    def retract(self, seq: int, reason: str = 'incorrect_price'):
        if reason not in ('incorrect_price', 'duplicate', 'moderation'):
            raise ValueError('Use an enumerated reason; no free text or personal data')
        with self.transaction() as conn:
            return self._append(conn, {'schema_version': 1, 'type': 'price.retracted', 'data': {
                'target_seq': seq, 'reason': reason, 'received_bucket': bucket(datetime.now(timezone.utc))}})

    def regions(self) -> list[dict]:
        with self.connection() as conn:
            return [{'id': row['region'], 'station_count': row['n']} for row in
                    conn.execute('SELECT region, COUNT(*) AS n FROM stations GROUP BY region ORDER BY region')]

    def stations(self, region: str, fuel: str, payment: str) -> list[dict]:
        now = datetime.now(timezone.utc)
        with self.connection() as conn:
            conn.execute('BEGIN')  # one consistent read snapshot
            rows = conn.execute('SELECT document FROM stations WHERE region=? ORDER BY id', (region,)).fetchall()
            result = []
            for row in rows:
                station = json.loads(row['document'])
                latest = conn.execute('''SELECT * FROM observations WHERE station_id=? AND fuel_type=?
                       AND payment_type=? AND retracted=0 ORDER BY observed_bucket DESC, event_seq DESC LIMIT 1''',
                       (station['id'], fuel, payment)).fetchone()
                station['price'] = None
                if latest:
                    observation = json.loads(latest['document'])
                    observed = datetime.fromisoformat(observation['observed_bucket'].replace('Z', '+00:00'))
                    age = max(0, int((now - observed).total_seconds()))
                    observation.update({'event_seq': latest['event_seq'], 'age_seconds': age,
                                        'freshness': 'recent' if age <= 7200 else ('aging' if age <= 86400 else 'stale'),
                                        'verification': 'demo' if observation['source'] == 'synthetic_fixture' else 'unverified'})
                    station['price'] = observation
                result.append(station)
            return result

    def history(self, station_id: str) -> list[dict]:
        with self.connection() as conn:
            if not conn.execute('SELECT 1 FROM stations WHERE id=?', (station_id,)).fetchone():
                raise KeyError('Unknown station')
            rows = conn.execute('SELECT * FROM observations WHERE station_id=? ORDER BY event_seq DESC',
                                (station_id,)).fetchall()
            return [dict(json.loads(row['document']), event_seq=row['event_seq'], retracted=bool(row['retracted'])) for row in rows]

    def export(self, key=None) -> tuple[list[dict], dict]:
        with self.connection() as conn:
            conn.execute('BEGIN')
            rows = conn.execute('SELECT * FROM events ORDER BY seq').fetchall()
            events = [envelope(row['seq'], row['previous_hash'], row['payload'], row['hash']) for row in rows]
            head = rows[-1]['hash'] if rows else ZERO_HASH
            return events, checkpoint(len(rows), head, key)
