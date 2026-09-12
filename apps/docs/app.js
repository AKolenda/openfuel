/* SPDX-License-Identifier: AGPL-3.0-only */
(() => {
'use strict';
const PAGES=window.OPENFUEL_PAGES;
const EVIDENCE=window.OPENFUEL_EVIDENCE || {results:{},logs:{}};
const $=id=>document.getElementById(id);
let active='start';
const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function navigation(target,query=''){
 const q=query.trim().toLowerCase(),groups=[...new Set(PAGES.map(p=>p.group))];
 const matches=PAGES.filter(p=>!q||(`${p.title} ${p.description} ${p.body.replace(/<[^>]*>/g,' ')}`).toLowerCase().includes(q));
 $(target).innerHTML=matches.length?groups.map(g=>{const pages=matches.filter(p=>p.group===g);return pages.length?`<section class="nav-group"><h2>${esc(g)}</h2><ul>${pages.map(p=>`<li><a href="#${p.id}" ${p.id===active?'aria-current="page"':''}>${esc(p.title)}</a></li>`).join('')}</ul></section>`:''}).join(''):'<p class="no-guides">No matching guides. Try “iOS”, “maps”, or “testing”.</p>';
}
function guide(id){return PAGES.find(p=>p.id===id)||PAGES[0];}
function evidenceTable(){
 const target=$('evidence-table');if(!target)return;
 const rows=Object.entries(EVIDENCE.results);
 target.innerHTML=rows.length?`<table><thead><tr><th>Check</th><th>Recorded result</th><th>Evidence</th></tr></thead><tbody>${rows.map(([key,r])=>`<tr><td>${esc(key)}${r.scope?`<br><small>${esc(r.scope)}</small>`:''}</td><td>${esc(r.status)} on ${esc(r.runtime)}${r.checked_at?`<br><small>${esc(r.checked_at.slice(0,10))}</small>`:''}</td><td><button class="text-link" data-log="${esc(key)}">Open output</button></td></tr>`).join('')}</tbody></table><p>Each result records its date and scope. Earlier architecture checks remain as history. A passing core test alone does not establish a native app build.</p>`:'<p>No executed evidence is included in this build yet. Run the root check commands and rebuild the site.</p>';
}
function render(){
 const slug=location.hash.slice(1).split('/')[0]||'start',p=guide(slug);active=p.id;
 document.title=p.title+' · OpenFuel documentation';$('page-title').textContent=p.title;$('page-description').textContent=p.description;$('crumb').textContent=p.title;$('article').innerHTML=p.body;
 navigation('nav',$('search').value);navigation('mobile-nav',$('mobile-search').value);
 const hs=[...$('article').querySelectorAll('h2[id]')];$('toc').innerHTML=hs.map(h=>`<a href="#${p.id}/${h.id}" data-section="${h.id}">${esc(h.textContent)}</a>`).join('');
 const i=PAGES.indexOf(p);$('pager').innerHTML=(i?`<a href="#${PAGES[i-1].id}"><small>Previous</small>${esc(PAGES[i-1].title)}</a>`:'')+(i<PAGES.length-1?`<a class="next" href="#${PAGES[i+1].id}"><small>Next</small>${esc(PAGES[i+1].title)}</a>`:'');
 evidenceTable();
 if($('guides-dialog').open)$('guides-dialog').close();
 const section=location.hash.slice(1).split('/')[1];if(section)requestAnimationFrame(()=>document.getElementById(section)?.scrollIntoView());else window.scrollTo(0,0);
}
function openDialog(id){
 document.querySelectorAll('dialog[open]').forEach(d=>d.close());const d=$(id);d.showModal();d.querySelector('h2').tabIndex=-1;d.querySelector('h2').focus({preventScroll:true});
 if(innerWidth<=760&&!matchMedia('(prefers-reduced-motion:reduce)').matches)d.animate([{transform:'translateY(100%)'},{transform:'translateY(0)'}],{duration:250,easing:'cubic-bezier(.2,.75,.2,1)'});
}
async function copyCode(button){
 const code=button.closest('.code').querySelector('code').textContent;
 try{
  if(navigator.clipboard?.writeText)await navigator.clipboard.writeText(code);
  else{const t=document.createElement('textarea');t.value=code;t.style.position='fixed';t.style.top='-1000px';document.body.appendChild(t);t.select();const ok=document.execCommand('copy');t.remove();if(!ok)throw Error('Clipboard blocked');}
  button.textContent='Copied';$('status').textContent='Code copied.';setTimeout(()=>button.textContent='Copy',1600);
 }catch{
  button.textContent='Select code';$('status').textContent='Clipboard unavailable. Select and copy the code.';
  const range=document.createRange();range.selectNodeContents(button.closest('.code').querySelector('code'));getSelection().removeAllRanges();getSelection().addRange(range);
 }
}
document.addEventListener('click',e=>{
 if(e.target.closest('#mobile-nav a')&&$('guides-dialog').open)$('guides-dialog').close();
 const el=e.target.closest('button,a[data-section]');if(!el)return;
 if(el.hasAttribute('data-close'))return el.closest('dialog').close();
 if(el.hasAttribute('data-open-reference')){const f=$('reference-frame');if(!f.getAttribute('src'))f.src='../preview/';openDialog('reference-dialog');return;}
 if(el.dataset.page){location.hash=el.dataset.page;return;}
 if(el.dataset.log){$('log-title').textContent=el.dataset.log+' · executed output';$('log-output').textContent=EVIDENCE.logs[el.dataset.log]||'No log included for this check.';openDialog('log-dialog');return;}
 if(el.classList.contains('copy'))copyCode(el);
});
$('open-guides').onclick=()=>openDialog('guides-dialog');$('search').oninput=e=>navigation('nav',e.target.value);$('mobile-search').oninput=e=>navigation('mobile-nav',e.target.value);
window.addEventListener('hashchange',render);
document.addEventListener('keydown',e=>{
 if(e.key==='/'&&!['INPUT','TEXTAREA'].includes(document.activeElement.tagName)){e.preventDefault();if(innerWidth<=760){openDialog('guides-dialog');$('mobile-search').focus();}else $('search').focus();}
 if(e.key==='Escape'&&document.activeElement.tagName==='INPUT'){document.activeElement.value='';navigation('nav');navigation('mobile-nav');document.activeElement.blur();}
});
document.querySelectorAll('dialog').forEach(d=>d.addEventListener('click',e=>{if(e.target===d){const r=d.getBoundingClientRect();if(e.clientY<r.top||e.clientY>r.bottom||e.clientX<r.left||e.clientX>r.right)d.close();}}));
render();
})();
