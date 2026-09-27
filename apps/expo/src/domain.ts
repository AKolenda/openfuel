export const fuels = ['regular', 'premium', 'diesel'] as const;
export type Fuel = typeof fuels[number];
export type Coordinates = { latitude: number; longitude: number };
export type Station = Coordinates & {
  id: string;
  name: string;
  brand: string;
  address: string;
  brandKey?: string | null;
  brandLogoUrl?: string | null;
  brandLogoSourceUrl?: string | null;
  distanceMetres: number;
  prices: Record<Fuel, number | null>;
  observedAt: Partial<Record<Fuel, string>>;
  priceSources: Partial<Record<Fuel, string>>;
};
export type StationResponse = {
  mode: 'live';
  is_demo: false;
  stations: Station[];
  coverage?: { message?: string; [key: string]: unknown };
};

export function priceLabel(price: number | null | undefined): string {
  return typeof price === 'number' ? (price / 10).toFixed(1) : '—';
}

// The sign shows cents/litre (149.9), while the API stores CAD millidollars/litre (1499).
export function parseCents(value: string): number | null {
  const normalized = value.trim().replace(',', '.');
  if (!/^\d{2,3}(\.\d)?$/.test(normalized)) return null;
  const milli = Math.round(Number(normalized) * 10);
  return milli >= 500 && milli <= 3999 ? milli : null;
}

export function validateResponse(input: unknown): StationResponse {
  if (!input || typeof input !== 'object') throw new Error('Invalid station response.');
  const data = input as Partial<StationResponse>;
  if (data.is_demo !== false || data.mode !== 'live' || !Array.isArray(data.stations)) {
    throw new Error('This API is not serving real station data. Check the API address.');
  }
  for (const station of data.stations) {
    if (!station || typeof station.id !== 'string' || typeof station.name !== 'string' ||
      !Number.isFinite(station.latitude) || Math.abs(station.latitude) > 90 ||
      !Number.isFinite(station.longitude) || Math.abs(station.longitude) > 180 ||
      !station.prices || (station as Station & { synthetic?: boolean }).synthetic === true) {
      throw new Error('The API returned an invalid station.');
    }
    for (const fuel of fuels) {
      const price = station.prices[fuel];
      if (price !== null && (!Number.isInteger(price) || price < 500 || price > 3999)) {
        throw new Error('The API returned an invalid fuel price.');
      }
    }
  }
  return data as StationResponse;
}

export function reportAge(observedAt: string | undefined, now = Date.now()): string {
  if (!observedAt || !Number.isFinite(Date.parse(observedAt))) return 'Time unavailable';
  const minutes = Math.max(0, Math.floor((now - Date.parse(observedAt)) / 60_000));
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes} min ago`;
  if (minutes < 1440) return `${Math.floor(minutes / 60)} hr ago`;
  const days = Math.floor(minutes / 1440);
  return `${days} ${days === 1 ? 'day' : 'days'} ago`;
}

/** A report this device made, as the server confirmed it, and when the confirmation arrived on this device. */
export type OwnReport = { station_id: string; fuel_type: Fuel; price_milli: number; observed_at: string; confirmedAt: number };

// Other Worker instances can answer with the price from before a report for about 15 seconds, and an
// HTTP cache for 15 more, so for 30 seconds this device lays its confirmed reports over every station list.
export const OWN_REPORT_MS = 30_000;

/** Shows reports from the last 30 seconds where the list has no price for that fuel or an older one;
 * a price someone else observed after the report wins. Without a recent report the list is returned as it is. */
export function withOwnReports(stations: Station[], reports: OwnReport[], now = Date.now()): Station[] {
  const recent = reports.filter(report => now - report.confirmedAt >= 0 && now - report.confirmedAt < OWN_REPORT_MS);
  if (!recent.length) return stations;
  return stations.map(station => recent.reduce((shown, report) => {
    const fuel = report.fuel_type;
    if (report.station_id !== shown.id ||
      (shown.prices[fuel] !== null && Date.parse(shown.observedAt?.[fuel] ?? '') >= Date.parse(report.observed_at))) return shown;
    return {
      ...shown,
      prices: { ...shown.prices, [fuel]: report.price_milli },
      observedAt: { ...shown.observedAt, [fuel]: report.observed_at },
      priceSources: { ...shown.priceSources, [fuel]: 'community-unverified' },
    };
  }, station));
}

export function sortedStations(stations: Station[], fuel: Fuel, sort: 'distance' | 'price'): Station[] {
  return [...stations].sort((a, b) => {
    if (sort === 'price') {
      const delta = (a.prices[fuel] ?? Infinity) - (b.prices[fuel] ?? Infinity);
      if (delta && !Number.isNaN(delta)) return delta;
    }
    return (a.distanceMetres ?? Infinity) - (b.distanceMetres ?? Infinity);
  });
}

// Only catalog hosts may receive a station-logo request. No arbitrary station URL.
export function safeLogoUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password && !url.port &&
      ['thumb.wikimedia.org', 'www.fuel.crs', 'www.shell.ca','www.tempo.crs'].includes(url.hostname)
      ? url.href : null;
  } catch { return null; }
}

export function areaSnapshot(data: StationResponse, point: Coordinates, label: string, timestamp: string) {
  const coarse = { latitude: Math.round(point.latitude * 100) / 100, longitude: Math.round(point.longitude * 100) / 100 };
  const radians = (value: number) => value * Math.PI / 180;
  const stations = data.stations.map(station => {
    const a = Math.sin(radians(station.latitude - coarse.latitude) / 2) ** 2 +
      Math.cos(radians(coarse.latitude)) * Math.cos(radians(station.latitude)) *
      Math.sin(radians(station.longitude - coarse.longitude) / 2) ** 2;
    return { ...station, distanceMetres: Math.round(6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, a)))) };
  });
  // Discard response.location and distances relative to a precise device fix.
  return { data: { mode: 'live', is_demo: false, stations, coverage: data.coverage }, point: coarse,
    label: label.includes('location') ? 'Nearby area' : label, timestamp };
}
