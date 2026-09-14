#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Exercise the exact bundled Expo map in Chromium with intercepted network data.

Run from the repository root with .venv/bin/python apps/expo/tests/map_browser.py.
This is a Leaflet/DOM regression check, not an Expo Go or native WebView test.
"""
from pathlib import Path
import json
import os
import struct
import subprocess
import zlib
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[3]


def png(width, height):
    def chunk(kind, value):
        return struct.pack('>I', len(value)) + kind + value + struct.pack('>I', zlib.crc32(kind + value) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress((b'\0' + b'\x28\x5b\x43' * width) * height)) + chunk(b'IEND', b''))


def main():
    # Evaluate only the trusted source template so the test cannot drift to a duplicate map.
    html = subprocess.check_output(['node', '--input-type=module', '-e', '''
import fs from 'node:fs';
const source = fs.readFileSync('apps/expo/src/OpenMap.tsx', 'utf8');
const leaflet = JSON.parse(fs.readFileSync('apps/expo/src/vendor/leaflet.json', 'utf8'));
const start = source.indexOf('const html = ') + 'const html = '.length;
const end = source.indexOf(';\\n\\nexport const OpenMap', start);
if (start < 13 || end < start) throw new Error('Map template not found');
process.stdout.write(new Function('leaflet', 'return ' + source.slice(start, end))(leaflet));
'''], cwd=ROOT, text=True)
    assert '<meta charset="utf-8">' in html
    checks = []
    with sync_playwright() as browser_api:
        browser = browser_api.chromium.launch(headless=True, executable_path=os.environ.get("CHROMIUM_PATH"))
        page = browser.new_page(viewport={'width': 390, 'height': 600})
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        def route(request):
            if request.request.url == 'https://thumb.wikimedia.org/wide-test.png':
                request.fulfill(status=200, content_type='image/png', body=png(2048, 128))
            elif request.request.url.startswith('https://tile.openstreetmap.org/'):
                request.fulfill(status=200, content_type='image/png', body=png(1, 1))
            else:
                request.abort()
        page.route('**/*', route)
        page.evaluate('window.messages=[];window.ReactNativeWebView={postMessage:value=>window.messages.push(JSON.parse(value))}')
        page.set_content(html)
        page.wait_for_function('window.messages.some(message=>message.type==="ready")')
        station = {'id': 'osm-node-123', 'name': 'Montréal </script><img src=x onerror="window.injected=true">',
                   'brand': 'Wide test brand', 'latitude': 53.55, 'longitude': -113.49,
                   'brandLogoUrl': 'https://thumb.wikimedia.org/wide-test.png',
                   'prices': {'regular': None, 'premium': 1629, 'diesel': None}}
        payload = {'stations': [station], 'fuel': 'regular', 'location': None}
        page.evaluate('window.setArea({latitude:53.55,longitude:-113.49,latitudeDelta:.09})')
        page.evaluate('data=>window.setStations(data)', payload)
        page.wait_for_function('document.querySelector(".price-pin img")?.complete && document.querySelector(".price-pin img")?.naturalWidth===2048')
        image = page.locator('.price-pin img').bounding_box()
        assert image and image['width'] == 28 and image['height'] == 26, image
        assert page.locator('.price-pin span').bounding_box()['width'] <= 98
        checks.append('wide remote PNG stays inside its 28×26 logo slot under Leaflet CSS')
        marker = page.locator('.price-pin')
        assert marker.get_attribute('aria-label') == station['name'] + ', no price reported'
        assert page.evaluate('window.injected === undefined')
        marker.click()
        assert page.evaluate('window.messages.at(-1)') == {'type': 'station', 'id': station['id']}
        checks.append('hostile station text remains inert, accessible and selectable')
        payload['fuel'] = 'premium'
        page.evaluate('data=>window.setStations(data)', payload)
        assert marker.get_attribute('aria-label') == station['name'] + ', 162.9 cents per litre'
        assert '162.9' in marker.inner_text()
        checks.append('fuel changes update marker price and accessible name')
        station['latitude'] = 53.551
        page.evaluate('data=>window.setStations(data)', payload)
        assert page.evaluate('markers.get("osm-node-123").marker.getLatLng().lat') == 53.551
        checks.append('unchanged marker artwork still follows corrected station coordinates')
        page.evaluate('window.setArea({latitude:53.55,longitude:550,latitudeDelta:.09})')
        region = page.evaluate('window.messages.filter(message=>message.type==="region").at(-1)')
        assert -180 <= region['longitude'] <= 180
        checks.append('world wrapping returns valid API longitude')
        page.evaluate('window.setStations({stations:[],fuel:"regular",location:null})')
        assert marker.count() == 0
        assert not errors, errors
        checks.append('removed stations clear their pins without script errors')
        browser.close()
    print(json.dumps({'passed': len(checks), 'checks': checks, 'scope': 'Exact Expo map HTML in Chromium; intercepted PNG and tile responses; no native-device claim'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
