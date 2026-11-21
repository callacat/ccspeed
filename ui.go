package main

// ui.html：内嵌 Web 控制台单页（原生 fetch，零 JS 依赖）。
const uiHTML = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ccspeed 控制台</title>
<style>
:root{--bg:#12141a;--card:#1a1d26;--line:#2a2f3d;--fg:#e6e9f0;--mut:#8b93a7;--acc:#4f8cff;--ok:#3fbf6f;--warn:#e6a23c}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif}
header{padding:14px 20px;border-bottom:1px solid var(--line);display:flex;align-items:center;gap:14px;flex-wrap:wrap}
header h1{font-size:16px;margin:0;font-weight:600}
.pill{padding:3px 10px;border-radius:999px;background:#223;font-size:12px;color:var(--mut);border:1px solid var(--line)}
.pill.ok{color:var(--ok);border-color:#26543a}
.pill.warn{color:var(--warn);border-color:#5b4526}
#node{color:var(--acc)}
main{padding:20px;max-width:1100px;margin:0 auto;display:grid;gap:16px;grid-template-columns:1fr 1fr}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px}
.card h2{margin:0 0 10px;font-size:13px;color:var(--mut);font-weight:600;letter-spacing:.05em}
.card.wide{grid-column:1/-1}
label{display:block;font-size:12px;color:var(--mut);margin:8px 0 4px}
input{width:100%;background:#11141b;border:1px solid var(--line);color:var(--fg);border-radius:6px;padding:7px 9px;font:inherit}
input:focus{outline:none;border-color:var(--acc)}
button{background:var(--acc);border:0;color:#fff;border-radius:6px;padding:7px 14px;font:inherit;cursor:pointer}
button.ghost{background:transparent;border:1px solid var(--line);color:var(--fg)}
button.sm{padding:3px 9px;font-size:12px}
.row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
.grow{flex:1}
.hint{font-size:12px;color:var(--mut);margin-top:8px}
.groups{display:grid;gap:12px;grid-template-columns:repeat(auto-fill,minmax(240px,1fr))}
.group{border:1px solid var(--line);border-radius:8px;overflow:hidden}
.group>summary{padding:8px 10px;background:#151824;cursor:pointer;font-weight:600;font-size:13px;list-style:none}
.group>summary::-webkit-details-marker{display:none}
.node{display:flex;align-items:center;gap:8px;padding:7px 10px;border-top:1px solid var(--line);font-size:12px}
.node .n{color:var(--mut);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1}
.node.cur .n{color:var(--acc)}
.node .ms{color:var(--mut);font-variant-numeric:tabular-nums;min-width:56px;text-align:right}
pre#logs{background:#0d0f14;border:1px solid var(--line);border-radius:8px;padding:10px;height:220px;overflow:auto;font:12px/1.6 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;color:#b9c2d4;white-space:pre-wrap;word-break:break-all;margin:0}
.toast{position:fixed;right:16px;bottom:16px;background:#1a1d26;border:1px solid var(--acc);color:var(--fg);padding:10px 14px;border-radius:8px;box-shadow:0 6px 24px #0008;display:none;max-width:380px}
.toast.show{display:block}
.spin{display:inline-block;width:12px;height:12px;border:2px solid var(--mut);border-top-color:transparent;border-radius:50%;animation:r 1s linear infinite;vertical-align:-2px}
@keyframes r{to{transform:rotate(360deg)}}
.kv{display:grid;grid-template-columns:auto 1fr;gap:4px 12px;font-size:13px}
.kv dt{color:var(--mut)}
.kv dd{margin:0;font-variant-numeric:tabular-nums;word-break:break-all}
</style>
</head>
<body>
<header>
  <h1>ccspeed 控制台</h1>
  <span class="pill" id="pill-listen">listen …</span>
  <span class="pill" id="pill-auth">SOCKS5 无鉴权</span>
  <span class="pill" id="pill-rot">固定节点</span>
</header>

<main>
  <section class="card">
    <h2>状态</h2>
    <dl class="kv" id="status"><dt>节点</dt><dd>加载中…</dd></dl>
    <div class="row" style="margin-top:10px">
      <button class="ghost sm" id="btn-refresh">刷新</button>
      <button class="ghost sm" id="btn-refast">重新测速选最快</button>
    </div>
  </section>

  <section class="card">
    <h2>凭据 / 控制台端口</h2>
    <div id="cred-state" class="hint">凭据状态检查中…</div>
    <label>Authorization（api_auth）</label>
    <input id="f-auth" placeholder="留空则不修改；当前未设置时填写" autocomplete="off">
    <label>comm（api_comm）</label>
    <input id="f-comm" placeholder="留空则不修改；当前未设置时填写" autocomplete="off">
    <label>SOCKS5 用户名（留空 = 关闭鉴权）</label>
    <input id="f-suser" autocomplete="off">
    <label>SOCKS5 口令</label>
    <input id="f-spass" type="password" autocomplete="new-password">
    <label>控制台端口（重启生效）</label>
    <input id="f-web" placeholder="127.0.0.1:28981">
    <div class="row" style="margin-top:12px">
      <button id="btn-save">保存</button>
      <span class="hint grow">API 凭据与 Web 端口需重启进程；SOCKS5 鉴权即时生效</span>
    </div>
    <div class="hint" id="save-note"></div>
  </section>

  <section class="card wide">
    <h2>节点 <span id="nodes-hint" class="pill"></span></h2>
    <div class="groups" id="groups"><div class="hint">首次拉取节点约 10–30s，请稍候…</div></div>
  </section>

  <section class="card wide">
    <h2>日志 <span class="pill" id="logs-tail">tail 200</span></h2>
    <pre id="logs">…</pre>
  </section>
</main>

<div class="toast" id="toast"></div>

<script>
const $=id=>document.getElementById(id);
function toast(msg){const t=$('toast');t.textContent=msg;t.classList.add('show');clearTimeout(t._t);t._t=setTimeout(()=>t.classList.remove('show'),3200)}
async function jget(p){const r=await fetch(p);if(!r.ok)throw new Error(p+' -> '+r.status);return r.json()}
async function refreshStatus(){
  try{
    const s=await jget('/api/status');
    $('pill-listen').textContent='listen '+s.listen;
    $('pill-listen').className='pill ok';
    $('pill-auth').textContent=s.socks_auth?'SOCKS5 需鉴权':'SOCKS5 无鉴权';
    $('pill-auth').className='pill '+(s.socks_auth?'ok':'warn');
    $('pill-rot').textContent=s.rotating?'每连接随机':'固定节点';
    $('status').innerHTML=
      '<dt>节点</dt><dd id="node">'+(s.node||'-')+'</dd>'+
      '<dt>名称</dt><dd>'+(s.node_name||'-')+'</dd>'+
      '<dt>分组</dt><dd>'+(s.node_group||'-')+'</dd>'+
      '<dt>延迟</dt><dd>'+(s.latency_ms?s.latency_ms+' ms':'未测')+'</dd>'+
      '<dt>候选节点</dt><dd>'+s.nodes+'</dd>'+
      '<dt>UDP 网关</dt><dd>'+(s.udp_node||'-')+'</dd>';
  }catch(e){$('pill-listen').textContent='离线';$('pill-listen').className='pill warn'}
}
async function refreshNodes(){
  try{
    const list=await jget('/api/nodes');
    const g={};
    for(const n of list){(g[n.group]=g[n.group]||[]).push(n)}
    const box=$('groups');
    if(!list.length){box.innerHTML='<div class="hint">无节点</div>';return}
    box.innerHTML='';
    for(const grp of Object.keys(g).sort()){
      const d=document.createElement('details');
      d.className='group';
      const sum=document.createElement('summary');
      sum.textContent=grp+' ('+g[grp].length+')';
      d.appendChild(sum);
      for(const nd of g[grp]){
        const row=document.createElement('div');
        row.className='node'+(nd.addr===window.cur?' cur':'');
        const n=document.createElement('span');n.className='n';
        const nm=document.createElement('div');nm.textContent=nd.name;
        const ad=document.createElement('div');ad.style.color='var(--mut)';ad.textContent=nd.addr;
        n.append(nm,ad);
        row.append(n);
        const b=document.createElement('button');
        b.className='sm';b.textContent='选用';
        b.onclick=()=>selectNode(nd.addr,b);
        row.appendChild(b);
        d.appendChild(row);
      }
      box.appendChild(d);
    }
    $('nodes-hint').textContent=list.length+' 个';
  }catch(e){const d=document.createElement('div');d.className='hint';d.textContent='节点加载失败：'+e.message;$('groups').replaceChildren(d)}
}
async function selectNode(addr,btn){
  btn.disabled=true;const old=btn.textContent;btn.textContent='切…';
  try{
    const r=await fetch('/api/select',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({addr})});
    const d=await r.json();
    if(!r.ok)throw new Error(d.error||r.status);
    window.cur=addr;toast('已切到 '+addr+'（'+(d.latency_ms||'?')+' ms）；老连接不受影响');
    refreshStatus();refreshNodes();
  }catch(e){toast('切换失败：'+e.message)}
  finally{btn.disabled=false;btn.textContent=old}
}
async function refast(){
  try{
    const list=await jget('/api/nodes');
    if(!list.length){toast('无节点');return}
    toast('重新测速 '+list.length+' 个节点，约 10–30s…');
    let best=null,bestD=1e18;
    for(const n of list){
      const t0=Date.now();
      try{
        const r=await fetch('/api/select',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({addr:n.addr})});
        const d=await r.json();
        const ms=d.latency_ms||(Date.now()-t0);
        if(ms&&ms<bestD){bestD=ms;best=n.addr}
      }catch(e){}
    }
    if(best){const r=await fetch('/api/select',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({addr:best})});toast('最快 '+best+' ('+bestD+' ms)')}
    else toast('未能选出可用节点');
    refreshStatus();refreshNodes();
  }catch(e){toast('测速失败：'+e.message)}
}
async function refreshLogs(){
  try{const d=await jget('/api/logs');$('logs').textContent=(d.lines||[]).join('\n')}catch(e){}
}
async function refreshConfig(){
  try{
    const c=await jget('/api/config');
    $('cred-state').textContent=(c.api_auth_set?'API 凭据已设置':'API 凭据未设置 — 首次使用请在下方填写')+(c.from_file?'（来源：配置文件）':'（来源：flag/env）')+' · 路径 '+c.config_path;
    $('f-web').value=c.web_addr||'127.0.0.1:28981';
  }catch(e){}
}
async function saveCfg(){
  const body={
    api_auth:$('f-auth').value.trim(),
    api_comm:$('f-comm').value.trim(),
    socks_user:$('f-suser').value.trim(),
    socks_pass:$('f-spass').value,
    web_addr:$('f-web').value.trim()
  };
  try{
    const r=await fetch('/api/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    const d=await r.json();
    if(!r.ok)throw new Error(d.error||r.status);
    $('save-note').textContent='已保存 '+d.saved+'：'+d.note;
    $('f-auth').value='';$('f-comm').value='';$('f-spass').value='';
    toast('已保存');refreshConfig();refreshStatus();
  }catch(e){toast('保存失败：'+e.message)}
}
$('btn-refresh').onclick=()=>{refreshStatus();refreshNodes();refreshLogs()};
$('btn-refast').onclick=refast;
$('btn-save').onclick=saveCfg;
refreshStatus();refreshNodes();refreshConfig();refreshLogs();
setInterval(()=>{refreshStatus();refreshLogs()},3000);
setInterval(refreshNodes,15000);
</script>
</body>
</html>
`
