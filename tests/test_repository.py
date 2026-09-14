# SPDX-License-Identifier: AGPL-3.0-only
from pathlib import Path
from html.parser import HTMLParser
from zipfile import ZipFile
import base64
import hashlib
import importlib.util
import json
import os
import plistlib
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import pytest
import yaml
from tools import project
ROOT = Path(__file__).resolve().parents[1]

class Tags(HTMLParser):
    def __init__(self,text): super().__init__(); self.tags=[]; self.feed(text)
    def handle_starttag(self,tag,attrs): self.tags.append((tag,dict(attrs)))

def test_all_components_have_one_canonical_root():
    manifest=json.loads((ROOT/'monorepo.json').read_text())
    for component in manifest['components'].values(): assert (ROOT/component['path']).is_dir()
    assert not (ROOT/'android').exists() and not (ROOT/'ios-starter').exists()

def test_duplicate_android_inputs_recorded():
    inputs=json.loads((ROOT/'docs/provenance/inputs.json').read_text())
    assert inputs['comparison']['android_source_files_identical']
    assert inputs['comparison']['different_common_files']==['README.md']
    assert len(inputs['source_archives'])==3
    assert all(len(value)==64 for value in inputs['source_archives'].values())

def test_full_licence_and_notices_present():
    text=(ROOT/'LICENSE').read_text()
    assert text.startswith('GNU AFFERO GENERAL PUBLIC LICENSE')
    assert '13. Remote Network Interaction' in text
    assert (ROOT/'LICENSE').read_bytes()==(ROOT/'LICENSES/AGPL-3.0-only.txt').read_bytes()
    assert 'excluded' in (ROOT/'LICENSE.md').read_text().lower()

def test_no_inherited_active_client_spdx():
    for base in ['apps/android','apps/ios','services']:
        for p in (ROOT/base).rglob('*'):
            if p.is_file() and p.suffix in ['.kt','.swift','.py','.sql','.yml','.kts'] and not any(x in p.parts for x in ['.build','__pycache__','build']):
                assert 'SPDX-License-Identifier: MPL-2.0' not in p.read_text(),p
                assert 'SPDX-License-Identifier: AGPL-3.0-or-later' not in p.read_text(),p

def test_android_xml_and_bilingual_resources():
    folder=ROOT/'apps/android/app/src/main'
    for p in folder.rglob('*.xml'): ET.parse(p)
    en=ET.parse(folder/'res/values/strings.xml').getroot()
    fr=ET.parse(folder/'res/values-fr/strings.xml').getroot()
    keys={e.attrib['name'] for e in en}
    assert keys=={e.attrib['name'] for e in fr}
    source='\n'.join(p.read_text() for p in folder.rglob('*.kt'))
    assert set(re.findall(r'R\.string\.(\w+)',source))<=keys

def test_android_source_privacy_boundary():
    manifest=ET.parse(ROOT/'apps/android/app/src/main/AndroidManifest.xml').getroot()
    ns='{http://schemas.android.com/apk/res/android}'
    assert {p.get(ns+'name') for p in manifest.findall('uses-permission')} == {
        'android.permission.INTERNET', 'android.permission.ACCESS_FINE_LOCATION',
        'android.permission.ACCESS_COARSE_LOCATION'}
    assert manifest.find('application').get(ns+'usesCleartextTraffic') == '${openfuelCleartext}'
    build=(ROOT/'apps/android/app/build.gradle.kts').read_text()
    assert '.getOrElse("https://openfuel.ca/api/v1")' in build
    assert 'manifestPlaceholders["openfuelCleartext"] = localTestHost.toString()' in build
    assert 'emulatorTest && parsedApi.scheme == "http" && parsedApi.host == "10.0.2.2"' in build
    assert manifest.find('application').get(ns+'allowBackup')=='false'
    source='\n'.join(p.read_text() for p in (ROOT/'apps/android/app/src/main').rglob('*.kt'))
    map_source=(ROOT/'apps/android/app/src/main/java/ca/openfuel/prototype/LiveMap.kt').read_text()
    assert 'settings.allowFileAccess = false' in map_source
    assert 'settings.allowContentAccess = false' in map_source
    assert 'settings.setGeolocationEnabled(false)' in map_source
    assert 'MIXED_CONTENT_NEVER_ALLOW' in map_source
    assert 'override fun shouldOverrideUrlLoading' in map_source
    assert 'uri.host == "tile.openstreetmap.org"' in map_source
    assert 'ModalBottomSheet(' in source

def test_ios_configuration_uses_real_generator():
    config=yaml.safe_load((ROOT/'apps/ios/project.yml').read_text())
    assert config['packages']['OpenFuelCore']['path']=='OpenFuelCore'
    assert 'OpenFuel' in config['targets']
    assert (ROOT/'apps/ios/OpenFuelCore/Package.swift').exists()
    for name in ['Info.plist','Info-Debug.plist','PrivacyInfo.xcprivacy']:
        data=plistlib.loads((ROOT/'apps/ios/OpenFuel'/name).read_bytes())
        if name=='Info.plist': assert 'NSAppTransportSecurity' not in data
        assert not any('LocationUsageDescription' in k for k in data)

def test_fixture_single_source():
    assert (ROOT/'packages/fixtures/api-stations.json').read_bytes()==(ROOT/'apps/ios/OpenFuelCore/Tests/OpenFuelCoreTests/stations.json').read_bytes()
    data=json.loads((ROOT/'packages/fixtures/api-stations.json').read_text())
    assert data['region']=='demo-region' and len(data['stations'])==3

def test_contract_is_real_and_licenced():
    schema=json.loads((ROOT/'packages/contracts/openapi.json').read_text())
    assert schema['info']['license']['name']=='AGPL-3.0-only'
    assert '/v1/reports' in schema['paths'] and '/v1/audit/export' in schema['paths']

def test_website_docs_preview_routes_and_source_links():
    page=(ROOT/'apps/web/index.html').read_text()
    tags=Tags(page).tags
    assert any(t=='a' and a.get('href')=='docs/' for t,a in tags)
    assert any(t=='a' and a.get('href')=='downloads/openfuel-source.zip' for t,a in tags)
    docs=(ROOT/'apps/docs/index.html').read_text()
    assert 'href="../#project"' in docs and 'href="../"' in docs
    assert 'REFERENCE=' not in (ROOT/'apps/docs/app.js').read_text()
    assert 'APP64' not in (ROOT/'apps/web/app.js').read_text()

def test_local_web_assets_not_embedded_copies():
    for folder in [ROOT/'apps/web',ROOT/'apps/docs',ROOT/'apps/web/designs/variant-b']:
        assert (folder/'styles.css').exists() and (folder/'app.js').exists()
        for p in [folder/'index.html',folder/'styles.css',folder/'app.js']:
            assert 'data:image/' not in p.read_text(),p

def test_handbook_internal_routes_unique_and_real():
    pages=json.loads((ROOT/'apps/docs/pages.json').read_text())
    ids=[p['id'] for p in pages]; assert len(ids)==len(set(ids)) and len(ids)>=10
    for page in pages:
        for href in re.findall(r'href="#([^"]+)"',page['body']):
            assert href.split('/')[0] in ids,(page['id'],href)
    ios=next(p for p in pages if p['id']=='ios')['body']
    assert 'XcodeGen' in ios and 'apps/ios/project.yml' in ios
    ios_page = next(p for p in pages if p['id'] == 'ios')['body'].lower()
    assert 'xcode' in ios_page and 'source' in ios_page

@pytest.mark.parametrize('name',['quality.yml','website.yml','android.yml','ios.yml'])
def test_workflow_exists_and_has_no_publish_permissions(name):
    data=yaml.safe_load((ROOT/'.github/workflows'/name).read_text())
    assert data['permissions']=={'contents':'read'}
    # PyYAML's YAML1.1 treats unquoted on as boolean True; inspect either representation.
    triggers=data.get('on',data.get(True)); assert 'workflow_dispatch' in triggers
    assert 'pull_request_target' not in triggers
    assert data['jobs']

def test_native_workflow_paths():
    assert 'tools/project.py android-build' in (ROOT/'.github/workflows/android.yml').read_text()
    assert 'apps/android/app/build/outputs/apk/debug/app-debug.apk' in (ROOT/'.github/workflows/android.yml').read_text()
    assert 'tools/project.py ios-build' in (ROOT/'.github/workflows/ios.yml').read_text()

def test_js_syntax_when_node_available():
    node=shutil.which('node')
    if not node:pytest.skip('Node is optional outside CI; install it for standalone JS syntax checking')
    for p in project.source_files():
        if p.suffix != '.js' or not p.is_relative_to(ROOT/'apps'): continue
        result=subprocess.run([node,'--check',str(p)],capture_output=True,text=True)
        assert result.returncode==0,(p,result.stderr)

def test_packaging_excludes_secrets_outputs_and_fonts(tmp_path,monkeypatch):
    monkeypatch.setattr(project,'ROOT',tmp_path)
    (tmp_path/'README.md').write_text('sample')
    safe=tmp_path/'apps/web/index.html';safe.parent.mkdir(parents=True);safe.write_text('hello')
    for name in ['apps/web/.env','apps/web/secret.key','apps/web/font.ttf','apps/web/price.sqlite','apps/web/node_modules/huge.js','apps/web/build/a.apk','.local/review.json','apps/expo/.expo/settings.json','apps/web/signing.p12','apps/web/signing.pfx','apps/ios/profile.mobileprovision','apps/web/.npmrc','apps/web/.netrc','apps/web/price.db-journal']:
        p=tmp_path/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('must not ship')
    target=tmp_path/'export/source.zip';project.package_source(target)
    with ZipFile(target) as z:
        names=set(z.namelist())
        assert names=={'openfuel/README.md','openfuel/apps/web/index.html','openfuel/SHA256SUMS'}
        for line in z.read('openfuel/SHA256SUMS').decode().splitlines():
            digest,name=line.split('  ',1);assert hashlib.sha256(z.read('openfuel/'+name)).hexdigest()==digest

def test_packaging_does_not_follow_symlinks(tmp_path,monkeypatch):
    monkeypatch.setattr(project,'ROOT',tmp_path)
    assets=tmp_path/'packages/assets';assets.mkdir(parents=True)
    secret=tmp_path/'private';secret.mkdir();(secret/'token.txt').write_text('never')
    try:(assets/'linked').symlink_to(secret,target_is_directory=True)
    except OSError:pytest.skip('Symlinks unavailable on this platform')
    assert not project.source_files()

def test_source_archive_bytes_are_deterministic(tmp_path,monkeypatch):
    monkeypatch.setattr(project,'ROOT',tmp_path)
    (tmp_path/'README.md').write_text('Same bytes')
    a=project.package_source(tmp_path/'one.zip');b=project.package_source(tmp_path/'two.zip')
    assert a.read_bytes()==b.read_bytes()

def test_generated_handbook_payload_matches_editable_source():
    raw=(ROOT/'apps/docs/pages.js').read_text()
    value=raw.split('window.OPENFUEL_PAGES=',1)[1].rsplit(';',1)[0]
    assert json.loads(value)==json.loads((ROOT/'apps/docs/pages.json').read_text())

def test_live_map_uses_curated_remote_brand_metadata_without_bundled_images():
    source=(ROOT/'apps/web/preview/app.js').read_text()
    catalog=json.loads((ROOT/'packages/brands/catalog.json').read_text())
    assert set(catalog['imageHosts']) == {'thumb.wikimedia.org','www.fuel.crs','www.shell.ca','www.tempo.crs'}
    assert all(host in source for host in catalog['imageHosts'])
    assert 'freebiesupply.com' not in source
    assert not any(p.suffix.lower() in {'.png','.jpg','.webp','.svg'} for p in (ROOT/'packages/brands').rglob('*'))
    page=(ROOT/'apps/web/preview/index.html').read_text()
    assert 'vendor/leaflet.js' in page
    assert 'https://tile.openstreetmap.org' in page
    assert 'geolocation=(self)' in (ROOT/'apps/web/_headers').read_text()
    assert 'strict-origin-when-cross-origin' in page
