import { type Coordinates, type Fuel, validateResponse } from './domain';

export const API_URL = (process.env.EXPO_PUBLIC_API_URL || 'https://openfuel.ca/api/v1').replace(/\/$/, '');

async function request(path: string, init?: RequestInit) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 35_000);
  try {
    const response = await fetch(`${API_URL}${path}`, { ...init, signal: controller.signal });
    const body = await response.json();
    if (!response.ok) throw new Error(body.message || (response.status === 429 ? 'Too many requests. Please try again shortly.' : `Request failed (${response.status}).`));
    return body;
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw new Error('The connection timed out. Try again.');
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

export async function fetchStations(coordinates: Coordinates) {
  // Round discovery coordinates to roughly 100 m. No GPS trail or background updates.
  const params = new URLSearchParams({ lat: coordinates.latitude.toFixed(3), lon: coordinates.longitude.toFixed(3), radius: '10000' });
  return validateResponse(await request(`/stations?${params}`));
}

export async function searchCities(query: string): Promise<Array<Coordinates & { name: string }>> {
  const data = await request(`/geocode?q=${encodeURIComponent(query.trim())}`);
  if (!Array.isArray(data.results)) throw new Error('City search is unavailable.');
  return data.results.filter((place: Coordinates & { name: string }) =>
    typeof place.name === 'string' && Number.isFinite(place.latitude) && Number.isFinite(place.longitude));
}

export async function submitReport(body: { station_id: string; fuel_type: Fuel; price_milli: number; client_id: string; request_id: string }) {
  const response = await request('/reports', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const report = response.report;
  if (response.ok !== true || response.is_demo !== false || !report ||
    report.id !== body.request_id || report.station_id !== body.station_id ||
    report.fuel_type !== body.fuel_type || report.price_milli !== body.price_milli ||
    typeof report.observed_at !== 'string' || !Number.isFinite(Date.parse(report.observed_at))) {
    throw new Error('The server did not confirm this report. Refresh before trying again.');
  }
  return response;
}
