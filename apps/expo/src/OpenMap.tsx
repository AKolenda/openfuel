import React, { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import { Linking, StyleSheet } from 'react-native';
import { WebView } from 'react-native-webview';
import { type Coordinates, type Fuel, type Station, safeLogoUrl } from './domain';
import leaflet from './vendor/leaflet.json';

export type Region = Coordinates & { latitudeDelta: number; longitudeDelta: number };
export type OpenMapHandle = { animateToRegion: (region: Region, duration?: number) => void };
type Props = {
  initialRegion: Region;
  stations: Station[];
  fuel: Fuel;
  location: Coordinates | null;
  onSelect: (station: Station) => void;
  onRegionChangeComplete: (region: Region) => void;
  onPanDrag: () => void;
};

// Leaflet is bundled locally; tiles and brand images are requested from their providers.
// This works in Expo Go without relying on its shared Google Maps credentials.
const html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"><meta name="referrer" content="strict-origin-when-cross-origin"><style>${leaflet.css}
html,body,#map{height:100%;width:100%;margin:0;background:#f0f5ea} .price-pin{background:transparent;border:0}.price-pin span{display:inline-flex;align-items:center;justify-content:center;gap:5px;min-height:26px;min-width:26px;background:#245a43;border:2px solid white;border-radius:20px;color:white;padding:5px 10px;font:700 13px system-ui;box-shadow:0 1px 4px #0003}.price-pin.unknown span{background:white;color:#245a43;border-color:#245a43}.leaflet-container .leaflet-marker-pane .price-pin img{width:28px;height:26px;max-width:28px;max-height:26px;flex:0 0 28px;object-fit:contain;background:white;border-radius:4px}.price-pin .brand-fallback{font-size:11px}.leaflet-control-attribution{font-size:9px}.leaflet-control-zoom{margin-top:12px!important}
</style></head><body><div id="map" aria-label="Map of nearby fuel stations"></div><script>${leaflet.js.replaceAll('</script', '<\\/script')}
</script><script>
const send = value => window.ReactNativeWebView.postMessage(JSON.stringify(value));
const map = L.map('map',{zoomControl:true}).setView([55,-104],3);
map.attributionControl.setPrefix(false);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,updateWhenIdle:true,keepBuffer:0,attribution:'© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>'}).addTo(map);
const pins = L.layerGroup().addTo(map), markers=new Map(); let locationDot=null, changing=false, gesture=false;
map.on('dragstart',()=>{gesture=true;send({type:'gesture'})});
map.on('zoomstart',()=>{if(!changing){gesture=true;send({type:'gesture'})}});
map.on('moveend',()=>{const c=map.getCenter().wrap(),b=map.getBounds();send({type:'region',latitude:c.lat,longitude:c.lng,latitudeDelta:b.getNorth()-b.getSouth(),longitudeDelta:b.getEast()-b.getWest()});gesture=false;});
window.setArea = region => {changing=true;map.setView([region.latitude,region.longitude],region.latitudeDelta>1?3:12.5,{animate:false});changing=false;};
window.setStations = data => {
 const keep=new Set(data.stations.map(s=>s.id));
 for(const [id,item] of markers){if(!keep.has(id)){pins.removeLayer(item.marker);markers.delete(id);}}
 data.stations.forEach(s=>{
   const price=s.prices[data.fuel],logo=s.brandLogoUrl,label=price==null?'':(price/10).toFixed(1);
   const key=JSON.stringify([label,logo,s.name,s.brand]);let item=markers.get(s.id);
   if(item&&item.key===key){item.marker.setLatLng([s.latitude,s.longitude]);return;}
   if(item)pins.removeLayer(item.marker);
   const chip=document.createElement('span'),initials=document.createElement('b');initials.className='brand-fallback';
   initials.textContent=(s.brand||s.name).replace(/[^a-z0-9 ]/gi,'').split(/\\s+/).map(w=>w[0]).join('').slice(0,2).toUpperCase()||'F';chip.append(initials);
   if(logo){const img=document.createElement('img');img.alt='';img.width=28;img.height=26;img.style.display='none';img.referrerPolicy='no-referrer';img.onload=()=>{img.style.display='block';initials.hidden=true};img.onerror=()=>img.remove();img.src=logo;chip.prepend(img);}
   if(label)chip.append(document.createTextNode(label));
   const width=price==null?48:94;
   const marker=L.marker([s.latitude,s.longitude],{icon:L.divIcon({className:'price-pin'+(price==null?' unknown':''),html:chip,iconSize:[width,40],iconAnchor:[width/2,20]}),title:s.name}).addTo(pins);
   const element=marker.getElement();if(element){element.setAttribute('aria-label',s.name+', '+(price==null?'no price reported':label+' cents per litre'));}
   marker.on('click',()=>send({type:'station',id:s.id}));markers.set(s.id,{marker,key});
 });
 if(locationDot){locationDot.remove();locationDot=null;}
 if(data.location)locationDot=L.circleMarker([data.location.latitude,data.location.longitude],{radius:7,color:'white',weight:3,fillColor:'#267bce',fillOpacity:1}).addTo(map);
};
new ResizeObserver(()=>map.invalidateSize()).observe(document.getElementById('map'));
send({type:'ready'});
</script></body></html>`;

export const OpenMap = forwardRef<OpenMapHandle, Props>(function OpenMap(props, ref) {
  const web = useRef<WebView>(null);
  const ready = useRef(false);
  const pendingArea = useRef(props.initialRegion);
  const latest = useRef(props);
  latest.current = props;
  const inject = (name: 'setArea' | 'setStations', value: unknown) => {
    if (ready.current) web.current?.injectJavaScript(`window.${name}(${JSON.stringify(value)});true;`);
  };
  const updateStations = () => inject('setStations', { stations: latest.current.stations.map(station => ({ ...station, brandLogoUrl: safeLogoUrl(station.brandLogoUrl) })), fuel: latest.current.fuel, location: latest.current.location });
  useImperativeHandle(ref, () => ({ animateToRegion(region) { pendingArea.current = region; inject('setArea', region); } }), []);
  useEffect(updateStations, [props.stations, props.fuel, props.location]);
  return <WebView ref={web} style={StyleSheet.absoluteFill} source={{ html, baseUrl: 'https://openfuel.ca/' }}
    originWhitelist={['*']} javaScriptEnabled cacheEnabled domStorageEnabled={false}
    applicationNameForUserAgent="OpenFuelExpo/0.4 (+https://openfuel.ca)"
    onShouldStartLoadWithRequest={request => {
      if (request.url === 'about:blank' || request.url === 'https://openfuel.ca/') return true;
      if (request.url === 'https://www.openstreetmap.org/copyright') void Linking.openURL(request.url).catch(() => {});
      return false;
    }}
    onMessage={event => {
      try {
        const value = JSON.parse(event.nativeEvent.data);
        if (value.type === 'ready') { ready.current = true; inject('setArea', pendingArea.current); updateStations(); }
        else if (value.type === 'station') { const station = latest.current.stations.find(s => s.id === value.id); if (station) latest.current.onSelect(station); }
        else if (value.type === 'gesture') latest.current.onPanDrag();
        else if (value.type === 'region' && Number.isFinite(value.latitude) && Math.abs(value.latitude) <= 90 &&
          Number.isFinite(value.longitude) && Math.abs(value.longitude) <= 180 &&
          Number.isFinite(value.latitudeDelta) && value.latitudeDelta > 0 &&
          Number.isFinite(value.longitudeDelta) && value.longitudeDelta > 0) latest.current.onRegionChangeComplete(value);
      } catch { /* Ignore malformed messages; the native app owns station data. */ }
    }} />;
});
