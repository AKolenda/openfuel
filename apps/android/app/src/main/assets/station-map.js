/* SPDX-License-Identifier: AGPL-3.0-only
 * The Android app's map page: the base map, the stations and the location dot are drawn by MapLibre in one
 * WebGL frame, so a pinch or pan moves no page elements. mapStyle comes from map-style.js; OpenFuelMap is the
 * app's JavaScript bridge. Without WebGL the Leaflet page in station-map-leaflet.js runs instead. */
// The Leaflet page and base-map.js declare some of the same names, so this page's code stays in a block.
{
 const OPENFREEMAP_TILES='https://tiles.openfreemap.org/planet';
 const empty={type:'FeatureCollection',features:[]};
 let map=null;
 // The app passes its search area in the fragment, so the first tiles requested are that area's.
 const initialArea=/^#(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)$/.exec(location.hash);
 try{
  // MapLibre's zoom z shows what Leaflet's zoom z+1 shows: its tiles are 512 px. At most 2 pixels per CSS pixel
  // leaves 42% fewer pixels to draw in every frame on a 2.625 screen, for slightly softer text.
  map=new maplibregl.Map({container:'map',center:initialArea?[+initialArea[2],+initialArea[1]]:[-106.35,56.13],zoom:initialArea?11.5:2,maxZoom:18,
   pixelRatio:Math.min(devicePixelRatio||1,2),attributionControl:false,dragRotate:false,pitchWithRotate:false,touchPitch:false,
   style:{version:8,sources:{location:{type:'geojson',data:empty}},layers:[
    {id:'location',type:'circle',source:'location',paint:{'circle-radius':6.5,'circle-color':'#267bce','circle-stroke-width':3,'circle-stroke-color':'white'}}]}});
  map.touchZoomRotate.disableRotation();
 }catch{map=null;}
 if(!map){
  // MapLibre throws without WebGL; the Leaflet page starts on a clean map element.
  const element=document.getElementById('map');element.replaceChildren();element.className='';
  document.head.append(...['leaflet.css','leaflet.js','base-map.js','station-map-leaflet.js'].map(file=>{
   const tag=document.createElement(file.endsWith('.css')?'link':'script');
   if(tag.tagName==='LINK'){tag.rel='stylesheet';tag.href=file;}else{tag.src=file;tag.async=false;}
   return tag;
  }));
 }else{
  // Exposed for the instrumentation tests, as the Leaflet page's map is.
  window.map=map;
  let changingArea=false;
  map.on('moveend',()=>{if(!changingArea){const p=map.getCenter().wrap();OpenFuelMap.moved(p.lat,p.lng);}});
  window.setArea=(lat,lon,overview)=>{changingArea=true;map.jumpTo({center:[lon,lat],zoom:overview?2:11.5});changingArea=false;};
  // The base map goes under the location dot once OpenFreeMap's tile index has loaded. A refused index (an HTTP
  // error or an unreadable index) switches to OpenStreetMap's raster tiles for the visit; a lost connection leaves
  // the map as it is and retries on the next move.
  let pending=false,chosen=false;
  function useBase(style,name){
   if(style.glyphs)map.setGlyphs(style.glyphs);
   if(style.sprite)map.setSprite(style.sprite);
   for(const [id,source] of Object.entries(style.sources))map.addSource(id,source);
   for(const layer of style.layers)map.addLayer(layer,'location');
   chosen=true;map.off('moveend',startBase);OpenFuelMap.base(name);
  }
  function raster(){useBase({sources:{openstreetmap:{type:'raster',tiles:['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],tileSize:256,maxzoom:19}},
   layers:[{id:'openstreetmap',type:'raster',source:'openstreetmap'}]},'openstreetmap');}
  function vector(index){
   try{useBase({...mapStyle,sources:{...mapStyle.sources,openmaptiles:{type:'vector',tiles:index.tiles,minzoom:index.minzoom??0,maxzoom:index.maxzoom??14}}},'openfreemap');}
   catch{for(const layer of mapStyle.layers)if(map.getLayer(layer.id))map.removeLayer(layer.id);if(map.getSource('openmaptiles'))map.removeSource('openmaptiles');raster();}
  }
  function startBase(){
   if(pending||chosen)return;
   pending=true;
   fetch(OPENFREEMAP_TILES).then(response=>response.ok?response.json().catch(()=>null):null)
    .then(index=>index?vector(index):raster(),()=>{}).finally(()=>{pending=false;});
  }

  // Stations. Each distinct chip (price, logo or initial, highlight) is drawn once, at the map's pixel density,
  // into a cell of one texture, with the brand centre (the geographic anchor) at the same place in every cell.
  // Each frame then draws every chip on screen as one batch of quads: no page elements move and MapLibre's
  // symbol tiles, which would copy the chip images again for every tile and zoom level, are not used.
  const cell=[72,74],anchor=[36,50],ratio=map.getPixelRatio(),logos=new Map();
  const cw=Math.ceil(cell[0]*ratio),ch=Math.ceil(cell[1]*ratio),ax=Math.round(anchor[0]*ratio),ay=Math.round(anchor[1]*ratio),columns=Math.floor(4096/cw);
  // The chips are drawn in memory, so a lost WebGL context cannot take them with it.
  const atlas={canvas:document.createElement('canvas'),slots:new Map(),free:[],version:0};
  atlas.canvas.width=columns*cw;atlas.canvas.height=0;
  let placed=[],gpu=null;
  function logoImage(uri){
   if(!logos.has(uri)){const image=new Image();image.src=uri;logos.set(uri,image.decode().then(()=>image,()=>null));}
   return logos.get(uri);
  }
  function drawChip(g,slot,s,logo){
   const known=s.price!=null,w=known?64:42,h=known?66:40,border=s.best?2:1,x=(slot%columns)*cw,y=Math.floor(slot/columns)*ch;
   g.setTransform(1,0,0,1,0,0);g.clearRect(x,y,cw,ch);
   g.setTransform(ratio,0,0,ratio,x+(anchor[0]-w/2)*ratio,y+(anchor[1]-h+20)*ratio);
   g.beginPath();g.roundRect(border/2,border/2,w-border,h-border,13-border/2);
   g.save();g.shadowColor='#0003';g.shadowOffsetY=ratio;g.shadowBlur=3*ratio;g.fillStyle='white';g.fill();g.restore();
   g.lineWidth=border;g.strokeStyle='#285b43';g.stroke();
   g.fillStyle='#285b43';g.textAlign='center';g.textBaseline='middle';
   if(known){g.font='700 15px system-ui';g.fillText((s.price/10).toFixed(1),w/2,17);}
   // The brand centre is 20 px above the chip's foot; adding a price grows the chip upward.
   if(logo){const scale=Math.min(29/logo.naturalWidth,29/logo.naturalHeight),lw=logo.naturalWidth*scale,lh=logo.naturalHeight*scale;g.drawImage(logo,w/2-lw/2,h-20-lh/2,lw,lh);}
   else{g.font='700 18px system-ui';g.fillText(s.name.slice(0,1).toUpperCase()||'F',w/2,h-20);}
  }
  /** Gives every chip in use a cell, drawing only the new ones and reusing cells of chips no longer shown. */
  function updateAtlas(stations,images){
   const used=new Set(stations.map(item=>item.chip));
   for(const [chip,slot] of atlas.slots)if(!used.has(chip)){atlas.slots.delete(chip);atlas.free.push(slot);}
   const added=[...new Map(stations.filter(item=>!atlas.slots.has(item.chip)).map(item=>[item.chip,item])).values()];
   if(!added.length)return;
   const rows=Math.ceil((atlas.slots.size+added.length)/columns);
   if(rows*ch>atlas.canvas.height){
    const grown=Object.assign(document.createElement('canvas'),{width:atlas.canvas.width,height:rows*ch});
    if(atlas.canvas.height)grown.getContext('2d',{willReadFrequently:true}).drawImage(atlas.canvas,0,0);
    for(let slot=atlas.canvas.height/ch*columns;slot<rows*columns;slot++)atlas.free.push(slot);
    atlas.canvas=grown;
   }
   atlas.free.sort((a,b)=>b-a);
   const g=atlas.canvas.getContext('2d',{willReadFrequently:true});
   for(const item of added){const slot=atlas.free.pop();atlas.slots.set(item.chip,slot);drawChip(g,slot,item.s,images.get(item.logo));}
   atlas.version++;
  }
  function setUp(gl){
   const program=gl.createProgram();
   for(const [type,source] of [[gl.VERTEX_SHADER,'attribute vec2 a_position,a_coordinate;uniform vec2 u_scale;varying vec2 v_coordinate;void main(){gl_Position=vec4(a_position*u_scale+vec2(-1,1),0,1);v_coordinate=a_coordinate;}'],
    [gl.FRAGMENT_SHADER,'precision mediump float;uniform sampler2D u_image;varying vec2 v_coordinate;void main(){gl_FragColor=texture2D(u_image,v_coordinate);}']]){
    const shader=gl.createShader(type);gl.shaderSource(shader,source);gl.compileShader(shader);gl.attachShader(program,shader);
   }
   gl.linkProgram(program);
   const texture=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,texture);
   // Chips are drawn on whole pixels at the canvas's own density, so every texel lands on one pixel.
   for(const [name,value] of [[gl.TEXTURE_MIN_FILTER,gl.NEAREST],[gl.TEXTURE_MAG_FILTER,gl.NEAREST],[gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE],[gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE]])
    gl.texParameteri(gl.TEXTURE_2D,name,value);
   return {program,texture,version:-1,buffer:gl.createBuffer(),data:new Float32Array(0),position:gl.getAttribLocation(program,'a_position'),
    coordinate:gl.getAttribLocation(program,'a_coordinate'),scale:gl.getUniformLocation(program,'u_scale'),image:gl.getUniformLocation(program,'u_image')};
  }
  const stationLayer={id:'stations',type:'custom',renderingMode:'2d',render(gl){
   if(!placed.length||!atlas.canvas.height)return;
   if(!gpu)gpu=setUp(gl);
   gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,gpu.texture);
   if(gpu.version!==atlas.version){
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL,true);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,atlas.canvas);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL,false);gpu.version=atlas.version;
   }
   const width=gl.drawingBufferWidth,height=gl.drawingBufferHeight,scale=width/map.getContainer().clientWidth,tw=atlas.canvas.width,th=atlas.canvas.height;
   if(gpu.data.length<placed.length*24)gpu.data=new Float32Array(placed.length*24);
   const data=gpu.data;let i=0;
   const vertex=(x,y,u,v)=>{data[i++]=x;data[i++]=y;data[i++]=u;data[i++]=v;};
   for(const {at,slot} of placed){
    const p=map.project(at),x0=Math.round(p.x*scale)-ax,y0=Math.round(p.y*scale)-ay,x1=x0+cw,y1=y0+ch;
    if(x0>width||y0>height||x1<0||y1<0)continue;
    const u0=(slot%columns)*cw/tw,v0=Math.floor(slot/columns)*ch/th,u1=u0+cw/tw,v1=v0+ch/th;
    vertex(x0,y0,u0,v0);vertex(x1,y0,u1,v0);vertex(x0,y1,u0,v1);vertex(x0,y1,u0,v1);vertex(x1,y0,u1,v0);vertex(x1,y1,u1,v1);
   }
   const n=i/24;
   gl.useProgram(gpu.program);gl.uniform2f(gpu.scale,2/width,-2/height);gl.uniform1i(gpu.image,0);
   gl.bindBuffer(gl.ARRAY_BUFFER,gpu.buffer);gl.bufferData(gl.ARRAY_BUFFER,data.subarray(0,i),gl.DYNAMIC_DRAW);
   gl.enableVertexAttribArray(gpu.position);gl.vertexAttribPointer(gpu.position,2,gl.FLOAT,false,16,0);
   gl.enableVertexAttribArray(gpu.coordinate);gl.vertexAttribPointer(gpu.coordinate,2,gl.FLOAT,false,16,8);
   gl.enable(gl.BLEND);gl.blendFunc(gl.ONE,gl.ONE_MINUS_SRC_ALPHA);
   gl.drawArrays(gl.TRIANGLES,0,n*6);
   gl.disableVertexAttribArray(gpu.position);gl.disableVertexAttribArray(gpu.coordinate);
  }};
  // MapLibre restores its own layers after a lost WebGL context, but not this one.
  map.on('webglcontextlost',()=>{gpu=null;});
  map.on('webglcontextrestored',()=>map.once('style.load',()=>{if(!map.getLayer('stations'))map.addLayer(stationLayer);}));
  // A tap selects the chip in front, the one drawn last.
  map.on('click',event=>{
   for(let i=placed.length-1;i>=0;i--){
    const {s,at,w,h}=placed[i],p=map.project(at);
    if(Math.abs(event.point.x-p.x)<=w/2&&event.point.y>=p.y-h+20&&event.point.y<=p.y+20)return OpenFuelMap.selected(s.id);
   }
  });
  // Invisible buttons over the chips give TalkBack each station's label and action. They move once the map
  // settles, never during a gesture, and let touches through to the map.
  const labels=document.createElement('div');labels.className='station-labels';document.body.append(labels);
  let generation=0;
  function placeLabels(){for(const {at,w,h,button} of placed){const p=map.project(at);button.style.transform=`translate(${Math.round(p.x-w/2)}px,${Math.round(p.y-h+20)}px)`;}}
  map.on('moveend',placeLabels);
  window.setStations=async data=>{
   const run=++generation;
   const stations=data.stations.map(s=>{const logo=data.logos[s.logo]||null;return {s,logo,chip:JSON.stringify([s.price,logo?s.logo:s.name.slice(0,1).toUpperCase(),s.best])};});
   const images=new Map(await Promise.all([...new Set(stations.map(item=>item.logo).filter(Boolean))].map(async uri=>[uri,await logoImage(uri)])));
   if(run!==generation)return;
   updateAtlas(stations,images);
   // Drawn from north to south, so the chip lower on the screen is in front, as on the Leaflet page.
   placed=stations.map(({s,chip})=>{
    const known=s.price!=null,w=known?64:42,h=known?66:40,button=document.createElement('button');
    button.setAttribute('aria-label',s.name+', '+(known?(s.price/10).toFixed(1)+' cents per litre':'no price reported'));
    button.style.width=w+'px';button.style.height=h+'px';button.onclick=()=>OpenFuelMap.selected(s.id);
    return {s,at:[s.lon,s.lat],slot:atlas.slots.get(chip),w,h,button};
   }).sort((a,b)=>b.s.lat-a.s.lat);
   labels.replaceChildren(...placed.map(item=>item.button));placeLabels();
   map.triggerRepaint();
  };
  window.setLocation=location=>{map.getSource('location').setData(location?{type:'Point',coordinates:[location.lon,location.lat]}:empty);};
  map.once('load',()=>{map.addLayer(stationLayer);map.on('moveend',startBase);startBase();OpenFuelMap.ready();});
 }
}
