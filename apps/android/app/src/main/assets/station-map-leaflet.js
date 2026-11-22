/* SPDX-License-Identifier: AGPL-3.0-only
 * The Android app's map page without WebGL, loaded by station-map.js: station markers and the location dot
 * over the shared base map's raster tiles. mapStyle comes from map-style.js; OpenFuelMap is the app's JavaScript bridge. */
// The app passes its search area in the fragment, so the first tiles requested are that area's.
const initialArea=/^#(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)$/.exec(location.hash);
const map=L.map('map',{zoomControl:false,attributionControl:false,zoomSnap:0.5}).setView(initialArea?[+initialArea[1],+initialArea[2]]:[56.13,-106.35],initialArea?12.5:3);
// Attribution is drawn natively at the screen edge.
const tileOptions={maxZoom:19,updateWhenIdle:true,keepBuffer:1};
baseMapLayer(map,mapStyle,tileOptions,(layer,name)=>OpenFuelMap.base(name));
const markers=new Map();let locationDot=null,changingArea=false;
map.on('moveend',()=>{if(!changingArea){const p=map.getCenter().wrap();OpenFuelMap.moved(p.lat,p.lng);}});
window.setArea=(lat,lon,overview)=>{changingArea=true;map.setView([lat,lon],overview?3:12.5,{animate:false});changingArea=false;};
// A numbered pin for the first stations in the app's list, the first few amber, and a dot for the rest: ink with a
// price, grey without. Each is centred on its station, inside a larger touch box.
function stationIcon(s){
   const pin=document.createElement('div');
   if(s.rank){pin.className='station-rank'+(s.highlight?' highlight':'');pin.textContent=s.rank;}
   else pin.className='station-dot'+(s.price!=null?' priced':'');
   const size=s.rank?36:28;
   return L.divIcon({className:'station-pin',html:pin,iconSize:[size,size],iconAnchor:[size/2,size/2]});
}
window.setStations=data=>{
 const keep=new Set(data.stations.map(s=>s.id));
 for(const [id,item] of markers){if(!keep.has(id)){item.marker.remove();markers.delete(id);}}
 for(const s of data.stations){
  const known=s.price!=null,key=JSON.stringify([s.name,s.price,s.rank,s.highlight]);
  let item=markers.get(s.id);
  if(!item){const marker=L.marker([s.lat,s.lon],{title:s.name,keyboard:true}).on('click',()=>OpenFuelMap.selected(s.id));item={marker,key:null};markers.set(s.id,item);}
  item.marker.setLatLng([s.lat,s.lon]);
  if(item.key!==key){
   // The numbered pins sit over the dots, with 1 in front.
   item.marker.setIcon(stationIcon(s));item.marker.setZIndexOffset(s.rank?(10-s.rank)*1000:0);
   item.marker.addTo(map);item.marker.getElement().setAttribute('aria-label',(s.rank?s.rank+'. ':'')+s.name+', '+(known?(s.price/10).toFixed(1)+' cents per litre':'no price reported'));item.key=key;
  }
 }
};
window.setLocation=location=>{
 if(location){const p=[location.lat,location.lon];if(locationDot)locationDot.setLatLng(p);else locationDot=L.circleMarker(p,{radius:8,color:'white',weight:3,fillColor:'#267bce',fillOpacity:1}).addTo(map);}
 else if(locationDot){locationDot.remove();locationDot=null;}
};
new ResizeObserver(()=>map.invalidateSize({pan:false})).observe(document.getElementById('map'));
OpenFuelMap.ready();
