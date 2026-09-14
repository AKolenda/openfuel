// SPDX-License-Identifier: AGPL-3.0-only
import corrections from './station-corrections.json' with {type: 'json'};
const byId = new Map(corrections.map(c => [c.station_id, c]));
/** Apply reviewed identity corrections only to the exact snapshot identity they checked.
 * A future upstream rename or move must be reviewed again, never silently overwritten. */
export function correctedStation(station) {
  const correction = byId.get(station.id);
  if (!correction || station.name !== correction.previous_name || station.brand !== correction.previous_brand ||
      Math.abs(station.latitude - correction.latitude) > .00001 || Math.abs(station.longitude - correction.longitude) > .00001) return station;
  return {...station, name: correction.name, brand: correction.brand,
    original_name: station.name, correction_source_url: correction.source_url, identity_checked_at: correction.checked_at};
}
