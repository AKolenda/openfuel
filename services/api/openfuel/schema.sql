-- SPDX-License-Identifier: AGPL-3.0-only
-- The ledger is authoritative. Projection tables are disposable/rebuildable.
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS events (
  seq INTEGER PRIMARY KEY,
  previous_hash TEXT NOT NULL CHECK(length(previous_hash) = 64),
  payload BLOB NOT NULL,
  hash TEXT NOT NULL UNIQUE CHECK(length(hash) = 64)
);
CREATE TRIGGER IF NOT EXISTS events_no_update BEFORE UPDATE ON events
BEGIN SELECT RAISE(ABORT, 'events are append-only'); END;
CREATE TRIGGER IF NOT EXISTS events_no_delete BEFORE DELETE ON events
BEGIN SELECT RAISE(ABORT, 'events are append-only'); END;
CREATE TABLE IF NOT EXISTS stations (
  id TEXT PRIMARY KEY,
  region TEXT NOT NULL,
  document TEXT NOT NULL,
  event_seq INTEGER NOT NULL REFERENCES events(seq)
);
CREATE INDEX IF NOT EXISTS stations_region ON stations(region);
CREATE TABLE IF NOT EXISTS observations (
  event_seq INTEGER PRIMARY KEY REFERENCES events(seq),
  report_id TEXT NOT NULL UNIQUE,
  station_id TEXT NOT NULL REFERENCES stations(id),
  fuel_type TEXT NOT NULL,
  payment_type TEXT NOT NULL,
  price_milli INTEGER NOT NULL CHECK(price_milli > 0),
  observed_bucket TEXT NOT NULL,
  document TEXT NOT NULL,
  retracted INTEGER NOT NULL DEFAULT 0 CHECK(retracted IN (0,1))
);
CREATE INDEX IF NOT EXISTS observations_lookup
ON observations(station_id, fuel_type, payment_type, observed_bucket DESC, event_seq DESC);
