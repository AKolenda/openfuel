// SPDX-License-Identifier: AGPL-3.0-only
/** Read-only edge facade. Native clients do NOT receive database/admin keys.
 * Static assets are Cloudflare-hosted; curated reads come from fixed Supabase RPCs.
 * No arbitrary SQL/URL proxy, cookies, request-body logs, or mutation endpoint.
 */
const headers = {
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'no-referrer',
  'access-control-allow-origin': '*', // Anonymous PUBLIC facts only. Never credentialed requests.
};
const json = (body, status = 200, extra = {}) => new Response(JSON.stringify(body), {status, headers:{...headers,...extra}});
const publicKey = (key) => typeof key === 'string' && /^sb_publishable_[A-Za-z0-9_-]{10,}$/.test(key) && !key.includes('REPLACE');
function localAnonKey(key) {
  try {
    if(typeof key!=='string'||key.length>4096)return false;
    const parts=key.split('.');if(parts.length!==3)return false;
    const payload=JSON.parse(atob(parts[1].replace(/-/g,'+').replace(/_/g,'/')));
    // This is a role filter, NOT signature validation. Supabase validates the signature.
    return payload.role==='anon';
  }catch{return false;}
}
function upstreamConfig(env) {
  // Deliberately fail closed; supporting a self-hosted domain requires an explicit reviewed change.
  const url = new URL(env.SUPABASE_URL || 'https://unconfigured.invalid');
  if(env.OPENFUEL_ENV==='development'&&url.protocol==='http:'&&url.hostname==='127.0.0.1'&&url.port==='54321'&&!url.username&&!url.password&&url.pathname==='/'&&!url.search&&!url.hash&&(publicKey(env.SUPABASE_PUBLISHABLE_KEY)||localAnonKey(env.SUPABASE_PUBLISHABLE_KEY)))return url;
  if (url.protocol !== 'https:' || url.username || url.password || url.port ||
      !/^[a-z0-9]{20}\.supabase\.co$/.test(url.hostname) || url.pathname !== '/' || url.search || url.hash ||
      !publicKey(env.SUPABASE_PUBLISHABLE_KEY)) throw new Error('unconfigured');
  return url;
}
function boundedInteger(value, def, lo, hi) {
  if (value === null) return def;
  if (!/^\d+$/.test(value)) throw new Error('invalid');
  const n=Number(value); if (!Number.isSafeInteger(n)||n<lo||n>hi) throw new Error('invalid');return n;
}
const gradeSet=new Set(['regular','premium','midgrade','diesel']);
const paymentSet=new Set(['standard','cash','credit','membership']);
async function limitedJSON(response, max=2_000_000) {
  if(!response.body) throw new Error('empty');
  const reader=response.body.getReader();const chunks=[];let size=0;
  for(;;){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>max){await reader.cancel();throw new Error('too large');}chunks.push(value);}
  const bytes=new Uint8Array(size);let at=0;for(const chunk of chunks){bytes.set(chunk,at);at+=chunk.length;}
  return JSON.parse(new TextDecoder().decode(bytes));
}
function sanitizeStations(data) {
  if(!data || !Array.isArray(data.stations) || data.stations.length>200) throw new Error('invalid upstream');
  // Only the documented public fields survive a future upstream schema change.
  const keys=['id','name','brand','address','region','latitude_e6','longitude_e6','currency','volume_unit','is_demo','status','version','source_license'];
  const priceKeys=['event_seq','price_milli','fuel_type','payment_type','currency','volume_unit','observed_bucket','age_seconds','freshness','source','verification'];
  const stations=data.stations.map(s=>{
    if(typeof s.id!=='string'||typeof s.name!=='string'||s.currency!=='CAD'||s.volume_unit!=='L'||typeof s.is_demo!=='boolean')throw new Error('invalid station');
    const clean=Object.fromEntries(keys.filter(k=>Object.hasOwn(s,k)).map(k=>[k,s[k]]));
    if(s.price==null)clean.price=null;
    else {
      if(!Number.isSafeInteger(s.price.price_milli)||s.price.price_milli<=0)throw new Error('invalid price');
      clean.price=Object.fromEntries(priceKeys.filter(k=>Object.hasOwn(s.price,k)).map(k=>[k,s.price[k]]));
    }
    return clean;
  });
  return {region:String(data.region),stations,next_cursor:Number.isSafeInteger(data.next_cursor)?data.next_cursor:null};
}
export function createWorker(fetcher=fetch) {
  return {
    async fetch(request,env,ctx) {
      const url=new URL(request.url);
      if(!url.pathname.startsWith('/api/')) return env.ASSETS.fetch(request);
      if(request.method==='OPTIONS')return new Response(null,{status:204,headers:{...headers,'access-control-allow-methods':'GET, HEAD, OPTIONS','access-control-allow-headers':'Accept'}});
      if(!['GET','HEAD'].includes(request.method))return json({error:'read_only',message:'Public submissions are not enabled in this foundation.'},405,{'allow':'GET, HEAD, OPTIONS'});
      if(url.pathname==='/api/v1/health'){let ready=false;try{upstreamConfig(env);ready=true;}catch{}return json({ok:true,service:'openfuel-public-read-api',databaseConfigured:ready,writesEnabled:false});}
      let rpc,body;
      try {
        const names=[...url.searchParams.keys()];
        if(new Set(names).size!==names.length)throw new Error('duplicate query parameters');
        if(url.pathname==='/api/v1/stations') {
          if(names.some(n=>!['region','fuel','payment','limit','cursor'].includes(n)))throw new Error('unknown parameter');
          const region=url.searchParams.get('region')||'demo-region';
          const fuel=url.searchParams.get('fuel')||'regular',payment=url.searchParams.get('payment')||'standard';
          if(!/^[a-z0-9-]{1,64}$/.test(region)||!gradeSet.has(fuel)||!paymentSet.has(payment))throw new Error('invalid');
          rpc='openfuel_stations_v1';body={p_region:region,p_fuel:fuel,p_payment:payment,p_limit:boundedInteger(url.searchParams.get('limit'),100,1,200),p_offset:boundedInteger(url.searchParams.get('cursor'),0,0,10000)};
        } else if(url.pathname==='/api/v1/regions') {
          if(names.length)throw new Error('unknown parameter');rpc='openfuel_regions_v1';body={};
        } else return json({error:'not_found'},404);
      } catch {return json({error:'invalid_query'},400);}
      let base;try{base=upstreamConfig(env);}catch{return json({error:'not_configured',message:'Configure the Supabase project and public read key. Demo screens remain local.'},503);}
      try {
        const response=await fetcher(new URL(`/rest/v1/rpc/${rpc}`,base),{
          method:'POST',redirect:'error',headers:{'apikey':env.SUPABASE_PUBLISHABLE_KEY,'content-type':'application/json','accept':'application/json'},
          body:JSON.stringify(body),signal:AbortSignal.timeout(5000),
        });
        if(!response.ok)throw new Error('upstream');
        const data=await limitedJSON(response);
        let clean;
        if(rpc==='openfuel_stations_v1')clean=sanitizeStations(data);
        else {
          if(!Array.isArray(data?.regions)||data.regions.length>1000)throw new Error('invalid regions');
          clean={regions:data.regions.map(r=>({id:String(r.id),station_count:Number(r.station_count)}))};
        }
        return request.method==='HEAD'?new Response(null,{headers}):json(clean);
      } catch {
        // Never forward provider errors, credentials, response bodies, query SQL or stack traces.
        return json({error:'temporarily_unavailable'},502);
      }
    }
  };
}
export default createWorker();
