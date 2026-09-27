-- SPDX-License-Identifier: AGPL-3.0-only
-- Station geography is served from static per-area files built from the same snapshot, so D1 only
-- holds prices for reading. Areas are 0.5 degree squares: row = CAST((latitude+90)*2 AS INTEGER),
-- column = CAST((longitude+180)*2 AS INTEGER), id = row*1000+column. The Worker and the site
-- build must use exactly these expressions.
ALTER TABLE current_prices ADD COLUMN area INTEGER;
UPDATE current_prices SET area=(SELECT CAST((latitude+90)*2 AS INTEGER)*1000+CAST((longitude+180)*2 AS INTEGER)
  FROM stations WHERE stations.id=current_prices.station_id);
-- One row per area holds all of its current prices, so a stations request reads one row per area
-- in a single query. Whenever a current price there is added, changed or removed, including by
-- hand, the triggers change that one entry and give the area a new version, so cached lists stay
-- valid until a price there actually changes. Versions are random odd numbers that always change,
-- so a restored database cannot reuse the cache entry of a different state. Areas without a row
-- have never had a price (version 0).
CREATE TABLE area_prices (
  area INTEGER PRIMARY KEY,
  version INTEGER NOT NULL,
  -- JSON object: "station_id:fuel_type" -> [station_id, fuel_type, price_milli, observed_at, source].
  -- Station IDs and fuel types contain only [a-z0-9-], so they are safe in a JSON path.
  prices TEXT NOT NULL
);
-- To rebuild area_prices if it is ever edited out of step with current_prices, see docs/RUNNING_COSTS.md.
INSERT INTO area_prices(area,version,prices)
  SELECT area,(random() & 2147483647) | 1,json_group_object(station_id||':'||fuel_type,json_array(station_id,fuel_type,price_milli,observed_at,source))
  FROM current_prices WHERE area IS NOT NULL GROUP BY area;
-- Each change sets or removes one entry without reading the area's other prices.
CREATE TRIGGER current_prices_area_insert AFTER INSERT ON current_prices WHEN NEW.area IS NOT NULL BEGIN
  INSERT INTO area_prices(area,version,prices)
  VALUES(NEW.area,(random() & 2147483647) | 1,json_object(NEW.station_id||':'||NEW.fuel_type,json_array(NEW.station_id,NEW.fuel_type,NEW.price_milli,NEW.observed_at,NEW.source)))
  ON CONFLICT(area) DO UPDATE SET version=((area_prices.version+1+(random() & 1073741823)) & 2147483647) | 1,
    prices=json_set(area_prices.prices,'$."'||NEW.station_id||':'||NEW.fuel_type||'"',json_array(NEW.station_id,NEW.fuel_type,NEW.price_milli,NEW.observed_at,NEW.source));
END;
CREATE TRIGGER current_prices_area_update AFTER UPDATE ON current_prices WHEN NEW.area IS NOT NULL BEGIN
  INSERT INTO area_prices(area,version,prices)
  VALUES(NEW.area,(random() & 2147483647) | 1,json_object(NEW.station_id||':'||NEW.fuel_type,json_array(NEW.station_id,NEW.fuel_type,NEW.price_milli,NEW.observed_at,NEW.source)))
  ON CONFLICT(area) DO UPDATE SET version=((area_prices.version+1+(random() & 1073741823)) & 2147483647) | 1,
    prices=json_set(area_prices.prices,'$."'||NEW.station_id||':'||NEW.fuel_type||'"',json_array(NEW.station_id,NEW.fuel_type,NEW.price_milli,NEW.observed_at,NEW.source));
END;
-- A price that left its area (its station moved in a new snapshot) or changed key is removed from the old entry.
CREATE TRIGGER current_prices_area_leave AFTER UPDATE ON current_prices
WHEN OLD.area IS NOT NULL AND (OLD.area IS NOT NEW.area OR OLD.station_id IS NOT NEW.station_id OR OLD.fuel_type IS NOT NEW.fuel_type) BEGIN
  UPDATE area_prices SET version=((version+1+(random() & 1073741823)) & 2147483647) | 1,
    prices=json_remove(prices,'$."'||OLD.station_id||':'||OLD.fuel_type||'"') WHERE area=OLD.area;
END;
CREATE TRIGGER current_prices_area_delete AFTER DELETE ON current_prices WHEN OLD.area IS NOT NULL BEGIN
  UPDATE area_prices SET version=((version+1+(random() & 1073741823)) & 2147483647) | 1,
    prices=json_remove(prices,'$."'||OLD.station_id||':'||OLD.fuel_type||'"') WHERE area=OLD.area;
END;
DROP TRIGGER publish_report;
CREATE TRIGGER publish_report AFTER INSERT ON price_reports BEGIN
  INSERT INTO current_prices(station_id,fuel_type,price_milli,observed_at,source,area)
  VALUES(NEW.station_id,NEW.fuel_type,NEW.price_milli,NEW.observed_at,'community-unverified',
    (SELECT CAST((latitude+90)*2 AS INTEGER)*1000+CAST((longitude+180)*2 AS INTEGER) FROM stations WHERE id=NEW.station_id))
  -- Setting area again repairs a price written by hand without one.
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
