#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Capture the actual HTML design. These are BROWSER references, never native app screenshots."""
from pathlib import Path
import importlib.util,json,os,shutil,sys
ROOT=Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:sys.path.insert(0,str(ROOT))
from playwright.sync_api import sync_playwright
from tools.project import build_site

def main():
    build_site()
    spec=importlib.util.spec_from_file_location('webchecks',ROOT/'tests/browser_checks.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    out=ROOT/'packages/design/goldens/html';out.mkdir(parents=True,exist_ok=True)
    states=['list','cards','wide','sort','settings','about','detail','report']
    errors=[]
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path=os.environ.get('CHROMIUM_PATH') or shutil.which('chromium'),headless=True,args=['--no-sandbox'])
        for state in states:
            context=browser.new_context(viewport={'width':390,'height':844},device_scale_factor=1)
            context.route('**/*',lambda route:route.abort())
            page=context.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
            page.set_content(module.inline_document('/preview/'),wait_until='domcontentloaded');page.wait_for_timeout(100)
            if state=='cards':page.locator('[data-layout="cards"]').click()
            elif state=='wide':
                page.locator('[data-action="filters"]').click();page.wait_for_timeout(300)
                page.locator('input[name="mapButton"][value="full"]').check(force=True)
                page.locator('#filters-form [type="submit"]').click()
            elif state in ['sort','settings','about']:
                page.locator('[data-action="'+('filters' if state=='settings' else state)+'"]').click()
            elif state in ['detail','report']:
                page.locator('#station-list [data-select="parkside"]').click()
                if state=='report':page.locator('[data-report]').first.click()
            page.wait_for_timeout(350);page.mouse.move(0,0)
            page.screenshot(path=str(out/f'{state}.png'))
            context.close()
        browser.close()
    if errors:raise RuntimeError(str(errors))
    (out/'manifest.json').write_text(json.dumps({'type':'browser_reference_only','viewport':{'width':390,'height':844,'scale':1},'states':states,'network':'blocked; initial badges only','native_visual_parity':'not verified'},indent=2)+'\n')
    print(f'Captured {len(states)} BROWSER references, not Android/iOS screenshots: {out.relative_to(ROOT)}')
if __name__=='__main__':main()
