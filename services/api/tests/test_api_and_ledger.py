# SPDX-License-Identifier: AGPL-3.0-only
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
import json
from pathlib import Path
import sqlite3
from uuid import uuid4
import pytest
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi.testclient import TestClient
from openfuel.api import create_app, Settings
from openfuel.audit import canonical, event_hash, checkpoint, verify_export, ZERO_HASH
from openfuel.models import Report, bucket
from openfuel.store import Store, Conflict

TOKEN = 'closed-test-' + 'a' * 40
AUTH = {'Authorization': 'Bearer ' + TOKEN}

@pytest.fixture
def store(tmp_path):
    value = Store(str(tmp_path / 'db.sqlite3'))
    value.seed_demo()
    return value

@pytest.fixture
def client(store):
    with TestClient(create_app(Settings(db_path=store.path, write_token=TOKEN))) as value:
        yield value

def report(**overrides):
    return {'report_id': str(uuid4()), 'station_id': 'demo-river', 'fuel_type': 'regular',
            'payment_type': 'standard', 'price_milli': 1499,
            'observed_at': datetime.now(timezone.utc).isoformat(), **overrides}

def export_lines(store, key=None):
    events, head = store.export(key)
    return [json.dumps(item) for item in [*events, {'checkpoint': head}]]

def decode(envelope):
    return json.loads(base64.b64decode(envelope['payload_base64']))

def test_seed_is_explicit_and_not_repeatable(store):
    assert len(store.export()[0]) == 12
    assert len(store.regions()) == 1
    assert all(s['is_demo'] for s in store.stations('demo-region', 'regular', 'standard'))
    with pytest.raises(Conflict): store.seed_demo()

def test_read_only_is_default(store):
    with TestClient(create_app(Settings(db_path=store.path))) as read_only:
        assert read_only.get('/v1/meta').json()['writes_enabled'] is False
        assert read_only.post('/v1/reports', json=report()).status_code == 503
    assert len(store.export()[0]) == 12

def test_short_write_token_is_rejected(tmp_path):
    with pytest.raises(ValueError):
        create_app(Settings(db_path=str(tmp_path/'db'), write_token='short'))

def test_invalid_token_does_not_create_event(client, store):
    assert client.post('/v1/reports', json=report()).status_code == 401
    assert len(store.export()[0]) == 12

def test_authorized_report_coarsens_time(client, store):
    value = report()
    response = client.post('/v1/reports', headers=AUTH, json=value)
    assert response.status_code == 201
    result = response.json()
    assert result['verification'] == 'unverified'
    data = decode(result['event'])['data']
    assert set(data) == {'report_id', 'station_id', 'fuel_type', 'payment_type', 'price_milli',
                        'observed_bucket', 'received_bucket', 'currency', 'volume_unit', 'source', 'data_license'}
    observed = datetime.fromisoformat(data['observed_bucket'].replace('Z', '+00:00'))
    assert observed.minute % 15 == 0 and observed.second == 0 and observed.microsecond == 0
    assert data['currency'] == 'CAD' and data['volume_unit'] == 'L'
    assert data['source'] == 'community_unverified'
    assert 'observed_at' not in data
    assert verify_export(export_lines(store))['tree_size'] == 13

@pytest.mark.parametrize('field', ['latitude', 'longitude', 'user_id', 'device_id', 'email',
                                  'trip_id', 'speed', 'advertising_id', 'notes', 'currency'])
def test_private_or_unknown_fields_are_rejected(client, store, field):
    value = report(**{field: 'do-not-reflect-private-content'})
    response = client.post('/v1/reports', headers=AUTH, json=value)
    assert response.status_code == 422
    assert 'do-not-reflect' not in response.text
    assert len(store.export()[0]) == 12

@pytest.mark.parametrize('price', ['1499', 1499.5, True, 0, -1, 49, 15001])
def test_invalid_numeric_prices_are_rejected(client, price):
    assert client.post('/v1/reports', headers=AUTH, json=report(price_milli=price)).status_code == 422

@pytest.mark.parametrize('time', [
    lambda: datetime.now(timezone.utc) + timedelta(minutes=6),
    lambda: datetime.now(timezone.utc) - timedelta(hours=49),
    lambda: datetime.now().replace(tzinfo=None),
])
def test_invalid_time_is_rejected(client, time):
    assert client.post('/v1/reports', headers=AUTH, json=report(observed_at=time().isoformat())).status_code == 422

def test_report_idempotency_and_conflict(client, store):
    value = report()
    first = client.post('/v1/reports', headers=AUTH, json=value)
    second = client.post('/v1/reports', headers=AUTH, json=value)
    assert first.status_code == 201 and second.status_code == 200
    assert first.json()['event'] == second.json()['event']
    value['price_milli'] += 1
    assert client.post('/v1/reports', headers=AUTH, json=value).status_code == 409
    assert len(store.export()[0]) == 13

def test_unknown_station_and_region(client):
    assert client.post('/v1/reports', headers=AUTH, json=report(station_id='nonexistent')).status_code == 404
    assert client.get('/v1/stations/nonexistent/history').status_code == 404
    assert client.get('/v1/stations?region=unknown').json()['stations'] == []

def test_grade_and_payment_not_mixed(client):
    client.post('/v1/reports', headers=AUTH, json=report(payment_type='cash', price_milli=999))
    standard = client.get('/v1/stations').json()['stations']
    cash = client.get('/v1/stations?payment=cash').json()['stations']
    assert next(s for s in standard if s['id'] == 'demo-river')['price']['price_milli'] == 1479
    assert next(s for s in cash if s['id'] == 'demo-river')['price']['price_milli'] == 999
    midgrade = client.get('/v1/stations?fuel=midgrade').json()['stations']
    assert all(s['price'] is None for s in midgrade)

def test_stale_is_visible_not_claimed_current(client):
    values = client.get('/v1/stations').json()['stations']
    assert next(s for s in values if s['id'] == 'demo-north')['price']['freshness'] == 'stale'
    assert all(s['price']['verification'] == 'demo' for s in values)

def test_retraction_preserves_original_and_reverts_projection(store):
    event, _ = store.add_report(Report.model_validate(report()))
    store.retract(event['seq'])
    assert len(store.export()[0]) == 14
    assert store.export()[0][-2] == event
    history = store.history('demo-river')
    assert history[0]['retracted'] is True
    station = next(s for s in store.stations('demo-region', 'regular', 'standard') if s['id'] == 'demo-river')
    assert station['price']['price_milli'] == 1479
    assert verify_export(export_lines(store))['tree_size'] == 14

def test_invalid_retraction_rolls_back_transaction(store):
    with pytest.raises(ValueError): store.retract(999999)
    assert len(store.export()[0]) == 12
    assert verify_export(export_lines(store))['tree_size'] == 12

def test_database_triggers_reject_silent_edits(store):
    with store.connection() as conn:
        with pytest.raises(sqlite3.IntegrityError): conn.execute('UPDATE events SET hash=? WHERE seq=1', ('f'*64,))
        with pytest.raises(sqlite3.IntegrityError): conn.execute('DELETE FROM events WHERE seq=1')

def test_projection_can_be_rebuilt_from_ledger(store):
    with store.connection() as conn:
        conn.execute('DELETE FROM observations')
    rebuilt = Store(store.path)
    assert len(rebuilt.history('demo-river')) == 3
    assert verify_export(export_lines(rebuilt))['tree_size'] == 12

def test_startup_detects_payload_tampering(store):
    with store.connection() as conn:
        conn.execute('DROP TRIGGER events_no_update')
        conn.execute('UPDATE events SET payload=? WHERE seq=1', (b'{}',))
    with pytest.raises(ValueError): Store(store.path)

def test_concurrent_appends_have_no_forks_or_lost_writes(store):
    def write(i):
        return store.add_report(Report.model_validate(report(price_milli=1500+i)))[0]['seq']
    with ThreadPoolExecutor(max_workers=8) as pool:
        seqs = list(pool.map(write, range(24)))
    assert len(set(seqs)) == 24
    assert verify_export(export_lines(store))['tree_size'] == 36

def test_corrupted_export_fails(store):
    lines = export_lines(store)
    item = json.loads(lines[1])
    payload = decode(item)
    payload['data']['price_milli'] = 1
    item['payload_base64'] = base64.b64encode(canonical(payload)).decode()
    lines[1] = json.dumps(item)
    with pytest.raises(ValueError): verify_export(lines)

def test_removed_middle_event_fails(store):
    lines = export_lines(store); lines.pop(3)
    with pytest.raises(ValueError): verify_export(lines)

def test_tail_truncation_requires_trusted_anchor(store):
    events, trusted = store.export()
    shorter = events[:-1]
    forged_footer = checkpoint(len(shorter), shorter[-1]['hash'])
    lines = [json.dumps(x) for x in [*shorter, {'checkpoint': forged_footer}]]
    assert verify_export(lines)['tree_size'] == 11 # Internal consistency alone is NOT evidence against rollback.
    with pytest.raises(ValueError): verify_export(lines, anchor=trusted)

def test_recomputed_history_is_detected_by_old_anchor(store):
    events, trusted = store.export()
    previous = ZERO_HASH
    for item in events:
        data = decode(item)
        if data['type'] == 'price.observed': data['data']['price_milli'] += 1
        payload = canonical(data)
        item['previous_hash'] = previous
        item['payload_base64'] = base64.b64encode(payload).decode()
        item['hash'] = event_hash(item['seq'], previous, payload)
        previous = item['hash']
    lines = [json.dumps(x) for x in [*events, {'checkpoint': checkpoint(len(events), previous)}]]
    with pytest.raises(ValueError): verify_export(lines, anchor=trusted)

def test_signed_checkpoint_pins_external_key(store):
    key = Ed25519PrivateKey.generate()
    events, head = store.export(key)
    lines = [json.dumps(x) for x in [*events, {'checkpoint': head}]]
    assert verify_export(lines, pinned_public_key=head['public_key_base64'])['tree_size'] == 12
    wrong_key = store.export(Ed25519PrivateKey.generate())[1]['public_key_base64']
    with pytest.raises(InvalidSignature): verify_export(lines, pinned_public_key=wrong_key)
    with pytest.raises(ValueError): verify_export(export_lines(store), pinned_public_key=head['public_key_base64'])

def test_old_anchor_accepts_longer_valid_prefix(store):
    anchor = store.export()[1]
    store.add_report(Report.model_validate(report()))
    assert verify_export(export_lines(store), anchor=anchor)['tree_size'] == 13

def test_export_footer_is_final(store):
    lines = export_lines(store); lines.append(lines[0])
    with pytest.raises(ValueError): verify_export(lines)

def test_large_body_rejected_without_reflection(client):
    response = client.post('/v1/reports', content='x'*9000, headers={**AUTH, 'Content-Type':'application/json'})
    assert response.status_code == 413

def test_export_and_privacy_headers(client):
    response = client.get('/v1/audit/export')
    assert response.headers['cache-control'] == 'no-store'
    assert response.headers['referrer-policy'] == 'no-referrer'
    assert verify_export(response.text.splitlines())['tree_size'] == 12

def test_openapi_contract_is_current(client):
    path = Path(__file__).resolve().parents[3] / 'packages/contracts/openapi.json'
    assert client.get('/openapi.json').json() == json.loads(path.read_text())
