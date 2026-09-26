-- SPDX-License-Identifier: AGPL-3.0-only
-- Stations and prices are read per 0.5 degree area instead of per latitude band across Canada.
-- Area row = CAST((latitude+90)*2 AS INTEGER), column = CAST((longitude+180)*2 AS INTEGER);
-- a price's area id is row*1000+column. The Worker must use exactly these expressions.
CREATE INDEX stations_area ON stations(CAST((latitude+90)*2 AS INTEGER), CAST((longitude+180)*2 AS INTEGER));
ALTER TABLE current_prices ADD COLUMN area INTEGER;
UPDATE current_prices SET area=(SELECT CAST((latitude+90)*2 AS INTEGER)*1000+CAST((longitude+180)*2 AS INTEGER)
  FROM stations WHERE stations.id=current_prices.station_id);
CREATE INDEX current_prices_area ON current_prices(area);
-- Each area's price version changes whenever a current price there is added, changed or removed,
-- including by hand, so cached prices stay valid until a price in that area actually changes.
-- Versions are random odd numbers that always change, so a restored database cannot reuse the
-- cache entry of a different state. Areas without a row have never had a price (version 0).
CREATE TABLE area_versions (
  area INTEGER PRIMARY KEY,
  version INTEGER NOT NULL
) WITHOUT ROWID;
INSERT INTO area_versions(area,version)
  SELECT area,(random() & 2147483647) | 1 FROM current_prices WHERE area IS NOT NULL GROUP BY area;
CREATE TRIGGER current_prices_version_insert AFTER INSERT ON current_prices WHEN NEW.area IS NOT NULL BEGIN
  INSERT INTO area_versions(area,version) VALUES(NEW.area,(random() & 2147483647) | 1)
  ON CONFLICT(area) DO UPDATE SET version=((area_versions.version+1+(random() & 1073741823)) & 2147483647) | 1;
END;
CREATE TRIGGER current_prices_version_update AFTER UPDATE ON current_prices BEGIN
  INSERT INTO area_versions(area,version) SELECT NEW.area,(random() & 2147483647) | 1 WHERE NEW.area IS NOT NULL
  ON CONFLICT(area) DO UPDATE SET version=((area_versions.version+1+(random() & 1073741823)) & 2147483647) | 1;
  INSERT INTO area_versions(area,version) SELECT OLD.area,(random() & 2147483647) | 1 WHERE OLD.area IS NOT NULL AND OLD.area IS NOT NEW.area
  ON CONFLICT(area) DO UPDATE SET version=((area_versions.version+1+(random() & 1073741823)) & 2147483647) | 1;
END;
CREATE TRIGGER current_prices_version_delete AFTER DELETE ON current_prices WHEN OLD.area IS NOT NULL BEGIN
  INSERT INTO area_versions(area,version) VALUES(OLD.area,(random() & 2147483647) | 1)
  ON CONFLICT(area) DO UPDATE SET version=((area_versions.version+1+(random() & 1073741823)) & 2147483647) | 1;
END;
DROP TRIGGER publish_report;
CREATE TRIGGER publish_report AFTER INSERT ON price_reports BEGIN
  INSERT INTO current_prices(station_id,fuel_type,price_milli,observed_at,source,area)
  VALUES(NEW.station_id,NEW.fuel_type,NEW.price_milli,NEW.observed_at,'community-unverified',
    (SELECT CAST((latitude+90)*2 AS INTEGER)*1000+CAST((longitude+180)*2 AS INTEGER) FROM stations WHERE id=NEW.station_id))
  ON CONFLICT(station_id,fuel_type) DO UPDATE SET
    price_milli=excluded.price_milli, observed_at=excluded.observed_at, source=excluded.source, area=excluded.area
  WHERE excluded.observed_at >= current_prices.observed_at;
END;
-- count(*) read every stored report on each insert; max(rowid) reads one. Reports are never deleted,
-- so it equals the count, and it can only overestimate if they ever are.
DROP TRIGGER report_capacity;
CREATE TRIGGER report_capacity BEFORE INSERT ON price_reports
WHEN (SELECT max(rowid) FROM price_reports) >= 100000
BEGIN SELECT RAISE(ABORT, 'report_capacity_limit'); END;
-- The Worker's own daily D1 row budget (UTC days, matching Cloudflare's daily reset).
CREATE TABLE usage_budget (
  day TEXT PRIMARY KEY,
  rows_read INTEGER NOT NULL DEFAULT 0,
  rows_written INTEGER NOT NULL DEFAULT 0
) WITHOUT ROWID;
