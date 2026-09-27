// SPDX-License-Identifier: AGPL-3.0-only
// The site build's station files (one per area, index.json and ids.json), generated from the snapshot, served as the ASSETS binding.
import {execFileSync} from 'node:child_process';
import {mkdtempSync, readdirSync, readFileSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';

export const stationFiles = new Map();
const folder = mkdtempSync(join(tmpdir(), 'openfuel-areas-'));
try {
  execFileSync('python3', [new URL('../../tools/import_live_data.py', import.meta.url).pathname, '--areas', join(folder, 'stations')]);
  for (const name of readdirSync(join(folder, 'stations'))) stationFiles.set(name, readFileSync(join(folder, 'stations', name), 'utf8'));
} finally { rmSync(folder, {recursive: true, force: true}); }

/** An ASSETS binding: station files by name, 404 for other station paths, and 'site' for everything else. */
export function assets(requests = []) {
  return {fetch: async request => {
    const {pathname} = new URL(request.url);
    requests.push(pathname);
    const name = pathname.match(/^\/data\/stations\/([^/]+)$/)?.[1];
    if (name === undefined) return new Response('site');
    return stationFiles.has(name) ? new Response(stationFiles.get(name), {headers: {'content-type': 'application/json'}}) : new Response('Not found', {status: 404});
  }};
}
