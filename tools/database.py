#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Plan/apply forward migrations only. Never reset or seed a remote database.

Project allowlist: deploy/projects.json (copy the example and configure distinct refs).
Credentials: SUPABASE_ACCESS_TOKEN and SUPABASE_DB_PASSWORD in environment or .env.migrations.
The script prints NO token/password/connection URL and never uses shell=True.
"""
from pathlib import Path
import argparse,json,os,re,shutil,subprocess,sys
ROOT=Path(__file__).resolve().parents[1]
PIN='2.48.3'
def credentials(root:Path,environ:dict)->dict:
    env=dict(environ);file=root/'.env.migrations'
    if file.exists():
        for line in file.read_text().splitlines():
            line=line.strip()
            if not line or line.startswith('#'):continue
            if '=' not in line:raise ValueError('Invalid migration env file')
            key,value=line.split('=',1);key=key.strip()
            if key not in ('SUPABASE_ACCESS_TOKEN','SUPABASE_DB_PASSWORD','SUPABASE_PROJECT_ID'):raise ValueError('Unexpected key in migration env file')
            env.setdefault(key,value.strip().strip('"\''))
    for key in ('SUPABASE_ACCESS_TOKEN','SUPABASE_DB_PASSWORD'):
        if not env.get(key) or env[key].startswith('REPLACE'):raise ValueError(f'Configure {key} outside public build variables')
    return env

def deployment_plan(root:Path,target:str,action:str,confirm:str|None,env:dict)->list[list[str]]:
    refs=json.loads((root/'deploy/projects.json').read_text())
    if target not in ('staging','production') or action not in ('plan','apply'):raise ValueError('Invalid target/action')
    if refs.get('staging')==refs.get('production'):raise ValueError('Staging and production must be different projects')
    for t in ('staging','production'):
        if not re.fullmatch(r'[a-z0-9]{20}',refs.get(t,'')):raise ValueError(f'Configure real {t} project ref in deploy/projects.json')
    ref=refs[target]
    if env.get('SUPABASE_PROJECT_ID') and env['SUPABASE_PROJECT_ID']!=ref:raise ValueError('CI project ID does not match the target allowlist')
    if action=='apply' and confirm!=ref:raise ValueError('Applying requires --confirm with the exact target project ref')
    commands=[['supabase','link','--project-ref',ref],['supabase','migration','list','--linked'],['supabase','db','push','--linked','--dry-run']]
    if action=='apply':commands.append(['supabase','db','push','--linked','--yes'])
    return commands

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['plan','apply']);p.add_argument('--target',required=True,choices=['staging','production']);p.add_argument('--confirm');a=p.parse_args()
    cli=shutil.which('supabase')
    if not cli:raise ValueError(f'Install Supabase CLI {PIN}; no database changes were attempted')
    version=subprocess.run([cli,'--version'],capture_output=True,text=True,check=True).stdout.strip()
    if version.splitlines()[0]!=PIN:raise ValueError(f'This workflow pins Supabase CLI {PIN}; review updates deliberately')
    env=credentials(ROOT,os.environ)
    for cmd in deployment_plan(ROOT,a.target,a.action,a.confirm,env):
        print('+ '+' '.join(cmd),flush=True)
        subprocess.run([cli,*cmd[1:]],cwd=ROOT,env=env,check=True)
if __name__=='__main__':
    try:main()
    except (ValueError,OSError,subprocess.CalledProcessError) as e:print(f'ERROR: {e}',file=sys.stderr);sys.exit(1)
