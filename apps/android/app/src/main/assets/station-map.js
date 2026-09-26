/* SPDX-License-Identifier: AGPL-3.0-only
 * The Android app's map page: station markers and the location dot over the shared base map.
 * mapStyle comes from map-style.js; OpenFuelMap is the app's JavaScript bridge. */
// The app passes its search area in the fragment, so the first tiles requested are that area's.
const initialArea=/^#(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)$/.exec(location.hash);
const map=L.map('map',{zoomControl:false,attributionControl:false,zoomSnap:0.5}).setView(initialArea?[+initialArea[1],+initialArea[2]]:[56.13,-106.35],initialArea?12.5:3);
// Attribution is drawn natively at the screen edge.
const tileOptions={maxZoom:19,updateWhenIdle:true,keepBuffer:1};
baseMapLayer(map,mapStyle,tileOptions,(layer,name)=>OpenFuelMap.base(name));
const markers=new Map();let locationDot=null,changingArea=false;
map.on('moveend',()=>{if(!changingArea){const p=map.getCenter().wrap();OpenFuelMap.moved(p.lat,p.lng);}});
window.setArea=(lat,lon,overview)=>{changingArea=true;map.setView([lat,lon],overview?3:12.5,{animate:false});changingArea=false;};
function stationIcon(s,logo){
   const known=s.price!=null;
   const chip=document.createElement('div');chip.className='station-chip'+(s.best?' best':'');
   if(known){const price=document.createElement('div');price.className='station-price';price.textContent=(s.price/10).toFixed(1);chip.append(price);}
   const brand=document.createElement('div');brand.className='station-brand';brand.textContent=s.name.slice(0,1).toUpperCase()||'F';
   if(logo){const img=document.createElement('img');img.alt='';img.src=logo;brand.replaceChildren(img);}
   chip.append(brand);
   const w=known?64:42,h=known?66:40;
   // The brand centre is always the geographic anchor; adding a price grows upward.
   return L.divIcon({className:'station-pin',html:chip,iconSize:[w,h],iconAnchor:[w/2,h-20]});
}
window.setStations=data=>{
 const keep=new Set(data.stations.map(s=>s.id));
 for(const [id,item] of markers){if(!keep.has(id)){item.marker.remove();markers.delete(id);}}
 for(const s of data.stations){
  const logo=data.logos[s.logo],known=s.price!=null,key=JSON.stringify([s.name,s.price,logo,s.best]);
  let item=markers.get(s.id);
  if(!item){const marker=L.marker([s.lat,s.lon],{title:s.name,keyboard:true}).on('click',()=>OpenFuelMap.selected(s.id));item={marker,key:null};markers.set(s.id,item);}
  item.marker.setLatLng([s.lat,s.lon]);
  if(item.key!==key){
   item.marker.setIcon(stationIcon(s,logo));
   item.marker.addTo(map);item.marker.getElement().setAttribute('aria-label',s.name+', '+(known?(s.price/10).toFixed(1)+' cents per litre':'no price reported'));item.key=key;
  }
 }
};
window.setLocation=location=>{
 if(location){const p=[location.lat,location.lon];if(locationDot)locationDot.setLatLng(p);else locationDot=L.circleMarker(p,{radius:8,color:'white',weight:3,fillColor:'#267bce',fillOpacity:1}).addTo(map);}
 else if(locationDot){locationDot.remove();locationDot=null;}
};
new ResizeObserver(()=>map.invalidateSize({pan:false})).observe(document.getElementById('map'));
OpenFuelMap.ready();
