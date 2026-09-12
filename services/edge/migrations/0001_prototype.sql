-- SPDX-License-Identifier: AGPL-3.0-only
-- Isolated synthetic playground. Never import private evidence or live station facts here.
CREATE TABLE stations (
  id TEXT PRIMARY KEY,
  sort_order INTEGER NOT NULL,
  data TEXT NOT NULL CHECK(json_valid(data))
);
CREATE TABLE current_prices (
  station_id TEXT NOT NULL REFERENCES stations(id),
  fuel_type TEXT NOT NULL CHECK(fuel_type IN ('regular', 'premium', 'diesel')),
  price_milli INTEGER NOT NULL CHECK(price_milli BETWEEN 500 AND 3999),
  observed_at TEXT NOT NULL,
  source TEXT NOT NULL CHECK(source IN ('sample', 'community-prototype')),
  PRIMARY KEY (station_id, fuel_type)
);
CREATE TABLE price_reports (
  id TEXT PRIMARY KEY,
  station_id TEXT NOT NULL REFERENCES stations(id),
  fuel_type TEXT NOT NULL CHECK(fuel_type IN ('regular', 'premium', 'diesel')),
  price_milli INTEGER NOT NULL CHECK(price_milli BETWEEN 500 AND 3999),
  client_id TEXT NOT NULL,
  observed_at TEXT NOT NULL
);
CREATE INDEX reports_client_time ON price_reports(client_id, observed_at);
CREATE TRIGGER bounded_reports BEFORE INSERT ON price_reports
  WHEN (SELECT count(*) FROM price_reports) >= 10000
    OR (SELECT count(*) FROM price_reports WHERE client_id = NEW.client_id
        AND observed_at > strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-1 hour')) >= 30
BEGIN
  SELECT RAISE(ABORT, 'prototype_rate_limit');
END;
CREATE TRIGGER publish_sample_report AFTER INSERT ON price_reports BEGIN
  INSERT INTO current_prices(station_id, fuel_type, price_milli, observed_at, source)
    VALUES (NEW.station_id, NEW.fuel_type, NEW.price_milli, NEW.observed_at, 'community-prototype')
    ON CONFLICT(station_id, fuel_type) DO UPDATE SET
      price_milli = excluded.price_milli, observed_at = excluded.observed_at, source = excluded.source;
END;
