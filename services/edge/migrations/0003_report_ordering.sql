-- SPDX-License-Identifier: AGPL-3.0-only
-- Distinguish capacity from hourly limits, and never regress a more recent observation.
DROP TRIGGER bounded_reports;
CREATE TRIGGER bounded_reports BEFORE INSERT ON price_reports
  WHEN (SELECT count(*) FROM price_reports WHERE client_id = NEW.client_id
    AND observed_at > strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-1 hour')) >= 30
BEGIN
  SELECT RAISE(ABORT, 'prototype_rate_limit');
END;
CREATE TRIGGER report_capacity BEFORE INSERT ON price_reports
  WHEN (SELECT count(*) FROM price_reports) >= 10000
BEGIN
  SELECT RAISE(ABORT, 'prototype_capacity_limit');
END;
DROP TRIGGER publish_sample_report;
CREATE TRIGGER publish_sample_report AFTER INSERT ON price_reports BEGIN
  INSERT INTO current_prices(station_id, fuel_type, price_milli, observed_at, source)
    VALUES (NEW.station_id, NEW.fuel_type, NEW.price_milli, NEW.observed_at, 'community-prototype')
    ON CONFLICT(station_id, fuel_type) DO UPDATE SET
      price_milli = excluded.price_milli, observed_at = excluded.observed_at, source = excluded.source
    WHERE excluded.observed_at >= current_prices.observed_at;
END;
