# SPDX-License-Identifier: AGPL-3.0-only
"""Executed tests for env/build/deployment boundaries; SQL checks here are STATIC only."""
import json
from pathlib import Path
from zipfile import ZipFile
import pytest
from tools import project
from tools.database import deployment_plan, credentials
from tools.public_config import read_public_config, emit_public_config
ROOT=Path(__file__).resolve().parents[1]


def test_public_defaults_use_live_api(tmp_path):
    c=read_public_config(tmp_path,{})
    assert c == {'environment':'development','apiBaseURL':'/api/v1','sourceURL':'/downloads/openfuel-source.zip','mode':'live','writesEnabled':True}


def test_public_build_never_serializes_private_env(tmp_path):
    (tmp_path/'.env').write_text('OPENFUEL_PUBLIC_ENV=staging\nSUPABASE_DB_PASSWORD=PRIVATE_SENTINEL\nSUPABASE_SECRET_KEY=sb_secret_PRIVATE_SENTINEL\n')
    c=read_public_config(tmp_path,{'SUPABASE_ACCESS_TOKEN':'PRIVATE_SENTINEL'})
    assert 'PRIVATE_SENTINEL' not in json.dumps(c)
    assert c['environment']=='staging'


def test_public_process_variables_override_envfile(tmp_path):
    (tmp_path/'.env').write_text('OPENFUEL_PUBLIC_ENV=staging\nOPENFUEL_PUBLIC_API_BASE_URL=/old\n')
    assert read_public_config(tmp_path,{'OPENFUEL_PUBLIC_API_BASE_URL':'https://public.example.ca/api/v1/'})['apiBaseURL']=='https://public.example.ca/api/v1'


@pytest.mark.parametrize('value',['http://api.example.ca','//evil.test','https://user:pass@host.test','/api?secret=x','https://example.ca/#secret','/api\\secret','https://example.ca/sb_secret_abc','postgresql://db/test'])
def test_invalid_public_url_rejected(tmp_path,value):
    with pytest.raises(ValueError):read_public_config(tmp_path,{'OPENFUEL_PUBLIC_API_BASE_URL':value})


def test_public_environment_is_not_arbitrary(tmp_path):
    with pytest.raises(ValueError):read_public_config(tmp_path,{'OPENFUEL_PUBLIC_ENV':'prod-password'})


def test_env_file_is_not_general_shell_code(tmp_path):
    (tmp_path/'.env').write_text('$(printenv)')
    with pytest.raises(ValueError):read_public_config(tmp_path,{})


@pytest.mark.parametrize('name',['.env','.env.production','.dev.vars','production.env','prod.env.local','Local.xcconfig','local.properties','secret.key'])
def test_static_build_cannot_copy_secret_inputs(tmp_path,name):
    src=tmp_path/'web';dst=tmp_path/'public';src.mkdir();(src/name).write_text('PRIVATE_SENTINEL');(src/'index.html').write_text('public')
    project._copy_tree(src,dst)
    assert (dst/'index.html').is_file()
    assert not (dst/name).exists()


def test_source_archive_includes_examples_never_values(tmp_path,monkeypatch):
    monkeypatch.setattr(project,'ROOT',tmp_path)
    for name in ['.env.example','.dev.vars.example','env/migrations.env.example','wrangler.jsonc','supabase/migrations/20260906000100_registry.sql']:
        p=tmp_path/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('public template')
    for name in ['.env','.dev.vars','env/production.env','env/prod.env.local','apps/ios/Config/Local.xcconfig','supabase/.temp/project-ref']:
        p=tmp_path/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('PRIVATE_SENTINEL')
    out=project.package_source(tmp_path/'bundle.zip')
    with ZipFile(out) as z:
        assert 'openfuel/.env.example' in z.namelist() and 'openfuel/.dev.vars.example' in z.namelist()
        assert 'openfuel/env/migrations.env.example' in z.namelist()
        assert not any(b'PRIVATE_SENTINEL' in z.read(n) for n in z.namelist())


def setup_refs(root):
    (root/'deploy').mkdir();refs={'staging':'s'*20,'production':'p'*20};(root/'deploy/projects.json').write_text(json.dumps(refs));return refs


def test_migration_plan_contains_no_mutation(tmp_path):
    refs=setup_refs(tmp_path);commands=deployment_plan(tmp_path,'staging','plan',None,{})
    assert commands[0][-1]==refs['staging']
    assert commands[-1]==['supabase','db','push','--linked','--dry-run']
    assert all('reset' not in c and '--include-seed' not in c for c in commands)


@pytest.mark.parametrize('target',['staging','production'])
def test_apply_requires_exact_project_confirmation(tmp_path,target):
    refs=setup_refs(tmp_path)
    for invalid in [None,'yes','production',refs['production' if target=='staging' else 'staging']]:
        with pytest.raises(ValueError):deployment_plan(tmp_path,target,'apply',invalid,{})
    assert deployment_plan(tmp_path,target,'apply',refs[target],{})[-1]==['supabase','db','push','--linked','--yes']


def test_ci_ref_must_match_declared_target(tmp_path):
    refs=setup_refs(tmp_path)
    with pytest.raises(ValueError):deployment_plan(tmp_path,'production','plan',None,{'SUPABASE_PROJECT_ID':refs['staging']})


def test_staging_and_production_cannot_share_db(tmp_path):
    refs=setup_refs(tmp_path);refs['production']=refs['staging'];(tmp_path/'deploy/projects.json').write_text(json.dumps(refs))
    with pytest.raises(ValueError):deployment_plan(tmp_path,'staging','plan',None,{})


def test_migration_credentials_separate_and_do_not_override_ci(tmp_path):
    (tmp_path/'.env.migrations').write_text('SUPABASE_ACCESS_TOKEN=local_token\nSUPABASE_DB_PASSWORD=local_password\n')
    e=credentials(tmp_path,{'SUPABASE_ACCESS_TOKEN':'ci_token'})
    assert e['SUPABASE_ACCESS_TOKEN']=='ci_token' and e['SUPABASE_DB_PASSWORD']=='local_password'


def test_unknown_secret_loader_key_rejected(tmp_path):
    (tmp_path/'.env.migrations').write_text('OPENFUEL_PUBLIC_SOURCE_URL=bad\n')
    with pytest.raises(ValueError):credentials(tmp_path,{})


def test_only_one_active_migrations_directory():
    assert len(list((ROOT/'supabase/migrations').glob('*.sql')))==2
    assert not (ROOT/'services/registry/schema-draft.sql').exists()
    assert (ROOT/'docs/provenance/registry-schema-draft.sql').exists()
    assert not any('seed' in p.name for p in (ROOT/'supabase/migrations').iterdir())


def test_private_schema_not_http_exposed_static_config():
    import tomllib
    config=tomllib.loads((ROOT/'supabase/config.toml').read_text())
    assert config['api']['schemas']==['public']
    sql=(ROOT/'supabase/migrations/20260906000100_registry.sql').read_text()
    for table in ['stations','events','price_observations','ledger_head','proposals','evidence']:
        assert f'ALTER TABLE app_private.{table} ENABLE ROW LEVEL SECURITY;' in sql
    assert 'REVOKE ALL ON ALL TABLES IN SCHEMA app_private FROM PUBLIC,anon,authenticated,service_role;' in sql


def test_mobile_artwork_resources_have_identical_bytes():
    canonical=(ROOT/'packages/mobile/basemap.png').read_bytes()
    assert (ROOT/'apps/ios/OpenFuel/Resources/demo_basemap.png').read_bytes()==canonical
    assert (ROOT/'apps/android/app/src/main/res/drawable-nodpi/demo_basemap.png').read_bytes()==canonical


def test_mobile_source_never_has_database_keys():
    for base in ['apps/ios/OpenFuel','apps/android/app/src/main']:
        for p in (ROOT/base).rglob('*'):
            if p.suffix not in ['.swift','.kt','.xml','.plist']:continue
            assert 'sb_secret_' not in p.read_text()
            assert 'SUPABASE_DB_PASSWORD' not in p.read_text()


def test_static_build_does_not_apply_migrations():
    import inspect
    build=inspect.getsource(project.build_site)
    assert 'db push' not in build and 'database.py' not in build
