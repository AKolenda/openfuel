-- SPDX-License-Identifier: AGPL-3.0-only
-- Station geography is imported from OSM. No synthetic or inferred pump prices.
CREATE TABLE stations (
  id TEXT PRIMARY KEY,
  latitude REAL NOT NULL CHECK(latitude BETWEEN -90 AND 90),
  longitude REAL NOT NULL CHECK(longitude BETWEEN -180 AND 180),
  data TEXT NOT NULL CHECK(json_valid(data))
);
CREATE INDEX stations_location ON stations(latitude, longitude);
CREATE TABLE current_prices (
  station_id TEXT NOT NULL REFERENCES stations(id),
  fuel_type TEXT NOT NULL CHECK(fuel_type IN ('regular','premium','diesel')),
  price_milli INTEGER NOT NULL CHECK(price_milli BETWEEN 500 AND 3999),
  observed_at TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT 'community-unverified',
  PRIMARY KEY(station_id, fuel_type)
);
CREATE TABLE price_reports (
  id TEXT PRIMARY KEY,
  station_id TEXT NOT NULL REFERENCES stations(id),
  fuel_type TEXT NOT NULL CHECK(fuel_type IN ('regular','premium','diesel')),
  price_milli INTEGER NOT NULL CHECK(price_milli BETWEEN 500 AND 3999),
  client_id TEXT NOT NULL,
  observed_at TEXT NOT NULL
);
CREATE INDEX reports_client_time ON price_reports(client_id, observed_at);
CREATE TRIGGER report_capacity BEFORE INSERT ON price_reports
WHEN (SELECT count(*) FROM price_reports) >= 100000
BEGIN SELECT RAISE(ABORT, 'report_capacity_limit'); END;
CREATE TRIGGER report_frequency BEFORE INSERT ON price_reports
WHEN (SELECT count(*) FROM price_reports WHERE client_id=NEW.client_id
  AND observed_at > strftime('%Y-%m-%dT%H:%M:%fZ','now','-1 hour')) >= 30
BEGIN SELECT RAISE(ABORT, 'report_rate_limit'); END;
CREATE TRIGGER publish_report AFTER INSERT ON price_reports BEGIN
  INSERT INTO current_prices(station_id,fuel_type,price_milli,observed_at,source)
  VALUES(NEW.station_id,NEW.fuel_type,NEW.price_milli,NEW.observed_at,'community-unverified')
  ON CONFLICT(station_id,fuel_type) DO UPDATE SET
    price_milli=excluded.price_milli, observed_at=excluded.observed_at, source=excluded.source
  WHERE excluded.observed_at >= current_prices.observed_at;
END;
CREATE TABLE cities (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, search_name TEXT NOT NULL,
  latitude REAL NOT NULL, longitude REAL NOT NULL, population INTEGER NOT NULL
);
CREATE INDEX city_search ON cities(search_name);
CREATE TABLE market_averages (
  city TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL,
  period TEXT NOT NULL, fuel_type TEXT NOT NULL, price_milli INTEGER NOT NULL,
  PRIMARY KEY(city, fuel_type)
);
CREATE TABLE dataset_metadata (id TEXT PRIMARY KEY, data TEXT NOT NULL CHECK(json_valid(data)));
