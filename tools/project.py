#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""One root entry point for independent Python, web, Swift and Kotlin projects.

Python 3.11+. No Node monorepo framework or shared mobile UI runtime is required.
The web build/serve/package commands use only the Python standard library.
"""
from __future__ import annotations
import argparse
import hashlib
import http.server
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import socketserver
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path: sys.path.insert(0, str(ROOT))
DIST = ROOT / 'dist'
EVIDENCE = ROOT / 'evidence'
COMPONENTS = {'api': ROOT/'services/api', 'registry': ROOT/'services/registry', 'ios': ROOT/'apps/ios', 'android': ROOT/'apps/android'}
SOURCE_DIRS = {'supabase','deploy','env','apps','services','packages','docs','tools','tests','evidence','LICENSES','.github'}
ROOT_FILES = {'.env.example','.dev.vars.example','.node-version','.python-version','wrangler.jsonc','package.json','package-lock.json','README.md','LICENSE','NOTICE','LICENSE.md','CONTRIBUTING.md','SECURITY.md','CHANGELOG.md','.gitignore','.gitleaks.toml','.gitattributes','.editorconfig','Makefile','requirements-dev.txt','pyproject.toml','monorepo.json','compose.yaml','openfuel.code-workspace'}
EXCLUDE_PARTS = {'.temp','.branches','.wrangler','.git','.venv','venv','node_modules','__pycache__','.pytest_cache','.mypy_cache','.ruff_cache','.gradle','.kotlin','.build','build','DerivedData','dist','.local','.idea','.swiftpm','.expo','.DS_Store'}
EXCLUDE_SUFFIXES = {'.pyc','.pyo','.apk','.aab','.jks','.keystore','.pem','.key','.p12','.pfx','.der','.crt','.cer','.credentials','.mobileprovision','.sqlite','.sqlite3','.db','.ttf','.otf','.woff','.woff2','.jar','.xcuserstate'}

class ToolUnavailable(RuntimeError):
    pass

def tool(name: str) -> str:
    found = shutil.which(name)
    if not found:
        raise ToolUnavailable(f'{name} is not installed or not on PATH. Run `python tools/project.py doctor` and see docs/BUILD.md. Nothing was built.')
    return found

def run(command: list[str], cwd: Path=ROOT, *, evidence: str|None=None, env: dict[str,str]|None=None) -> int:
    print('+ ' + ' '.join(command), flush=True)
    if evidence:
        completed = subprocess.run(command, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        output = completed.stdout.replace(str(ROOT), '<repo>')
        print(output, end='', flush=True)
        EVIDENCE.mkdir(exist_ok=True)
        (EVIDENCE/f'{evidence}.txt').write_text(output, encoding='utf-8')
        summary = EVIDENCE/'results.json'
        try: results = json.loads(summary.read_text())
        except (OSError, ValueError): results = {}
        results[evidence] = {'status':'passed' if completed.returncode==0 else 'failed','exit_code':completed.returncode,
          'command':[arg.replace(str(ROOT),'<repo>') for arg in command],
          'runtime':platform.system(), 'checked_at':datetime.now(timezone.utc).isoformat(), 'log':evidence+'.txt'}
        summary.write_text(json.dumps(results,indent=2)+'\n')
        if completed.returncode:
            raise subprocess.CalledProcessError(completed.returncode,command)
        return completed.returncode
    subprocess.run(command, cwd=cwd, env=env, check=True)
    return 0

def is_private_build_input(p: Path) -> bool:
    name = p.name.lower()
    if name.endswith('.example'): return False
    return (name.startswith(('.env', '.dev.vars')) or name.endswith('.env') or '.env.' in name or
            name in {'local.properties', 'gradle.properties.local', 'local.xcconfig', '.npmrc', '.netrc'} or
            any(marker in name for marker in ('.sqlite-', '.sqlite3-', '.db-')))

def source_files():
    """Explicit allowlist, no traversal through symlinked trees or hidden build state."""
    files=[]
    for name in sorted(ROOT_FILES):
        p=ROOT/name
        if p.is_file() and not p.is_symlink(): files.append(p)
    for name in sorted(SOURCE_DIRS):
        directory=ROOT/name
        if not directory.is_dir(): continue
        for current, folders, names in os.walk(directory,followlinks=False):
            folders[:]=sorted(n for n in folders if n not in EXCLUDE_PARTS and not (Path(current)/n).is_symlink() and not n.endswith('.xcodeproj'))
            for n in sorted(names):
                p=Path(current)/n
                if p.is_symlink() or n in EXCLUDE_PARTS or is_private_build_input(p): continue
                if p.suffix.lower() in EXCLUDE_SUFFIXES and p != ROOT/'apps/android/gradle/wrapper/gradle-wrapper.jar': continue
                if n in {'local.properties','gradle.properties.local','Local.xcconfig'} or n.endswith(('-wal','-shm')): continue
                files.append(p)
    return sorted(set(files))

def package_source(output: Path|None=None) -> Path:
    output = output or DIST/'openfuel-source.zip'
    output=output.resolve();output.parent.mkdir(parents=True,exist_ok=True)
    manifest=[]
    files=[p for p in source_files() if p.resolve()!=output]
    with tempfile.NamedTemporaryFile(dir=output.parent,suffix='.zip',delete=False) as tmp:
        temporary=Path(tmp.name)
    try:
        with ZipFile(temporary,'w',compression=ZIP_DEFLATED,compresslevel=6) as z:
            for p in files:
                data=p.read_bytes();rel=p.relative_to(ROOT).as_posix()
                zi=ZipInfo('openfuel/'+rel,date_time=(2026,9,6,0,0,0))
                mode=0o755 if p.suffix=='.sh' or p.stat().st_mode & 0o111 else 0o644
                zi.create_system=3;zi.external_attr=(0o100000|mode)<<16;zi.compress_type=ZIP_DEFLATED
                z.writestr(zi,data)
                manifest.append(f'{hashlib.sha256(data).hexdigest()}  {rel}')
            zi=ZipInfo('openfuel/SHA256SUMS',date_time=(2026,9,6,0,0,0));zi.external_attr=(0o100644)<<16;zi.compress_type=ZIP_DEFLATED
            z.writestr(zi,'\n'.join(manifest)+'\n')
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    print(f'Source archive: {output.relative_to(ROOT) if output.is_relative_to(ROOT) else output} ({len(files)} source files; no native binaries)')
    return output

def _copy_tree(source: Path, target: Path):
    target.mkdir(parents=True,exist_ok=True)
    for p in source.rglob('*'):
        if any(x in EXCLUDE_PARTS for x in p.relative_to(source).parts) or p.is_symlink() or is_private_build_input(p) or p.suffix.lower() in EXCLUDE_SUFFIXES: continue
        dest=target/p.relative_to(source)
        if p.is_dir():dest.mkdir(parents=True,exist_ok=True)
        elif p.is_file():dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,dest)

def build_site(*, include_source: bool=True) -> Path:
    site=DIST/'site'
    if site.exists(): shutil.rmtree(site)
    site.mkdir(parents=True)
    _copy_tree(ROOT/'apps/web',site)
    _copy_tree(ROOT/'apps/docs',site/'docs')
    pages=json.loads((ROOT/'apps/docs/pages.json').read_text())
    generated='/* SPDX-License-Identifier: AGPL-3.0-only; generated from pages.json. */\nwindow.OPENFUEL_PAGES='+json.dumps(pages,ensure_ascii=False,separators=(',',':'))+';\n'
    (ROOT/'apps/docs/pages.js').write_text(generated)
    (site/'docs/pages.js').write_text(generated)
    _copy_tree(ROOT/'packages/assets',site/'shared/assets')
    _copy_tree(ROOT/'packages/contracts',site/'shared/contracts')
    _copy_tree(ROOT/'packages/fixtures',site/'shared/fixtures')
    # Public handbook documents only. Do not publish the working tree, .local or arbitrary files.
    (site/'handbook').mkdir()
    for p in sorted((ROOT/'docs').glob('*.md')):shutil.copyfile(p,site/'handbook'/p.name)
    shutil.copyfile(ROOT/'LICENSE',site/'LICENSE.txt')
    shutil.copyfile(ROOT/'NOTICE',site/'NOTICE.txt')
    live_contract=ROOT/'packages/contracts/live-openapi.json'
    published_contract=live_contract if live_contract.exists() else ROOT/'packages/contracts/prototype-openapi.json'
    if published_contract.exists():
        shutil.copyfile(published_contract,site/'openapi.json')
    logs={p.stem:p.read_text() for p in EVIDENCE.glob('*.txt') if p.stat().st_size<250_000}
    try: results=json.loads((EVIDENCE/'results.json').read_text())
    except (OSError,ValueError): results={}
    (site/'docs/evidence.js').write_text('/* Generated from executed checks; native app builds are separate. */\nwindow.OPENFUEL_EVIDENCE='+json.dumps({'results':results,'logs':logs})+';\n')
    from tools.public_config import emit_public_config
    emit_public_config(ROOT, site)
    if include_source:
        (site/'downloads').mkdir();shutil.copyfile(package_source(),site/'downloads/openfuel-source.zip')
    apk=Path(os.environ.get('OPENFUEL_ANDROID_APK', ROOT/'apps/android/app/build/outputs/apk/debug/app-debug.apk'))
    if apk.is_file():
        if apk.stat().st_size > 25 * 1024 * 1024:
            raise RuntimeError('The Android APK exceeds the Workers 25 MiB asset limit. Build the compact APK or set OPENFUEL_ANDROID_APK to a verified compact build.')
        downloads=site/'downloads';downloads.mkdir(exist_ok=True)
        shutil.copyfile(apk,downloads/'openfuel-android.apk')
        digest=hashlib.sha256(apk.read_bytes()).hexdigest()
        (downloads/'openfuel-android.apk.sha256').write_text(digest+'  openfuel-android.apk\n')
        (downloads/'release.json').write_text(json.dumps({
            'product':'OpenFuel', 'channel':'public-alpha', 'data':'real OpenStreetMap stations; unverified community prices',
            'android':{'path':'/downloads/openfuel-android.apk','bytes':apk.stat().st_size,
                       'sha256':digest,'minimumAndroid':'8.0','signing':'debug'},
            'source':'/downloads/openfuel-source.zip',
            'ios':'SwiftUI source; macOS/Xcode required to build'
        },indent=2)+'\n')
    print('Static website: dist/site/ — website /, handbook /docs/, station map /preview/')
    return site

class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('X-Content-Type-Options','nosniff')
        self.send_header('Referrer-Policy','no-referrer')
        self.send_header('Cache-Control','no-cache')
        super().end_headers()
    def log_message(self,*args):
        pass  # Do not accumulate map/search access logs in the development server.

def serve(args):
    from functools import partial
    site=build_site()
    handler=partial(QuietHandler,directory=str(site))
    with http.server.ThreadingHTTPServer((args.host,args.port),handler) as server:
        print(f'Open http://{args.host}:{server.server_port}/  (Ctrl+C to stop)',flush=True)
        print('This is a static development preview, not a deployed or authenticated service.',flush=True)
        try:server.serve_forever()
        except KeyboardInterrupt: pass

def contract(check: bool=False):
    sys.path.insert(0,str(COMPONENTS['api']))
    from openfuel.api import create_app,Settings
    schema=create_app(Settings(db_path=str(ROOT/'.local/contracts.sqlite3'))).openapi()
    path=ROOT/'packages/contracts/openapi.json'
    if check:
        if not path.exists() or json.loads(path.read_text())!=schema:
            raise RuntimeError('API contract drift. Run `python tools/project.py contracts` and review the change.')
        print('API OpenAPI contract matches the executable service.')
    else:
        path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(schema,indent=2)+'\n');print('Updated packages/contracts/openapi.json')

def fixtures(check: bool=False):
    canonical=ROOT/'packages/fixtures/api-stations.json'
    target=COMPONENTS['ios']/'OpenFuelCore/Tests/OpenFuelCoreTests/stations.json'
    if check:
        if canonical.read_bytes()!=target.read_bytes():raise RuntimeError('Swift API fixture drift. Run `python tools/project.py fixtures` and review.')
        print('Swift test fixture matches packages/fixtures/api-stations.json.')
    else:shutil.copyfile(canonical,target);print('Synced Swift API test resource.')

def ios_core():
    run([tool('swift'),'test','--package-path',str(COMPONENTS['ios']/'OpenFuelCore')],evidence='ios-core')

def android_core():
    out=ROOT/'.local/checks';out.mkdir(parents=True,exist_ok=True)
    jar=out/'android-core-checks.jar'
    run([tool('kotlinc'),str(COMPONENTS['android']/'app/src/main/java/ca/openfuel/prototype/Core.kt'),
       str(COMPONENTS['android']/'app/src/main/java/ca/openfuel/prototype/GeneratedSamples.kt'),str(COMPONENTS['android']/'scripts/CoreChecks.kt'),'-include-runtime','-d',str(jar)])
    run([tool('java'),'-jar',str(jar)],evidence='android-core')

def android_build():
    gradle=tool('gradle')
    completed=subprocess.run([gradle,'--version'],text=True,capture_output=True,check=True)
    if 'Gradle 8.11.1\n' not in completed.stdout:raise RuntimeError('Select Gradle 8.11.1; the imported Android project pins AGP 8.9.2.')
    if not (os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or (COMPONENTS['android']/'local.properties').exists()):
        raise ToolUnavailable('Android SDK path missing. Install API 35/build-tools 35.0.0, then set ANDROID_HOME.')
    run([gradle,'--no-daemon',':app:testDebugUnitTest',':app:assembleDebug'],cwd=COMPONENTS['android'],evidence='android-build')

def ios_generate():
    run([tool('xcodegen'),'generate','--spec','project.yml'],cwd=COMPONENTS['ios'])

def ios_build():
    if platform.system()!='Darwin':raise ToolUnavailable('A full iOS build requires macOS with Xcode. Swift core tests can run here; no iOS binary was built.')
    tool('xcodebuild');ios_generate()
    run([tool('xcodebuild'),'-project','OpenFuel.xcodeproj','-scheme','OpenFuel','-configuration','Debug',
         '-sdk','iphonesimulator','-destination','generic/platform=iOS Simulator',
         '-derivedDataPath',str(ROOT/'.local/ios-derived'),'CODE_SIGNING_ALLOWED=NO','build'],cwd=COMPONENTS['ios'],evidence='ios-build')

def doctor():
    print('OpenFuel toolchain status (discovery is not a successful build):')
    for name in ['python','git','node','swift','kotlinc','java','gradle','xcodegen','xcodebuild']:
        found=sys.executable if name=='python' else shutil.which(name)
        print(f'  {name:12} {found or "not found"}')
    print('  Android SDK ',os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or 'not configured')
    for name in ['pytest','fastapi','playwright']:
        print(f'  {name:12} {"installed" if importlib.util.find_spec(name) else "not installed"}')
    print('\nWebsite/handbook: Python only. Full Android: JDK 17+, Gradle 8.11.1, SDK 35. Full iOS: macOS + Xcode + XcodeGen.')

def api_seed():
    folder=ROOT/'.local';folder.mkdir(exist_ok=True)
    run([sys.executable,'-m','openfuel.cli','--db',str(folder/'openfuel.sqlite3'),'seed-demo'],cwd=COMPONENTS['api'])

def api_serve(args):
    folder=ROOT/'.local';folder.mkdir(exist_ok=True)
    env=os.environ.copy();env.setdefault('OPENFUEL_DB',str(folder/'openfuel.sqlite3'))
    # Explicit source URL for this exact running revision is required before public operation.
    run([sys.executable,'-m','uvicorn','openfuel.api:app','--host',args.host,'--port',str(args.port),'--no-access-log','--no-proxy-headers'],cwd=COMPONENTS['api'],env=env)

def check_all(args):
    run([sys.executable,str(ROOT/'tools/generate_mobile.py'),'--check'])
    contract(True);fixtures(True)
    run([sys.executable,'-m','pytest','-q','tests'],evidence='repository')
    run([sys.executable,'-m','pytest','-q'],cwd=COMPONENTS['api'],evidence='api')
    run([sys.executable,'-m','pytest','-q'],cwd=COMPONENTS['registry'],evidence='registry')
    if args.native_cores:android_core();ios_core()
    if args.web:
        build_site();run([sys.executable,str(ROOT/'tests/browser_checks.py')],evidence='browser')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    for name in ['doctor','site','api-seed','ios-core','android-core','android-build','ios-generate','ios-build']:sub.add_parser(name)
    for name,port in [('serve',4173),('api',8000)]:
        p=sub.add_parser(name);p.add_argument('--host',default='127.0.0.1');p.add_argument('--port',type=int,default=port)
    p=sub.add_parser('package');p.add_argument('--output',type=Path)
    for name in ['contracts','fixtures']:
        p=sub.add_parser(name);p.add_argument('--check',action='store_true')
    p=sub.add_parser('check');p.add_argument('--web',action='store_true');p.add_argument('--native-cores',action='store_true')
    args=parser.parse_args()
    actions={'doctor':doctor,'site':build_site,'api-seed':api_seed,'ios-core':ios_core,'android-core':android_core,
      'android-build':android_build,'ios-generate':ios_generate,'ios-build':ios_build}
    if args.command in actions:actions[args.command]()
    elif args.command=='serve':serve(args)
    elif args.command=='api':api_serve(args)
    elif args.command=='package':package_source(args.output)
    elif args.command=='contracts':contract(args.check)
    elif args.command=='fixtures':fixtures(args.check)
    elif args.command=='check':check_all(args)

if __name__=='__main__':
    try:main()
    except (RuntimeError,ToolUnavailable,subprocess.CalledProcessError,ModuleNotFoundError,OSError) as exc:
        print(f'ERROR: {exc}',file=sys.stderr);sys.exit(1)
