#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Check the actual built website, handbook and one canonical map preview.

Ordinary environments use real localhost navigation. In restricted review sandboxes,
Chromium may block even localhost URLs by administrator policy. In that case only,
HTTP routes are still checked with Python and the same local HTML/CSS/JS is inlined
for browser rendering. The result records this limitation rather than claiming a
successful browser-network navigation. Browser policy is never disabled or changed.
"""
from __future__ import annotations
import base64
from copy import deepcopy
from datetime import datetime, timezone
from functools import partial
from html import escape
import http.client
import http.server
from io import BytesIO
import json
import mimetypes
import os
from pathlib import Path
import re
import shutil
import sys
import threading
from urllib.parse import urlparse,unquote,parse_qs
from zipfile import ZipFile
from playwright.sync_api import sync_playwright,Error as PlaywrightError
ROOT=Path(__file__).resolve().parents[1]
SITE=ROOT/'dist/site';OUT=ROOT/'evidence/browser';OUT.mkdir(parents=True,exist_ok=True)
CHECKS=[]
MODE='http'
BROWSER_ERRORS=[]
EXTERNAL=[]
MAP_TILES=[]
BRAND_REQUESTS=[]
API_STATES={}
# Two public OSM records from the Canadian import. Any prices submitted below are
# isolated test observations: intercepted in this browser, never sent to OpenFuel.
STATIONS=[
    {'id':'osm-node-999521943','name':'Tempo','latitude':53.5485522,'longitude':-113.475735},
    {'id':'osm-node-638176403','name':'Hughes','latitude':53.54627,'longitude':-113.4797131},
]
for station in STATIONS:
    station.update(address='Address not recorded in OpenStreetMap',synthetic=False,prices={'regular':None,'premium':None,'diesel':None},ages={},observedAt={})
STATIONS[0].update(brandKey='tempo',brandLogoUrl='https://thumb.wikimedia.org/openfuel-test-brand.png',distanceMetres=497)
STATIONS[1].update(brandKey='hughes',brandLogoUrl='https://thumb.wikimedia.org/openfuel-test-missing.png',distanceMetres=845)
TILE=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jD1kAAAAASUVORK5CYII=')

def check(name,truth=True):
    if not truth:raise AssertionError(name)
    CHECKS.append(name)
    print('PASS '+name,flush=True)

def route_path(route):
    relative=unquote(urlparse(route).path).lstrip('/')
    if relative.startswith('repo-test/'):relative=relative[len('repo-test/'):]
    p=SITE/relative
    if p.is_dir() or not p.suffix:p=p/'index.html'
    p=p.resolve()
    if not p.is_relative_to(SITE.resolve()):raise ValueError('Outside the static site')
    return p

def inline_document(route):
    """Resolve only local static resources; never fetch remote data or alter app logic."""
    p=route_path(route);text=p.read_text()
    def css_link(m):
        href=m.group(1);path=(p.parent/href).resolve()
        if not path.is_relative_to(SITE.resolve()):raise ValueError('CSS outside site')
        css=path.read_text()
        def image_url(image):
            value=image.group(1).strip('"\'')
            if value.startswith(('data:','#','https:','http:')):return image.group(0)
            image_path=(path.parent/value).resolve()
            if not image_path.is_relative_to(SITE.resolve()) or not image_path.is_file():return image.group(0)
            mime=mimetypes.guess_type(image_path.name)[0] or 'application/octet-stream'
            return 'url(data:'+mime+';base64,'+base64.b64encode(image_path.read_bytes()).decode()+')'
        css=re.sub(r'url\(([^)]+)\)',image_url,css)
        return '<style>'+css+'</style>'
    text=re.sub(r'<link\b[^>]*rel="stylesheet"[^>]*href="([^"]+)"[^>]*>',css_link,text)
    text=re.sub(r'<link\b[^>]*href="([^"]+)"[^>]*rel="stylesheet"[^>]*>',css_link,text)
    def js_script(m):
        path=(p.parent/m.group(1)).resolve()
        if not path.is_relative_to(SITE.resolve()):raise ValueError('Script outside site')
        return '<script>'+path.read_text().replace('</script>','<\\/script>')+'</script>'
    text=re.sub(r'<script\s+src="([^"]+)"[^>]*>\s*</script>',js_script,text)
    def image(m):
        value=m.group(2)
        if value.startswith(('data:','http:','https:')):return m.group(0)
        image_path=(p.parent/value).resolve()
        if not image_path.is_relative_to(SITE.resolve()) or not image_path.is_file():return m.group(0)
        mime=mimetypes.guess_type(image_path.name)[0] or 'application/octet-stream'
        return m.group(1)+'data:'+mime+';base64,'+base64.b64encode(image_path.read_bytes()).decode()+m.group(3)
    text=re.sub(r'(<img\b[^>]*\bsrc=")([^"]+)(")',image,text)
    return text

class Handler(http.server.SimpleHTTPRequestHandler):
    def log_message(self,*args):pass
    def translate_path(self,path):
        # Serve the same static site below a repository-style prefix to catch root-absolute mistakes.
        if path.startswith('/repo-test/'):path=path[len('/repo-test'):]
        return super().translate_path(path)

def main():
    global MODE
    if not (SITE/'index.html').exists():raise RuntimeError('Run python tools/project.py site first')
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),partial(Handler,directory=str(SITE)))
    threading.Thread(target=server.serve_forever,daemon=True).start()
    origin=f'http://127.0.0.1:{server.server_port}'
    try:
        for path in ['/','/docs/','/docs/pages.js','/preview/','/shared/contracts/openapi.json','/designs/variant-b/','/repo-test/','/repo-test/docs/','/repo-test/preview/','/favicon.svg','/brand/openfuel-wordmark-light.svg','/brand/openfuel-icon-192.png','/brand/openfuel-icon-512.png','/preview/manifest.webmanifest']:
            connection=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=5)
            connection.request('GET',path);r=connection.getresponse();body=r.read()
            check('HTTP route '+path,r.status==200 and len(body)>0);connection.close()
        connection=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=5)
        connection.request('GET','/downloads/openfuel-source.zip');r=connection.getresponse();raw=r.read();connection.close()
        with ZipFile(BytesIO(raw)) as z:
            check('Source download contains all four apps',all('openfuel/'+name in z.namelist() for name in ['apps/web/index.html','apps/docs/index.html','apps/ios/project.yml','apps/android/settings.gradle.kts']))
            wrapper='openfuel/apps/android/gradle/wrapper/gradle-wrapper.jar'
            check('Source download has no app binaries, fonts or local data',not any((x.endswith(('.apk','.jar','.ttf','.woff2','.sqlite3')) and x!=wrapper) or '/.local/' in x or '/.git/' in x for x in z.namelist()))
        with sync_playwright() as p:
            executable=os.environ.get('CHROMIUM_PATH') or shutil.which('chromium') or shutil.which('google-chrome')
            kwargs={'headless':True,'args':['--no-sandbox']}
            if executable:kwargs['executable_path']=executable
            browser=p.chromium.launch(**kwargs)
            probe=browser.new_page()
            try:probe.goto(origin+'/',wait_until='load',timeout=10000)
            except PlaywrightError as e:
                if 'ERR_BLOCKED_BY_ADMINISTRATOR' not in str(e):raise
                MODE='local-resources-inlined; localhost browser navigation blocked by sandbox policy'
            probe.close()
            def new_page(width,height,location='denied'):
                context=browser.new_context(viewport={'width':width,'height':height},device_scale_factor=1,service_workers='block')
                api={'stations':deepcopy(STATIONS),'requests':[],'reports':[],'offline':False}
                API_STATES[id(context)]=api
                # Never request the operator's location. Successful coordinates are
                # the documented Edmonton city centre, injected only by this test.
                location_action={
                    'denied':'error({code:1});',
                    'granted':'success({coords:{latitude:53.55014,longitude:-113.46871,accuracy:25}});',
                    'pending':'window.__completeTestLocation=()=>success({coords:{latitude:49.2827,longitude:-123.1207,accuracy:25}});',
                }[location]
                context.add_init_script("Object.defineProperty(navigator,'geolocation',{value:{getCurrentPosition(success,error){"+location_action+"}}})")
                def guard(route):
                    url=route.request.url
                    parsed=urlparse(url)
                    if url.startswith(origin+'/api/v1/'):
                        api['requests'].append({'path':parsed.path,'query':parse_qs(parsed.query),'method':route.request.method})
                        if api['offline']:route.abort();return
                        if parsed.path.endswith('/stations'):
                            body={'mode':'live','is_demo':False,'stations':api['stations'],'coverage':{'returned_count':2,'truncated':False}}
                        elif parsed.path.endswith('/geocode'):
                            body={'results':[{'name':'Edmonton, Alberta, Canada','latitude':53.55014,'longitude':-113.46871}]}
                        elif parsed.path.endswith('/reports'):
                            report=route.request.post_data_json
                            api['reports'].append(report)
                            station=next(s for s in api['stations'] if s['id']==report['station_id'])
                            observed=datetime.now(timezone.utc).isoformat()
                            station['prices'][report['fuel_type']]=report['price_milli']
                            station['observedAt'][report['fuel_type']]=observed
                            body={'ok':True,'is_demo':False,'report':{**report,'id':report['request_id'],'observed_at':observed}}
                        else:raise AssertionError('Unexpected API route: '+parsed.path)
                        route.fulfill(status=200,content_type='application/json',body=json.dumps(body))
                    elif parsed.hostname=='tile.openstreetmap.org':
                        MAP_TILES.append(url)
                        route.fulfill(status=200,content_type='image/png',body=TILE)
                    elif parsed.hostname=='thumb.wikimedia.org':
                        BRAND_REQUESTS.append(url)
                        if 'missing' in parsed.path:route.abort()
                        else:route.fulfill(status=200,content_type='image/png',body=TILE)
                    elif url.startswith(origin+'/'):route.continue_()
                    elif url.startswith(('data:','blob:','about:')):route.continue_()
                    else:EXTERNAL.append(url);route.abort()
                context.route('**/*',guard)
                page=context.new_page();page.on('pageerror',lambda e:BROWSER_ERRORS.append(str(e)))
                page.set_default_timeout(7000)
                return context,page
            def load(page,path):
                if MODE=='http':page.goto(origin+path,wait_until='load')
                else:
                    page.goto('about:blank');page.set_content(inline_document(path),wait_until='domcontentloaded',timeout=10000)
                page.wait_for_timeout(90)
            def open_iframe(page,selector):
                iframe=page.locator(selector)
                path=iframe.get_attribute('src')
                check('Canonical preview frame route used',path in ['/preview/','preview/','../preview/','../../preview/'])
                handle=iframe.element_handle();frame=handle.content_frame()
                if MODE!='http':frame.set_content(inline_document('/preview/'),wait_until='domcontentloaded')
                frame.wait_for_selector('#location-status');return frame
            for width,height in [(1440,1000),(390,844),(320,668)]:
                context,page=new_page(width,height)
                prefix=f'{width}px '
                load(page,'/')
                check(prefix+'approved wordmark is used on landing',page.locator('.site-header .wordmark img').get_attribute('src')=='brand/openfuel-wordmark-light.svg')
                check(prefix+'website no horizontal overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
                check(prefix+'website source and docs routes',page.locator('a[href="docs/"]').count()>=1 and page.locator('a[href="downloads/openfuel-source.zip"]').count()>=1)
                page.locator('#language').click();check(prefix+'French toggle',page.locator('html').get_attribute('lang')=='fr')
                page.locator('#language').click()
                check(prefix+'primary app link opens the full application',page.locator('a[data-i18n="a-demo"]').get_attribute('href')=='/preview/')
                check(prefix+'Android APK is a real download link',page.locator('a[data-i18n="a-build"]').get_attribute('href')=='/downloads/openfuel-android.apk')
                page.locator('.phone-play').click()
                frame=open_iframe(page,'#demo-frame')
                check(prefix+'website location denial has no sample fallback',frame.locator('.station-card').count()==0 and 'permission is off' in frame.locator('#location-status').inner_text())
                page.locator('#demo-dialog [data-close]').click()
                if width in [1440,390]:page.screenshot(path=str(OUT/f'website-{width}.png'),full_page=False)
                load(page,'/docs/')
                check(prefix+'docs start page renders',page.locator('#page-title').inner_text()=='Get started')
                check(prefix+'no top-right AGPL badge',page.locator('.header .badge,.breadcrumb .badge,.license-badge').count()==0)
                if width>760:
                    page.locator('#search').fill('swift');check(prefix+'docs search', 'iOS development' in page.locator('#nav').inner_text());page.locator('#search').fill('')
                    welcome=page.locator('#nav a[aria-current="page"]')
                    style=welcome.evaluate('(e)=>({before:getComputedStyle(e,"::before").content,after:getComputedStyle(e,"::after").content})')
                    check(prefix+'no decorative active-menu dot',style['before'] in ['none','normal','""'] and style['after'] in ['none','normal','""'])
                else:
                    page.locator('#open-guides').click();page.wait_for_timeout(300)
                    box=page.locator('#guides-dialog').bounding_box();check(prefix+'documentation menu is a bottom sheet',abs(box['y']+box['height']-height)<2)
                    page.locator('#mobile-search').fill('ios');check(prefix+'mobile docs search','iOS development' in page.locator('#mobile-nav').inner_text())
                    page.keyboard.press('Escape')
                # Exercise every real docs route, not a screenshot stand-in.
                ids=[x['id'] for x in json.loads((ROOT/'apps/docs/pages.json').read_text())]
                for doc_id in ids:
                    page.evaluate('(hash)=>location.hash=hash',doc_id);page.wait_for_timeout(25)
                    check(prefix+'docs route '+doc_id,len(page.locator('#article').inner_text())>100)
                page.evaluate('location.hash="ios"');page.wait_for_timeout(30)
                check(prefix+'iOS limitation is truthful','Apple SDK compilation' in page.locator('#article').inner_text() and 'these have not been verified' in page.locator('#article').inner_text())
                page.evaluate('location.hash="testing"');page.wait_for_timeout(35)
                check(prefix+'evidence table exists',page.locator('#evidence-table').count()==1)
                if page.locator('[data-log]').count():
                    page.locator('[data-log]').first.click();check(prefix+'real output opens',len(page.locator('#log-output').inner_text())>10);page.keyboard.press('Escape')
                page.evaluate('location.hash="start"');page.wait_for_timeout(30)
                if width in [1440,390]:page.screenshot(path=str(OUT/f'documentation-{width}.png'),full_page=False)
                page.evaluate('location.hash="website"');page.wait_for_timeout(35)
                page.locator('[data-open-reference]:visible').first.click();frame=open_iframe(page,'#reference-frame')
                check(prefix+'docs use real map with location permission fallback',frame.locator('#map').count()==1 and frame.locator('.station-card').count()==0)
                page.locator('#reference-dialog [data-close]').last.click()
                load(page,'/preview/')
                check(prefix+'approved wordmark is used in the app header',page.locator('.search-header .wordmark img').get_attribute('src')=='/brand/openfuel-wordmark-light.svg')
                check(prefix+'app offers an installable web manifest',page.locator('link[rel=manifest]').get_attribute('href')=='manifest.webmanifest')
                check(prefix+'real map has attribution',page.get_by_role('link',name='OpenStreetMap contributors').count()==1)
                check(prefix+'no fictional station fallback',page.locator('.station-card').count()==0)
                check(prefix+'no station branding appears without station records',page.locator('img[data-brand-logo]').count()==0)
                check(prefix+'manual search remains available',page.get_by_role('searchbox',name='Search Canadian city or coordinates').count()==1)
                check(prefix+'save icon is an opaque vector control',page.locator('#saved-button svg').count()==1 and page.locator('#saved-button').evaluate('(e)=>getComputedStyle(e).backgroundColor')=='rgb(255, 255, 255)')
                if width<=760:check(prefix+'mobile header blocks map bleed-through',page.locator('.search-header').evaluate('(e)=>getComputedStyle(e).backgroundColor')=='rgb(255, 255, 255)')
                controls=[page.locator(selector).bounding_box() for selector in ['#area-selector','[data-fuel=regular]','[data-fuel=premium]','[data-fuel=diesel]','#saved-button']]
                check(prefix+'location fuels and saved fit one compact row',all(box and abs(box['y']-controls[0]['y'])<2 for box in controls) and controls[0]['x']>=0 and controls[-1]['x']+controls[-1]['width']<=width)
                page.locator('#area-selector').click()
                check(prefix+'area selector offers city device and map choices',page.locator('[data-area-action]').count()==3 and page.locator('#area-selector').get_attribute('aria-expanded')=='true')
                page.get_by_role('button',name='Search a Canadian city or coordinates',exact=True).click()
                check(prefix+'area selector focuses the available city search',page.locator('#place-search').evaluate('(e)=>e===document.activeElement') and page.locator('#area-selector').get_attribute('aria-expanded')=='false')
                page.locator('#map').focus();page.keyboard.press('ArrowRight')
                page.locator('#search-area').wait_for(state='visible')
                controls=[page.locator(selector).bounding_box() for selector in ['#locate-button','#about-button','#search-area']]
                check(prefix+'map controls stack recenter then info then area search',all(controls[i]['y']+controls[i]['height']<=controls[i+1]['y'] for i in range(2)))
                page.get_by_role('button',name='Premium',exact=True).click()
                check(prefix+'fuel selection works',page.get_by_role('button',name='Premium',exact=True).get_attribute('aria-pressed')=='true')
                page.locator('#about-button').click();page.wait_for_timeout(300)
                if width<=760:
                    box=page.locator('#about-dialog').bounding_box();check(prefix+'map About is a bottom sheet',abs(box['y']+box['height']-height)<2)
                check(prefix+'location privacy disclosed','browser asks' in page.locator('#about-dialog').inner_text().lower())
                page.keyboard.press('Escape')
                page.get_by_role('combobox',name='Sort stations').select_option('price')
                check(prefix+'price sort selection works',page.locator('#sort').input_value()=='price')
                if MODE=='http':
                    page.get_by_role('searchbox').fill('Edmonton')
                    page.get_by_role('button',name='Search places',exact=True).click()
                    page.get_by_role('button',name='Edmonton, Alberta, Canada',exact=True).click()
                    page.locator('.station-card').first.wait_for()
                    check(prefix+'location selector reflects the selected city',page.locator('#area-label').inner_text()=='Edmonton')
                    check(prefix+'city search loads actual station-shaped API records',page.locator('.station-card').count()==2)
                    check(prefix+'missing pump prices remain blank',page.locator('.station-price .missing').count()==2)
                    check(prefix+'map pins identify their station',page.get_by_role('button',name='Tempo: no reported price',exact=True).count()==1)
                    page.locator('.station-card .logo-loaded').first.wait_for()
                    check(prefix+'station logos load from the remote provider',page.locator('.station-card .logo-loaded img').first.get_attribute('src')==STATIONS[0]['brandLogoUrl'])
                    check(prefix+'map logos use the same remote provider',page.locator('.marker-brand img').count()>=1)
                    check(prefix+'failed logo keeps station initials visible',page.locator('.station-card').nth(1).locator('.brand-fallback').inner_text()=='HU')
                    page.get_by_role('button',name='View Tempo, Address not recorded in OpenStreetMap',exact=True).click()
                    destination=page.get_by_role('link',name='Google Maps',exact=True).get_attribute('href')
                    check(prefix+'directions use the real station coordinates','destination=53.5485522%2C-113.475735' in destination)
                    page.get_by_role('button',name='♡ Save station',exact=True).click()
                    check(prefix+'station favorite persists',page.get_by_role('button',name='♥ Saved — remove',exact=True).count()==1)
                    page.get_by_role('button',name='Share a pump price',exact=True).click()
                    page.locator('#report-price').fill('49.9')
                    page.locator('#report-observed').check()
                    page.get_by_role('button',name='Share price',exact=True).click()
                    check(prefix+'invalid pump prices rejected before posting','50.0 to 399.9' in page.locator('#report-error').inner_text() and not API_STATES[id(context)]['reports'])
                    page.locator('#report-price').fill('149.9')
                    page.get_by_role('button',name='Share price',exact=True).click()
                    page.locator('#report-dialog').wait_for(state='hidden')
                    page.get_by_role('button',name='149.9 cents per litre at Tempo',exact=True).wait_for()
                    report=API_STATES[id(context)]['reports'][0]
                    check(prefix+'report sends integer CAD thousandths and request identity',report['price_milli']==1499 and report['fuel_type']=='premium' and len(report['request_id'])>=16 and len(report['client_id'])>=16)
                    check(prefix+'report displays community verification status','Unverified' in page.locator('.station-card').first.inner_text())
                    page.get_by_role('button',name='Show saved stations',exact=True).click()
                    check(prefix+'favorite filter shows the saved station',page.locator('.station-card').count()==1)
                    API_STATES[id(context)]['offline']=True
                    page.get_by_role('button',name='Refresh',exact=True).click()
                    page.locator('#connection-status.offline').wait_for()
                    check(prefix+'offline failure preserves cached station and report',page.locator('.station-card').count()==1 and '149.9' in page.locator('.station-card').inner_text())
                    location_cache=page.evaluate('JSON.parse(localStorage.getItem("openfuel-live-v1:last-area"))')
                    areas_cache=page.evaluate('JSON.parse(localStorage.getItem("openfuel-live-v1:areas-v2"))')
                    check(prefix+'remembered area is rounded to hundredths of a degree',location_cache['lat']==53.55 and location_cache['lon']==-113.47)
                    check(prefix+'cache omits precise device-derived station distances',all('distanceMetres' not in s for area in areas_cache for s in area['stations']))
                    load(page,'/preview/')
                    page.locator('.station-card').first.wait_for()
                    check(prefix+'warm offline start immediately restores the last station area',page.locator('.station-card').count()==2 and 'saved area' in page.locator('#location-status').inner_text())
                    check(prefix+'saved area never impersonates a fresh GPS fix',page.locator('.user-location').count()==0 and 'Your location · accurate' not in page.locator('#location-status').inner_text())
                    page.get_by_role('button',name='Hide stations and show full map',exact=True).click()
                    check(prefix+'station sheet can fully hide for map-only browsing',not page.locator('.results-panel').is_visible() and page.get_by_role('button',name='Show stations',exact=True).is_visible())
                    page.get_by_role('button',name='Show stations',exact=True).click()
                    check(prefix+'map-only restore button brings stations back',page.locator('.results-panel').is_visible())
                    if width<=760:
                        grip=page.locator('#sheet-toggle').bounding_box()
                        page.mouse.move(grip['x']+grip['width']/2,grip['y']+grip['height']/2);page.mouse.down();page.mouse.move(grip['x']+grip['width']/2,grip['y']+grip['height']/2+70,steps=8);page.mouse.up()
                        check(prefix+'swiping the sheet grip down shows the full map',page.get_by_role('button',name='Show stations',exact=True).is_visible())
                        page.get_by_role('button',name='Show stations',exact=True).click()
                if width==390:page.screenshot(path=str(OUT/'map-390.png'))
                check(prefix+'map no horizontal overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
                context.close()
            if MODE=='http':
                context,page=new_page(390,844,location='granted')
                load(page,'/preview/')
                page.locator('.station-card').first.wait_for()
                check('Successful device location loads stations on entry','Your location' in page.locator('#location-status').inner_text())
                query=next(r['query'] for r in API_STATES[id(context)]['requests'] if r['path'].endswith('/stations'))
                check('Nearby API receives the device coordinates',query['lat']==['53.550140'] and query['lon']==['-113.468710'])
                point=page.locator('.user-location').bounding_box();panel=page.locator('.results-panel').bounding_box();header=page.locator('.search-header').bounding_box()
                check('Device marker stays in the visible map above mobile sheet',point['y']>header['y']+header['height'] and point['y']+point['height']<panel['y'])
                context.close()
                context,page=new_page(1440,1000,location='pending')
                load(page,'/preview/')
                page.get_by_role('searchbox').fill('Edmonton')
                page.get_by_role('button',name='Search places',exact=True).click()
                page.get_by_role('button',name='Edmonton, Alberta, Canada',exact=True).click()
                page.locator('.station-card').first.wait_for()
                before=len(API_STATES[id(context)]['requests'])
                page.evaluate('window.__completeTestLocation()')
                check('Late location permission response cannot replace a chosen city',page.locator('#location-status').inner_text()=='Edmonton, Alberta, Canada' and len(API_STATES[id(context)]['requests'])==before)
                context.close()
            context,page=new_page(1440,1000)
            load(page,'/designs/variant-b/')
            check('Retained design B loads','public' in page.title().lower())
            check('Design B links back to same docs',page.locator('a[href="../../docs/"]').count()>=1)
            if MODE=='http':
                load(page,'/repo-test/');page.locator('a[href="docs/"]').first.click();page.wait_for_selector('#article')
                check('Browser relative routes work beneath repository prefix','/repo-test/docs/' in page.url)
            context.close();browser.close()
        check('No JavaScript exceptions',not BROWSER_ERRORS)
        check('No external network calls; maps and logos fulfilled by test transport',not EXTERNAL and len(MAP_TILES)>0 and len(BRAND_REQUESTS)>0)
        report={'mode':MODE,'passed':len(CHECKS),'checks':CHECKS,'javascript_errors':BROWSER_ERRORS,'external_requests':EXTERNAL,
          'mock_map_tile_requests':len(MAP_TILES),
          'mock_brand_logo_requests':len(BRAND_REQUESTS),
          'scope':'Built UI at desktop/mobile widths, with denied/granted/pending location, intercepted city/station/report API and map tile transport, reporting validation, favorites and offline cache. Test prices never reach a real backend. Live backend integration and genuine map screenshots are recorded separately. Not native app screenshots.'}
        (ROOT/'evidence/browser-results.json').write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps(report,indent=2))
    finally:server.shutdown();server.server_close()

if __name__=='__main__':main()
