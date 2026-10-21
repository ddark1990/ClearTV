package com.goomero.cleartv;

/**
 * The phone page's theme section ({@code /<token>/theme}): paste or pick a theme file, check it, preview it on the TV
 * for 30 seconds, keep or revert, try the built-in themes, export the current theme and reset. Talks only to the
 * theme API on the same server; everything from the TV is shown with textContent, never as markup.
 */
final class ThemePage {
    private ThemePage() {}

    static final String HTML = "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>ClearTV themes</title>"
            + "<style>body{margin:0;font:16px system-ui,sans-serif;background:#0d0f12;color:#e8ecf0}"
            + "main{max-width:520px;margin:0 auto;padding:16px}h1{font-size:13px;letter-spacing:.2em;color:#8a96a3;margin:4px 0 16px}"
            + "h2{font-size:12px;letter-spacing:.16em;color:#8a96a3;margin:22px 0 8px}"
            + "button,.b{font:inherit;color:inherit;background:#1c2026;border:1px solid #2c323a;border-radius:12px;padding:12px 14px;"
            + "text-decoration:none;display:inline-block;margin:4px 4px 0 0}button:active{background:#2f6fed}"
            + "textarea{width:100%;box-sizing:border-box;min-height:180px;font:13px ui-monospace,monospace;color:inherit;"
            + "background:#15181d;border:1px solid #2c323a;border-radius:12px;padding:12px}input[type=file]{margin:8px 0;color:#8a96a3}"
            + "#s{margin-top:12px;min-height:18px;color:#c9d2db}#e{color:#ff9a8a;font-size:14px;padding-left:18px}"
            + "a{color:#8a96a3}</style></head><body><main><h1>CLEARTV THEMES</h1>"
            + "<h2>YOUR THEME</h2><textarea id=t placeholder='Paste theme JSON here (see docs/THEMES.md)' autocomplete=off "
            + "autocapitalize=off spellcheck=false></textarea><input type=file id=f accept='.json,application/json,text/plain'>"
            + "<div><button id=check>Check</button><button id=prev>Preview on TV (30 s)</button><button id=keep>Keep</button>"
            + "<button id=rev>Revert now</button></div><div id=s></div><ul id=e></ul>"
            + "<h2>BUILT-IN THEMES</h2><div id=list></div>"
            + "<h2>MORE</h2><a class=b href='api/export' download='cleartv-theme.json'>Export current theme</a>"
            + "<button id=load>Load current into editor</button><button id=reset>Reset to Stargazer</button>"
            + "<p><a href='./'>&larr; Remote</a></p></main><script>"
            + "const $=i=>document.getElementById(i),S=$('s'),E=$('e');"
            + "function show(r){E.textContent='';if(r.ok){return}S.textContent=r.error?r.error.message:'Failed';"
            + "(r.errors||[]).forEach(p=>{const li=document.createElement('li');li.textContent=(p.path?p.path+': ':'')+p.message;E.appendChild(li)})}"
            + "function call(p,b){return fetch('api/'+p,b===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},"
            + "body:typeof b==='string'?b:JSON.stringify(b)}).then(r=>r.json()).then(r=>{show(r);return r})"
            + ".catch(()=>{S.textContent='The TV page is off. Scan the QR code on the TV again.';return {ok:false}})}"
            + "function themeObj(){try{return JSON.parse($('t').value)}catch(x){S.textContent='Not valid JSON: '+x.message;E.textContent='';return null}}"
            + "$('f').onchange=e=>{const f=e.target.files[0];if(!f)return;if(f.size>65536){S.textContent='64 KB is the limit for a theme file.';return}"
            + "f.text().then(t=>{$('t').value=t;S.textContent='Loaded '+f.name+'. Check or preview it.'})};"
            + "$('check').onclick=()=>call('validate',$('t').value).then(r=>{if(r.ok)S.textContent='Looks good: '+r.id+' is a valid theme.'});"
            + "$('prev').onclick=()=>{const o=themeObj();if(o)call('apply',{theme:o,revertAfter:30}).then(r=>{if(r.ok)"
            + "S.textContent='Showing on the TV for 30 s. Keep it, or it goes back by itself.'})};"
            + "$('keep').onclick=()=>call('confirm',{}).then(r=>{if(r.ok)S.textContent='Kept: '+r.active+'. It is saved in Settings > Theme.'});"
            + "$('rev').onclick=()=>call('revert',{}).then(r=>{if(r.ok)S.textContent='Back to '+r.active+'.'});"
            + "$('reset').onclick=()=>call('reset',{}).then(r=>{if(r.ok)S.textContent='Back to Stargazer.'});"
            + "$('load').onclick=()=>call('theme').then(r=>{if(r.ok){$('t').value=JSON.stringify(r.theme,null,2);S.textContent='Current theme loaded.'}});"
            + "call('themes').then(r=>{if(!r.ok)return;r.builtIn.concat(r.custom).forEach(t=>{const b=document.createElement('button');"
            + "b.textContent=t.name+(t.id===r.active?' (on)':'');b.onclick=()=>call('apply',{id:t.id,revertAfter:30}).then(x=>{if(x.ok)"
            + "S.textContent=t.name+' on the TV for 30 s. Keep it?'});$('list').appendChild(b)})});"
            + "</script></body></html>";
}
