#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Compare actual native screenshots against HUMAN-APPROVED native goldens.

No auto-approval, resizing or missing-baseline pass. HTML references are for initial human design review;
same-platform native goldens then protect against regressions at a fixed device/OS/font/locale.
"""
from pathlib import Path
import argparse,json,sys
from PIL import Image,ImageChops
ROOT=Path(__file__).resolve().parents[1]
STATES=('list','cards','wide','sort','settings','about','detail','report')

def compare(reference:Path,current:Path,*,delta:int=16,allowed_fraction:float=.01):
    a=Image.open(reference).convert('RGB');b=Image.open(current).convert('RGB')
    if a.size!=b.size:raise ValueError(f'Image dimensions differ: {a.size} vs {b.size}; do not silently resize screenshots')
    diff=ImageChops.difference(a,b);r,g,bl=diff.split();mask=ImageChops.lighter(ImageChops.lighter(r,g),bl).point(lambda x:255 if x>delta else 0)
    hist=mask.histogram();fraction=hist[255]/(a.width*a.height)
    return {'passed':fraction<=allowed_fraction,'mismatching_fraction':fraction,'allowed_fraction':allowed_fraction,'dimensions':a.size},diff

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--platform',choices=['ios','android'],required=True);p.add_argument('--current',type=Path,required=True);p.add_argument('--reference',type=Path);p.add_argument('--output',type=Path,default=ROOT/'evidence/native-comparison');a=p.parse_args()
    ref=a.reference or ROOT/'packages/design/native-goldens'/a.platform
    manifest=ref/'approval.json'
    if not manifest.exists():raise ValueError('No human-approved native baseline exists. Run actual native capture and review against the HTML reference first.')
    approval=json.loads(manifest.read_text())
    if approval.get('status')!='human-reviewed' or not all(approval.get(k) for k in ['device','os','locale','reviewed_by']):raise ValueError('Baseline approval lacks explicit reviewer/device/OS/locale')
    a.output.mkdir(parents=True,exist_ok=True);results=[]
    for state in STATES:
        before=ref/f'{state}.png';after=a.current/f'{state}.png'
        if not before.exists() or not after.exists():raise ValueError(f'Missing real {a.platform} screenshot: {state}')
        result,diff=compare(before,after,delta=16,allowed_fraction=.01);result['state']=state;results.append(result)
        if not result['passed']:diff.save(a.output/f'{a.platform}-{state}-diff.png')
    (a.output/f'{a.platform}.json').write_text(json.dumps(results,indent=2)+'\n')
    if not all(r['passed'] for r in results):return 1
    print('Same-platform native regression check passed. No cross-platform pixel identity is claimed.');return 0
if __name__=='__main__':
    try:sys.exit(main())
    except (OSError,ValueError) as e:print('BLOCKED: '+str(e),file=sys.stderr);sys.exit(2)
