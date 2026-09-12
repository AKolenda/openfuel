// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import {createWorker} from './worker.mjs';
const environment={SUPABASE_URL:'https://abcdefghijklmnopqrst.supabase.co',SUPABASE_PUBLISHABLE_KEY:'sb_publishable_12345678901234567890',ASSETS:{fetch:async()=>new Response('static')}};
const sample={region:'demo-region',stations:[{id:'test',name:'Test',currency:'CAD',volume_unit:'L',is_demo:true,price:null,private_evidence:'HIDDEN'}],next_cursor:null,moderator_email:'HIDDEN'};
const request=(path,init)=>new Request('https://openfuel.test'+path,init);
const response=(value=sample)=>new Response(JSON.stringify(value),{headers:{'content-type':'application/json'}});
test('serves static assets without contacting DB',async()=>{let calls=0;const w=createWorker(async()=>{calls++;return response()});assert.equal(await (await w.fetch(request('/docs/'),environment)).text(),'static');assert.equal(calls,0)});
test('health requires no database',async()=>{const r=await createWorker().fetch(request('/api/v1/health'),{});assert.equal(r.status,200);assert.equal((await r.json()).writesEnabled,false)});
for(const method of ['POST','PUT','DELETE','PATCH'])test(`rejects public ${method}`,async()=>assert.equal((await createWorker().fetch(request('/api/v1/stations',{method}),environment)).status,405));
for(const query of ['?region=bad%27sql','?fuel=gasoline','?latitude=45','?region=a&region=b','?limit=201','?limit=-1','?cursor=1.2','?payment=free'])test(`rejects ${query}`,async()=>assert.equal((await createWorker().fetch(request('/api/v1/stations'+query),environment)).status,400));
for(const key of ['', 'sb_secret_do_not_use_admin_key_here','eyJhbGciOiJhbGciOjEyfQ.service_role','sb_publishable_REPLACE_ME'])test(`rejects missing/privileged/placeholder key ${key.slice(0,12)}`,async()=>assert.equal((await createWorker().fetch(request('/api/v1/stations'),{...environment,SUPABASE_PUBLISHABLE_KEY:key})).status,503));
test('fixed RPC and no client credentials forwarded',async()=>{
 let captured;const w=createWorker(async(u,o)=>{captured={u:String(u),o};return response()});
 const r=await w.fetch(request('/api/v1/stations?region=ca-on-ottawa&fuel=diesel',{headers:{authorization:'Bearer PRIVATE_CLIENT',cookie:'secret'}}),environment);
 assert.equal(r.status,200);assert.equal(captured.u,environment.SUPABASE_URL+'/rest/v1/rpc/openfuel_stations_v1');assert.equal(captured.o.headers.authorization,undefined);assert.equal(captured.o.headers.cookie,undefined);assert.equal(captured.o.redirect,'error');assert.equal(JSON.parse(captured.o.body).p_region,'ca-on-ottawa');
 assert.equal((await r.json()).stations[0].private_evidence,undefined);
});
test('non-public upstream fields stripped',async()=>{const r=await createWorker(async()=>response()).fetch(request('/api/v1/stations'),environment);assert.ok(!(await r.text()).includes('HIDDEN'))});
test('private response bodies never reflected',async()=>{const r=await createWorker(async()=>new Response('password=PRIVATE',{status:500})).fetch(request('/api/v1/stations'),environment);assert.equal(r.status,502);assert.ok(!(await r.text()).includes('PRIVATE'))});
test('network failure is bounded generic failure',async()=>{const r=await createWorker(async()=>{throw new Error('DB password')}).fetch(request('/api/v1/stations'),environment);assert.equal(r.status,502)});
test('invalid returned price rejected',async()=>{const data=structuredClone(sample);data.stations[0].price={price_milli:'1.42'};assert.equal((await createWorker(async()=>response(data)).fetch(request('/api/v1/stations'),environment)).status,502)});
test('oversized responses rejected',async()=>assert.equal((await createWorker(async()=>new Response(' '.repeat(2_000_001))).fetch(request('/api/v1/stations'),environment)).status,502));
test('invalid upstream domain fails closed',async()=>assert.equal((await createWorker().fetch(request('/api/v1/stations'),{...environment,SUPABASE_URL:'https://evil.test'})).status,503));
test('regions allowlist',async()=>{const r=await createWorker(async()=>response({regions:[{id:'demo-region',station_count:6,private:'secret'}]})).fetch(request('/api/v1/regions'),environment);assert.deepEqual(await r.json(),{regions:[{id:'demo-region',station_count:6}]})});
test('unknown API path is JSON 404 not HTML SPA',async()=>assert.equal((await createWorker().fetch(request('/api/v1/admin'),environment)).status,404));
test('preflight never contacts upstream',async()=>{const r=await createWorker(()=>{throw new Error()}).fetch(request('/api/v1/stations',{method:'OPTIONS'}),environment);assert.equal(r.status,204);assert.equal(r.headers.get('access-control-allow-credentials'),null)});
test('HEAD has no body',async()=>{const r=await createWorker(async()=>response()).fetch(request('/api/v1/stations',{method:'HEAD'}),environment);assert.equal(await r.text(),'')});
const jwt=role=>[btoa('{"alg":"HS256"}'),btoa(JSON.stringify({role})),btoa('signature-is-validated-by-supabase')].join('.');
test('loopback local anonymous key works only in development',async()=>{
 const env={...environment,OPENFUEL_ENV:'development',SUPABASE_URL:'http://127.0.0.1:54321',SUPABASE_PUBLISHABLE_KEY:jwt('anon')};
 let target;const r=await createWorker(async(u)=>{target=String(u);return response()}).fetch(request('/api/v1/stations'),env);
 assert.equal(r.status,200);assert.equal(target,'http://127.0.0.1:54321/rest/v1/rpc/openfuel_stations_v1');
});
for(const state of ['staging','production'])test(`loopback dev key rejected in ${state}`,async()=>{
 const env={...environment,OPENFUEL_ENV:state,SUPABASE_URL:'http://127.0.0.1:54321',SUPABASE_PUBLISHABLE_KEY:jwt('anon')};
 assert.equal((await createWorker().fetch(request('/api/v1/stations'),env)).status,503);
});
test('local service role key is rejected too',async()=>assert.equal((await createWorker().fetch(request('/api/v1/stations'),{...environment,OPENFUEL_ENV:'development',SUPABASE_URL:'http://127.0.0.1:54321',SUPABASE_PUBLISHABLE_KEY:jwt('service_role')})).status,503));
test('local auth key cannot be redirected to a different host',async()=>assert.equal((await createWorker().fetch(request('/api/v1/stations'),{...environment,OPENFUEL_ENV:'development',SUPABASE_URL:'http://169.254.169.254:54321',SUPABASE_PUBLISHABLE_KEY:jwt('anon')})).status,503));

test('local modern publishable key is accepted without an admin key',async()=>{
 const env={...environment,OPENFUEL_ENV:'development',SUPABASE_URL:'http://127.0.0.1:54321'};
 assert.equal((await createWorker(async()=>response()).fetch(request('/api/v1/stations'),env)).status,200);
});
