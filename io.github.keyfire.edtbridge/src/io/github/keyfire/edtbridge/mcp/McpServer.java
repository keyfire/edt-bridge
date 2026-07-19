/*
 * edt-bridge - a 1C:EDT bridge that exposes the live EDT model over MCP.
 * Copyright 2026 edt-bridge contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.keyfire.edtbridge.mcp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;

import io.github.keyfire.edtbridge.edt.ProjectGateway;
import io.github.keyfire.edtbridge.tools.AddAttributeTool;
import io.github.keyfire.edtbridge.tools.AdoptObjectTool;
import io.github.keyfire.edtbridge.tools.AddFormAttributeTool;
import io.github.keyfire.edtbridge.tools.AddFormCommandTool;
import io.github.keyfire.edtbridge.tools.AddFormItemTool;
import io.github.keyfire.edtbridge.tools.ModifyFormItemTool;
import io.github.keyfire.edtbridge.tools.RemoveFormItemTool;
import io.github.keyfire.edtbridge.tools.AddFormTool;
import io.github.keyfire.edtbridge.tools.ModifyFormAttributeTool;
import io.github.keyfire.edtbridge.tools.ModifyFormCommandTool;
import io.github.keyfire.edtbridge.tools.RemoveFormAttributeTool;
import io.github.keyfire.edtbridge.tools.RemoveFormCommandTool;
import io.github.keyfire.edtbridge.tools.AddMethodTool;
import io.github.keyfire.edtbridge.tools.RemoveAttributeTool;
import io.github.keyfire.edtbridge.tools.ModifyAttributeTool;
import io.github.keyfire.edtbridge.tools.RenameTool;
import io.github.keyfire.edtbridge.tools.CreateExtensionTool;
import io.github.keyfire.edtbridge.tools.CreateExternalObjectTool;
import io.github.keyfire.edtbridge.tools.CreateObjectTool;
import io.github.keyfire.edtbridge.tools.DumpExternalObjectTool;
import io.github.keyfire.edtbridge.tools.InfobasesTool;
import io.github.keyfire.edtbridge.tools.PlatformHelpTool;
import io.github.keyfire.edtbridge.tools.PlatformInstallationsTool;
import io.github.keyfire.edtbridge.tools.RegisterPlatformTool;
import io.github.keyfire.edtbridge.tools.CreateInfobaseTool;
import io.github.keyfire.edtbridge.tools.BuildExtensionTool;
import io.github.keyfire.edtbridge.tools.ExtensionPropertiesTool;
import io.github.keyfire.edtbridge.tools.UpdateInfobaseTool;
import io.github.keyfire.edtbridge.tools.DeleteMethodTool;
import io.github.keyfire.edtbridge.tools.DeleteObjectTool;
import io.github.keyfire.edtbridge.tools.CleanProjectTool;
import io.github.keyfire.edtbridge.tools.DeleteProjectTool;
import io.github.keyfire.edtbridge.tools.FindReferencesTool;
import io.github.keyfire.edtbridge.tools.FormRenderTool;
import io.github.keyfire.edtbridge.tools.FormStructureTool;
import io.github.keyfire.edtbridge.tools.MetadataDetailsTool;
import io.github.keyfire.edtbridge.tools.MetadataObjectsTool;
import io.github.keyfire.edtbridge.tools.ProjectErrorsTool;
import io.github.keyfire.edtbridge.tools.ProjectsTool;
import io.github.keyfire.edtbridge.tools.ModuleTextTool;
import io.github.keyfire.edtbridge.tools.OutgoingCallsTool;
import io.github.keyfire.edtbridge.tools.PictureExportTool;
import io.github.keyfire.edtbridge.tools.OutgoingStructuresTool;
import io.github.keyfire.edtbridge.tools.GoToDefinitionTool;
import io.github.keyfire.edtbridge.tools.SearchModulesTool;
import io.github.keyfire.edtbridge.tools.SymbolInfoTool;
import io.github.keyfire.edtbridge.tools.ValidateQueryTool;
import io.github.keyfire.edtbridge.tools.DebugAttachTool;
import io.github.keyfire.edtbridge.tools.DebugDetachTool;
import io.github.keyfire.edtbridge.tools.DebugInspectTool;
import io.github.keyfire.edtbridge.tools.DebugControlTool;
import io.github.keyfire.edtbridge.tools.DebugEvaluateTool;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class McpServer {

    private static final Logger LOG = Logger.getLogger(McpServer.class.getName());
    private static final McpServer INSTANCE = new McpServer();

    public static McpServer getInstance() {
        return INSTANCE;
    }

    static final String SERVER_NAME = "edt-bridge";
    static final String SERVER_VERSION = resolveVersion();
    // Default MCP protocol revision when the client doesn't send one. The server actually echoes the
    // client's requested protocolVersion (see initializeResult) – we implement the stable base
    // JSON-RPC tools subset, which is compatible across revisions. Latest revision as of 2025-11-25.
    static final String PROTOCOL_VERSION = "2025-06-18";

    /**
     * The plugin's own version, taken from the bundle manifest – so a release bumps it in one place and
     * the dashboard and the MCP handshake cannot drift apart. The build qualifier is dropped
     * (0.3.1.202607190323 -> 0.3.1). Outside OSGi there is no bundle, hence the fallback.
     */
    private static String resolveVersion() {
        try {
            Bundle self = FrameworkUtil.getBundle(McpServer.class);
            if (self != null) {
                Version v = self.getVersion();
                return v.getMajor() + "." + v.getMinor() + "." + v.getMicro();
            }
        } catch (RuntimeException ignored) {
            // no framework context – fall through to the placeholder
        }
        return "0.0.0";
    }

    private static final String LANDING_PAGE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>edt-bridge</title>
<style>
:root{
  --bg:#131215;--surface:#232128;--surface-2:#1A181E;--surface-hover:#2C2A33;
  --border:rgba(255,255,255,0.10);--border-strong:rgba(255,255,255,0.18);
  --text-1:#F4F1EC;--text-2:#C5C0B8;--text-3:#948D82;--text-4:#6A6359;
  --accent:#E2231A;--accent-hover:#FF4A41;--accent-soft:rgba(226,35,26,0.16);
  --warm:#F59E0B;--success:#2FD27A;
  --btn:#FFD21E;--btn-hover:#FFDD55;--btn-fg:#1A1A1A;
  --shadow-card:0 18px 50px rgba(0,0,0,0.55);
  --font-ui:-apple-system,Roboto,"Segoe UI",system-ui,sans-serif;
  --font-mono:"JetBrains Mono",ui-monospace,"SF Mono",Menlo,Consolas,monospace;
}
:root[data-theme="light"]{
  --bg:#FBFAF8;--surface:#FFFFFF;--surface-2:#F3EFE8;--surface-hover:#ECE6DD;
  --border:rgba(33,28,22,0.12);--border-strong:rgba(33,28,22,0.22);
  --text-1:#1E1B17;--text-2:#4A443B;--text-3:#6E665B;--text-4:#9A9385;
  --accent-hover:#C51C14;--accent-soft:rgba(226,35,26,0.10);--warm:#C2780A;--success:#149A50;
  --shadow-card:0 16px 40px rgba(40,30,20,0.14);
}
.theme-switching,.theme-switching *{transition:none !important;}
*{box-sizing:border-box;}
body{margin:0;background:var(--bg);color:var(--text-2);font-family:var(--font-ui);font-size:14px;line-height:1.55;transition:background-color .25s,color .25s;}
.wrap{max-width:1000px;margin:0 auto;padding:28px 24px 48px;}
.top{display:flex;align-items:center;gap:10px;}
h1{font-size:22px;font-weight:800;color:var(--text-1);margin:0;display:flex;align-items:center;gap:10px;letter-spacing:-.01em;}
.dot{width:9px;height:9px;border-radius:50%;background:var(--success);box-shadow:0 0 10px var(--success);}
.spacer{flex:1;}
.sub{color:var(--text-3);margin:6px 0 20px;}
.panel{background:var(--surface);border:1px solid var(--border);border-radius:14px;padding:18px;margin:14px 0;box-shadow:var(--shadow-card);transition:background-color .25s,border-color .25s;}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:14px;}
.kv .k{color:var(--text-3);font-size:12px;margin-bottom:2px;}
.kv .v{color:var(--text-1);font-weight:600;word-break:break-word;}
.kv.wide{grid-column:1 / -1;}
.kv.wide .v{font-family:var(--font-mono);font-size:13px;font-weight:500;color:var(--text-2);}
h2{font-size:12px;text-transform:uppercase;letter-spacing:.08em;color:var(--text-3);margin:28px 0 10px;}
.tool h3{margin:0 0 5px;font-size:15px;color:var(--text-1);font-family:var(--font-mono);font-weight:700;}
.tool p{margin:0 0 12px;color:var(--text-3);}
textarea{width:100%;background:var(--surface-2);color:var(--text-1);border:1px solid var(--border);border-radius:10px;padding:10px;font-family:var(--font-mono);font-size:13px;resize:vertical;}
textarea:focus{outline:3px solid var(--accent-soft);outline-offset:1px;border-color:var(--border-strong);}
button.run{background:var(--btn);color:var(--btn-fg);border:1px solid var(--btn);border-radius:10px;padding:8px 18px;cursor:pointer;font-weight:700;font-size:14px;margin-top:10px;transition:background-color .15s,transform .15s,box-shadow .15s;}
button.run:hover{background:var(--btn-hover);transform:translateY(-2px);box-shadow:0 12px 28px rgba(255,210,30,0.30);}
button.run:active{transform:translateY(0);}
button.run:focus-visible{outline:3px solid rgba(255,210,30,0.55);outline-offset:2px;}
pre.out{background:var(--surface-2);border:1px solid var(--border);border-radius:10px;padding:12px;overflow:auto;max-height:360px;margin:10px 0 0;white-space:pre-wrap;word-break:break-word;color:var(--text-2);font-family:var(--font-mono);font-size:13px;}
pre.out:empty{display:none;}
input.tok{background:var(--surface-2);color:var(--text-1);border:1px solid var(--border);border-radius:8px;padding:7px 10px;width:280px;font-family:var(--font-mono);}
code{background:var(--surface-2);padding:1px 6px;border-radius:5px;font-family:var(--font-mono);color:var(--text-2);}
.tgl{display:inline-flex;align-items:center;justify-content:center;height:40px;min-width:40px;padding:0 11px;background:var(--surface);border:1px solid var(--border-strong);border-radius:10px;color:var(--text-2);cursor:pointer;font:700 13px/1 var(--font-ui);transition:color .15s,border-color .15s,transform .15s;}
.tgl:hover{color:var(--text-1);border-color:var(--text-3);transform:translateY(-2px);}
.tgl:focus-visible{outline:3px solid var(--accent-soft);outline-offset:2px;}
.tgl svg{width:18px;height:18px;}
.ico-moon{display:none;}
:root[data-theme="light"] .ico-sun{display:none;}
:root[data-theme="light"] .ico-moon{display:block;}
.toolbar{display:flex;gap:8px;margin:0 0 12px;flex-wrap:wrap;}
.toolbar button{background:var(--surface);border:1px solid var(--border-strong);border-radius:8px;color:var(--text-2);cursor:pointer;font:600 12px/1 var(--font-ui);padding:8px 13px;transition:color .15s,border-color .15s,transform .15s;}
.toolbar button:hover{color:var(--text-1);border-color:var(--text-3);transform:translateY(-1px);}
.group{margin:12px 0;border:1px solid var(--border);border-radius:14px;overflow:hidden;background:var(--surface-2);box-shadow:var(--shadow-card);}
.ghead{display:flex;align-items:center;gap:11px;padding:14px 16px;cursor:pointer;user-select:none;background:var(--surface);transition:background-color .15s;}
.ghead:hover{background:var(--surface-hover);}
.ghead .chev{transition:transform .18s;color:var(--text-3);font-size:11px;width:12px;text-align:center;}
.group:not(.collapsed) .ghead .chev{transform:rotate(90deg);}
.ghead .gtitle{font-weight:700;color:var(--text-1);font-size:14px;letter-spacing:-.01em;}
.ghead .gcount{margin-left:auto;color:var(--text-2);font-size:12px;font-family:var(--font-mono);background:var(--surface-2);border:1px solid var(--border);border-radius:20px;padding:2px 11px;}
.gbody{padding:4px 12px 10px;}
.gbody .tool{margin:12px 0;}
.group.collapsed .gbody{display:none;}
</style>
<script>try{var t=localStorage.getItem('edtbridge-theme');if(!t){t=(window.matchMedia&&matchMedia('(prefers-color-scheme: light)').matches)?'light':'dark';}if(t==='light'){document.documentElement.setAttribute('data-theme','light');}}catch(e){}</script>
</head>
<body>
<div class="wrap">
  <div class="top">
    <h1><span class="dot"></span> edt-bridge</h1>
    <span class="spacer"></span>
    <button class="tgl" id="langBtn" title="RU / EN">EN</button>
    <button class="tgl" id="themeBtn" aria-label="Theme">
      <svg class="ico-sun" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>
      <svg class="ico-moon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>
    </button>
  </div>
  <div class="sub" id="sub"></div>
  <div class="panel"><div class="grid" id="status"></div></div>
  <div id="tokbar"></div>
  <h2 id="h2caps"></h2>
  <div id="tools"></div>
  <h2 id="h2conn"></h2>
  <div class="panel"><span id="connIntro"></span>
<pre class="out" style="display:block">{
  "mcpServers": {
    "edt-bridge": { "type": "http", "url": "http://127.0.0.1:8770/mcp" }
  }
}</pre></div>
</div>
<script>
var MCP='/mcp', TOKEN='', LANG='en', STATUS=null, TOOLS=null;
var I18N={
 en:{sub:'Live 1C:EDT semantic model over MCP - read + write/refactor + debug, localhost.',server:'server',protocol:'protocol',endpoint:'endpoint',token:'auth token',projects:'open EDT projects',loading:'loading...',none:'none (localhost)',required:'required',noproj:'(none open)',caps:'Capabilities - run a tool',conn:'Connect an MCP client',connintro:'Add to your client config (e.g. .mcp.json):',run:'Run',running:'running...',badjson:'Invalid JSON in arguments: ',needtok:'Auth token required for tool calls: ',g_read:'Read / navigation',g_write:'Write / refactor',g_debug:'Debug',expandAll:'Expand all',collapseAll:'Collapse all'},
 ru:{sub:'Живая семантическая модель 1C:EDT по MCP - чтение, запись/рефакторинг, отладка, localhost.',server:'сервер',protocol:'протокол',endpoint:'адрес',token:'токен',projects:'открытые проекты EDT',loading:'загрузка...',none:'нет (localhost)',required:'требуется',noproj:'(нет открытых)',caps:'Возможности - запуск инструмента',conn:'Подключение MCP-клиента',connintro:'Добавьте в конфигурацию клиента (напр. .mcp.json):',run:'Запуск',running:'выполняется...',badjson:'Некорректный JSON в аргументах: ',needtok:'Для вызова инструментов нужен токен: ',g_read:'Чтение / навигация',g_write:'Запись / рефакторинг',g_debug:'Отладка',expandAll:'Развернуть все',collapseAll:'Свернуть все'}
};
function t(k){return (I18N[LANG]&&I18N[LANG][k])||I18N.en[k]||k;}
function initLang(){var l=null;try{l=localStorage.getItem('edtbridge-lang');}catch(e){}if(!l){l=((navigator.language||'en').toLowerCase().indexOf('ru')===0)?'ru':'en';}return l;}
function setLang(l){LANG=l;try{localStorage.setItem('edtbridge-lang',l);}catch(e){}document.documentElement.setAttribute('lang',l);document.getElementById('langBtn').textContent=(l==='ru'?'EN':'RU');applyI18n();}
function applyI18n(){document.getElementById('sub').textContent=t('sub');document.getElementById('h2caps').textContent=t('caps');document.getElementById('h2conn').textContent=t('conn');document.getElementById('connIntro').textContent=t('connintro');renderStatus();renderTools();}
function setTheme(th){var r=document.documentElement;document.body.classList.add('theme-switching');if(th==='light'){r.setAttribute('data-theme','light');}else{r.removeAttribute('data-theme');}try{localStorage.setItem('edtbridge-theme',th);}catch(e){}requestAnimationFrame(function(){document.body.classList.remove('theme-switching');});}
function toggleTheme(){var cur=document.documentElement.getAttribute('data-theme')==='light'?'light':'dark';setTheme(cur==='light'?'dark':'light');}
function hdr(){var h={'Content-Type':'application/json'};if(TOKEN){h['Authorization']='Bearer '+TOKEN;}return h;}
function rpc(method,params){return fetch(MCP,{method:'POST',headers:hdr(),body:JSON.stringify({jsonrpc:'2.0',id:Date.now(),method:method,params:params})}).then(function(r){return r.json();});}
function el(tag,cls,txt){var e=document.createElement(tag);if(cls){e.className=cls;}if(txt!=null){e.textContent=txt;}return e;}
function renderStatus(){var g=document.getElementById('status');g.textContent='';if(!STATUS){g.appendChild(el('div','kv',t('loading')));return;}var s=STATUS;function kv(k,v,wide){var d=el('div','kv'+(wide?' wide':''));d.appendChild(el('div','k',k));d.appendChild(el('div','v',v));g.appendChild(d);}kv(t('server'),s.name+' '+s.version);kv(t('protocol'),s.protocolVersion);kv(t('endpoint'),'127.0.0.1:'+s.port+'/mcp');kv(t('token'),s.tokenRequired?t('required'):t('none'));kv(t('projects'),(s.openProjects&&s.openProjects.length)?s.openProjects.join(', '):t('noproj'),true);}
function loadStatus(){fetch('/status').then(function(r){return r.json();}).then(function(s){STATUS=s;renderStatus();if(s.tokenRequired){var bar=document.getElementById('tokbar');bar.className='panel';bar.textContent='';bar.appendChild(el('span',null,t('needtok')));var inp=el('input','tok');inp.oninput=function(){TOKEN=inp.value.trim();};bar.appendChild(inp);}}).catch(function(e){STATUS=null;document.getElementById('status').textContent='status error: '+e;});}
function template(td){var req=(td.inputSchema&&td.inputSchema.required)||[];var o={};req.forEach(function(k){o[k]='';});return JSON.stringify(o,null,2);}
// Keep in sync with the token-gated write tools - anything missing here is shown as a read tool.
var WRITE_TOOLS=['edt_add_attribute','edt_add_form','edt_add_form_attribute','edt_add_form_command','edt_add_form_item','edt_add_method','edt_build_extension','edt_clean_project','edt_create_extension','edt_create_external_object','edt_create_infobase','edt_create_object','edt_delete_method','edt_delete_object','edt_delete_project','edt_dump_external_object','edt_modify_attribute','edt_modify_form_attribute','edt_modify_form_command','edt_modify_form_item','edt_register_platform','edt_remove_attribute','edt_remove_form_attribute','edt_remove_form_command','edt_remove_form_item','edt_rename','edt_update_infobase'];
function groupOf(n,tl){if(n.indexOf('edt_debug_')===0||n==='edt_evaluate'){return 'debug';}if(WRITE_TOOLS.indexOf(n)>=0){return 'write';}
// Fallback so a write tool nobody remembered to list still lands in the right group: every write
// tool is dry-run by default, so it takes an "apply". Only edt_register_platform does not, hence the list.
var p=tl&&tl.inputSchema&&tl.inputSchema.properties;if(p&&p.apply){return 'write';}return 'read';}
function grpCollapsed(id){try{var v=localStorage.getItem('edtbridge-grp-'+id);return v===null?true:v==='1';}catch(e){return true;}}
function setGrpCollapsed(id,c){try{localStorage.setItem('edtbridge-grp-'+id,c?'1':'0');}catch(e){}}
function makeCard(tl){var card=el('div','panel tool');card.appendChild(el('h3',null,tl.name));card.appendChild(el('p',null,(LANG==='ru'&&tl.descriptionRu)?tl.descriptionRu:(tl.description||'')));var tmpl=template(tl);var ta=el('textarea');ta.rows=Math.max(2,tmpl.split(String.fromCharCode(10)).length);ta.value=tmpl;card.appendChild(ta);var out=el('pre','out');var btn=el('button','run',t('run'));btn.onclick=function(){runTool(tl.name,ta,out);};card.appendChild(btn);card.appendChild(out);return card;}
function setAllGroups(collapsed){var gs=document.querySelectorAll('#tools .group');for(var i=0;i<gs.length;i++){var gid=gs[i].getAttribute('data-grp');if(collapsed){gs[i].classList.add('collapsed');}else{gs[i].classList.remove('collapsed');}setGrpCollapsed(gid,collapsed);}}
function renderTools(){var box=document.getElementById('tools');box.textContent='';if(!TOOLS){return;}var order=['read','write','debug'];var byGroup={read:[],write:[],debug:[]};TOOLS.forEach(function(tl){(byGroup[groupOf(tl.name,tl)]||byGroup.read).push(tl);});var tb=el('div','toolbar');var be=el('button',null,t('expandAll'));be.onclick=function(){setAllGroups(false);};var bc=el('button',null,t('collapseAll'));bc.onclick=function(){setAllGroups(true);};tb.appendChild(be);tb.appendChild(bc);box.appendChild(tb);order.forEach(function(gid){var list=byGroup[gid];if(!list.length){return;}var grp=el('div','group'+(grpCollapsed(gid)?' collapsed':''));grp.setAttribute('data-grp',gid);var head=el('div','ghead');head.appendChild(el('span','chev','▸'));head.appendChild(el('span','gtitle',t('g_'+gid)));head.appendChild(el('span','gcount',String(list.length)));head.onclick=function(){var c=!grp.classList.contains('collapsed');grp.classList.toggle('collapsed');setGrpCollapsed(gid,c);};var body=el('div','gbody');list.forEach(function(tl){body.appendChild(makeCard(tl));});grp.appendChild(head);grp.appendChild(body);box.appendChild(grp);});}
function loadTools(){rpc('tools/list',{}).then(function(res){TOOLS=(res.result&&res.result.tools)||[];renderTools();}).catch(function(e){document.getElementById('tools').textContent='tools error: '+e;});}
function runTool(name,ta,out){var args;try{args=JSON.parse(ta.value||'{}');}catch(e){out.textContent=t('badjson')+e;return;}out.textContent=t('running');rpc('tools/call',{name:name,arguments:args}).then(function(res){var r=res.result||res.error;if(r&&r.content&&r.content[0]&&r.content[0].text!=null){out.textContent=r.content[0].text;}else{out.textContent=JSON.stringify(r,null,2);}}).catch(function(e){out.textContent='error: '+e;});}
document.getElementById('themeBtn').onclick=toggleTheme;
document.getElementById('langBtn').onclick=function(){setLang(LANG==='ru'?'en':'ru');};
LANG=initLang();document.getElementById('langBtn').textContent=(LANG==='ru'?'EN':'RU');document.documentElement.setAttribute('lang',LANG);
applyI18n();loadStatus();loadTools();
</script>
</body>
</html>
""";

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final ProjectErrorsTool projectErrors = new ProjectErrorsTool();
    private final ProjectsTool projects = new ProjectsTool();
    private final ModuleTextTool moduleText = new ModuleTextTool();
    private final OutgoingCallsTool outgoingCalls = new OutgoingCallsTool();
    private final PictureExportTool pictureExport = new PictureExportTool();
    private final OutgoingStructuresTool outgoingStructures = new OutgoingStructuresTool();
    private final MetadataDetailsTool metadataDetails = new MetadataDetailsTool();
    private final FindReferencesTool findReferences = new FindReferencesTool();
    private final MetadataObjectsTool metadataObjects = new MetadataObjectsTool();
    private final ValidateQueryTool validateQuery = new ValidateQueryTool();
    private final GoToDefinitionTool goToDefinition = new GoToDefinitionTool();
    private final SymbolInfoTool symbolInfo = new SymbolInfoTool();
    private final FormStructureTool formStructure = new FormStructureTool();
    private final FormRenderTool formRender = new FormRenderTool();
    private final AddAttributeTool addAttribute = new AddAttributeTool();
    private final AddFormTool addForm = new AddFormTool();
    private final AddFormAttributeTool addFormAttribute = new AddFormAttributeTool();
    private final ModifyFormAttributeTool modifyFormAttribute = new ModifyFormAttributeTool();
    private final RemoveFormAttributeTool removeFormAttribute = new RemoveFormAttributeTool();
    private final AddFormCommandTool addFormCommand = new AddFormCommandTool();
    private final ModifyFormCommandTool modifyFormCommand = new ModifyFormCommandTool();
    private final RemoveFormCommandTool removeFormCommand = new RemoveFormCommandTool();
    private final AddFormItemTool addFormItem = new AddFormItemTool();
    private final ModifyFormItemTool modifyFormItem = new ModifyFormItemTool();
    private final RemoveFormItemTool removeFormItem = new RemoveFormItemTool();
    private final AddMethodTool addMethod = new AddMethodTool();
    private final DeleteMethodTool deleteMethod = new DeleteMethodTool();
    private final RemoveAttributeTool removeAttribute = new RemoveAttributeTool();
    private final ModifyAttributeTool modifyAttribute = new ModifyAttributeTool();
    private final RenameTool rename = new RenameTool();
    private final CreateObjectTool createObject = new CreateObjectTool();
    private final CreateExtensionTool createExtension = new CreateExtensionTool();
    private final CreateExternalObjectTool createExternalObject = new CreateExternalObjectTool();
    private final DumpExternalObjectTool dumpExternalObject = new DumpExternalObjectTool();
    private final InfobasesTool infobases = new InfobasesTool();
    private final UpdateInfobaseTool updateInfobase = new UpdateInfobaseTool();
    private final PlatformInstallationsTool platformInstallations = new PlatformInstallationsTool();
    private final RegisterPlatformTool registerPlatform = new RegisterPlatformTool();
    private final CreateInfobaseTool createInfobase = new CreateInfobaseTool();
    private final BuildExtensionTool buildExtension = new BuildExtensionTool();
    private final ExtensionPropertiesTool extensionProperties = new ExtensionPropertiesTool();
    private final PlatformHelpTool platformHelp = new PlatformHelpTool();
    private final DeleteObjectTool deleteObject = new DeleteObjectTool();
    private final DeleteProjectTool deleteProject = new DeleteProjectTool();
    private final CleanProjectTool cleanProject = new CleanProjectTool();
    private final SearchModulesTool searchModules = new SearchModulesTool();
    private final AdoptObjectTool adoptObject = new AdoptObjectTool();
    private final DebugAttachTool debugAttach = new DebugAttachTool();
    private final DebugDetachTool debugDetach = new DebugDetachTool();
    private final DebugInspectTool debugInspect = new DebugInspectTool();
    private final DebugControlTool debugControl = new DebugControlTool();
    private final DebugEvaluateTool debugEvaluate = new DebugEvaluateTool();
    private final ProjectGateway gateway = new ProjectGateway();
    private HttpServer http;
    private int port;

    private McpServer() {
    }

    public synchronized void startQuietly() {
        if (http != null) {
            return;
        }
        try {
            start();
        } catch (Exception e) {
            LOG.severe("edt-bridge: failed to start MCP server: " + e);
        }
    }

    public synchronized void start() throws IOException {
        if (http != null) {
            return;
        }
        enableNativeFormRender();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        int wanted = resolvePort();
        // Bind to the wanted port, or the next free one within a small range – so a second EDT
        // instance (its 8770 already taken) still comes up instead of failing to start. The actual
        // port is reported in /status and in the log so a client can discover it.
        IOException lastError = null;
        for (int candidate = wanted; candidate <= wanted + PORT_SCAN_RANGE; candidate++) {
            try {
                http = HttpServer.create(new InetSocketAddress(loopback, candidate), 0);
                port = candidate;
                break;
            } catch (IOException bindFailed) {
                lastError = bindFailed;
            }
        }
        if (http == null) {
            throw (lastError != null) ? lastError
                    : new IOException("no free port in [" + wanted + ", " + (wanted + PORT_SCAN_RANGE) + "]");
        }
        http.createContext("/mcp", this::handle);
        http.createContext("/status", this::handleStatus);
        http.createContext("/", this::handleRoot);
        // A bounded thread pool (not a single thread): a long operation – a rename or a project
        // create can run for many seconds – no longer blocks every other request. EDT's BM model does
        // its own read/write locking, so concurrent handlers are serialized only where the model needs.
        http.setExecutor(new ThreadPoolExecutor(2, MAX_WORKER_THREADS, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), namedDaemonThreadFactory()));
        http.start();
        if (port != wanted) {
            LOG.info("edt-bridge: port " + wanted + " was busy, using " + port);
        }
        LOG.info("edt-bridge MCP listening on http://127.0.0.1:" + port + "/mcp  (dashboard at /)  token="
                + (token() == null ? "(NONE - dev/localhost only!)" : "set"));
    }

    private static final int PORT_SCAN_RANGE = 20;
    private static final int MAX_WORKER_THREADS = 8;

    /** Daemon threads with a clear name, so a stuck worker is identifiable and never blocks JVM exit. */
    private static java.util.concurrent.ThreadFactory namedDaemonThreadFactory() {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "edt-bridge-mcp-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public synchronized void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
    }

    /**
     * Enable EDT's native form-layout renderer. {@code NativeRenderService} reads these flags into
     * {@code static final} fields in its class initializer, so they must be set BEFORE that class
     * loads (it loads lazily on the first form render – well after this). Without them
     * {@code HippoLayoutService} returns a layout with a null image. Don't override an explicit value.
     */
    private void enableNativeFormRender() {
        if (System.getProperty("nativeFormLayoutRender") == null) {
            System.setProperty("nativeFormLayoutRender", "true");
        }
        if (System.getProperty("nativeFormBufferedLayoutRender") == null) {
            System.setProperty("nativeFormBufferedLayoutRender", "true");
        }
    }

    private int resolvePort() {
        String p = System.getProperty("edt.bridge.port", System.getenv("EDT_BRIDGE_PORT"));
        if (p == null || p.isBlank()) {
            p = io.github.keyfire.edtbridge.EdtBridgePrefs.get(io.github.keyfire.edtbridge.EdtBridgePrefs.KEY_PORT);
        }
        try {
            return (p == null || p.isBlank()) ? 8770 : Integer.parseInt(p.trim());
        } catch (NumberFormatException e) {
            return 8770;
        }
    }

    private String token() {
        // launch-time config wins; otherwise the value set on the "edt-bridge" preference page.
        String t = System.getProperty("edt.bridge.token", System.getenv("EDT_BRIDGE_TOKEN"));
        if (t == null || t.isBlank()) {
            t = io.github.keyfire.edtbridge.EdtBridgePrefs.get(io.github.keyfire.edtbridge.EdtBridgePrefs.KEY_TOKEN);
        }
        return (t == null || t.isBlank()) ? null : t.trim();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, "{}");
                return;
            }
            String required = token();
            if (required != null) {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                String alt = ex.getRequestHeaders().getFirst("X-Edt-Bridge-Token");
                boolean ok = ("Bearer " + required).equals(auth) || required.equals(alt);
                if (!ok) {
                    send(ex, 401, "{}");
                    return;
                }
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject req = JsonParser.parseString(body).getAsJsonObject();
            JsonElement resp = dispatch(req);
            if (resp == null) {
                ex.sendResponseHeaders(202, -1); // notification: no body
                ex.close();
                return;
            }
            send(ex, 200, gson.toJson(resp));
        } catch (Exception e) {
            send(ex, 200, gson.toJson(errorObj(JsonNull.INSTANCE, -32603, "internal: " + e.getMessage())));
        }
    }

    /** GET / - human-facing status dashboard (no token required; read-only info + UI). */
    private void handleRoot(HttpExchange ex) throws IOException {
        try {
            if (!"/".equals(ex.getRequestURI().getPath())) {
                send(ex, 404, "{}");
                return;
            }
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, "{}");
                return;
            }
            byte[] b = LANDING_PAGE.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        } catch (Exception e) {
            send(ex, 500, "{}");
        }
    }

    /** GET /status - JSON server status for the dashboard (no token required). */
    private void handleStatus(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{}");
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("name", SERVER_NAME);
        o.addProperty("version", SERVER_VERSION);
        o.addProperty("protocolVersion", PROTOCOL_VERSION);
        o.addProperty("port", port);
        o.addProperty("tokenRequired", token() != null);
        o.addProperty("serverTime", java.time.OffsetDateTime.now().toString());
        JsonArray projects = new JsonArray();
        try {
            for (String n : gateway.listOpenProjects()) {
                projects.add(n);
            }
        } catch (Exception ignored) {
            // workspace not ready yet - leave the list empty
        }
        o.add("openProjects", projects);
        send(ex, 200, gson.toJson(o));
    }

    private JsonElement dispatch(JsonObject req) {
        String method = req.has("method") ? req.get("method").getAsString() : "";
        JsonElement id = req.get("id"); // null for notifications
        switch (method) {
            case "initialize":
                return result(id, initializeResult(req.has("params") ? req.getAsJsonObject("params") : null));
            case "tools/list":
                return result(id, toolsList());
            case "tools/call":
                return result(id, toolsCall(req.has("params") ? req.getAsJsonObject("params") : new JsonObject()));
            case "ping":
                return result(id, new JsonObject());
            case "notifications/initialized":
                return null;
            default:
                return id == null ? null : errorObj(id, -32601, "method not found: " + method);
        }
    }

    private JsonObject initializeResult(JsonObject params) {
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        JsonObject info = new JsonObject();
        info.addProperty("name", SERVER_NAME);
        info.addProperty("version", SERVER_VERSION);
        JsonObject r = new JsonObject();
        // Per the MCP spec, echo the client's requested protocolVersion when it sends one (we support
        // the stable base tools subset across revisions); fall back to our default otherwise.
        String version = PROTOCOL_VERSION;
        if (params != null && params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()) {
            String requested = params.get("protocolVersion").getAsString();
            if (requested != null && !requested.isBlank()) {
                version = requested;
            }
        }
        r.addProperty("protocolVersion", version);
        r.add("capabilities", caps);
        r.add("serverInfo", info);
        return r;
    }

    private JsonObject toolsList() {
        JsonArray tools = new JsonArray();
        tools.add(projectErrors.descriptor());
        tools.add(projects.descriptor());
        tools.add(moduleText.descriptor());
        tools.add(outgoingCalls.descriptor());
        tools.add(pictureExport.descriptor());
        tools.add(outgoingStructures.descriptor());
        tools.add(metadataDetails.descriptor());
        tools.add(findReferences.descriptor());
        tools.add(metadataObjects.descriptor());
        tools.add(validateQuery.descriptor());
        tools.add(goToDefinition.descriptor());
        tools.add(symbolInfo.descriptor());
        tools.add(formStructure.descriptor());
        tools.add(formRender.descriptor());
        tools.add(addAttribute.descriptor());
        tools.add(addForm.descriptor());
        tools.add(addFormAttribute.descriptor());
        tools.add(modifyFormAttribute.descriptor());
        tools.add(removeFormAttribute.descriptor());
        tools.add(addFormCommand.descriptor());
        tools.add(modifyFormCommand.descriptor());
        tools.add(removeFormCommand.descriptor());
        tools.add(addFormItem.descriptor());
        tools.add(modifyFormItem.descriptor());
        tools.add(removeFormItem.descriptor());
        tools.add(addMethod.descriptor());
        tools.add(deleteMethod.descriptor());
        tools.add(removeAttribute.descriptor());
        tools.add(modifyAttribute.descriptor());
        tools.add(rename.descriptor());
        tools.add(createObject.descriptor());
        tools.add(createExtension.descriptor());
        tools.add(createExternalObject.descriptor());
        tools.add(dumpExternalObject.descriptor());
        tools.add(infobases.descriptor());
        tools.add(updateInfobase.descriptor());
        tools.add(platformInstallations.descriptor());
        tools.add(registerPlatform.descriptor());
        tools.add(createInfobase.descriptor());
        tools.add(buildExtension.descriptor());
        tools.add(extensionProperties.descriptor());
        tools.add(platformHelp.descriptor());
        tools.add(deleteObject.descriptor());
        tools.add(deleteProject.descriptor());
        tools.add(cleanProject.descriptor());
        tools.add(searchModules.descriptor());
        tools.add(adoptObject.descriptor());
        tools.add(debugAttach.descriptor());
        tools.add(debugDetach.descriptor());
        tools.add(debugInspect.descriptor());
        tools.add(debugControl.descriptor());
        tools.add(debugEvaluate.descriptor());
        JsonObject r = new JsonObject();
        r.add("tools", tools);
        return r;
    }

    private JsonObject toolsCall(JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments")
                : new JsonObject();
        if (projectErrors.name().equals(name)) {
            return projectErrors.call(args);
        }
        if (projects.name().equals(name)) {
            return projects.call(args);
        }
        if (moduleText.name().equals(name)) {
            return moduleText.call(args);
        }
        if (outgoingCalls.name().equals(name)) {
            return outgoingCalls.call(args);
        }
        if (pictureExport.name().equals(name)) {
            return pictureExport.call(args);
        }
        if (outgoingStructures.name().equals(name)) {
            return outgoingStructures.call(args);
        }
        if (metadataDetails.name().equals(name)) {
            return metadataDetails.call(args);
        }
        if (findReferences.name().equals(name)) {
            return findReferences.call(args);
        }
        if (metadataObjects.name().equals(name)) {
            return metadataObjects.call(args);
        }
        if (validateQuery.name().equals(name)) {
            return validateQuery.call(args);
        }
        if (goToDefinition.name().equals(name)) {
            return goToDefinition.call(args);
        }
        if (symbolInfo.name().equals(name)) {
            return symbolInfo.call(args);
        }
        if (formStructure.name().equals(name)) {
            return formStructure.call(args);
        }
        if (formRender.name().equals(name)) {
            return formRender.call(args);
        }
        if (addAttribute.name().equals(name)) {
            JsonObject denied = writeTokenGate(addAttribute.isWrite(), name);
            return denied != null ? denied : addAttribute.call(args);
        }
        if (addForm.name().equals(name)) {
            JsonObject denied = writeTokenGate(addForm.isWrite(), name);
            return denied != null ? denied : addForm.call(args);
        }
        if (addFormAttribute.name().equals(name)) {
            JsonObject denied = writeTokenGate(addFormAttribute.isWrite(), name);
            return denied != null ? denied : addFormAttribute.call(args);
        }
        if (modifyFormAttribute.name().equals(name)) {
            JsonObject denied = writeTokenGate(modifyFormAttribute.isWrite(), name);
            return denied != null ? denied : modifyFormAttribute.call(args);
        }
        if (removeFormAttribute.name().equals(name)) {
            JsonObject denied = writeTokenGate(removeFormAttribute.isWrite(), name);
            return denied != null ? denied : removeFormAttribute.call(args);
        }
        if (addFormCommand.name().equals(name)) {
            JsonObject denied = writeTokenGate(addFormCommand.isWrite(), name);
            return denied != null ? denied : addFormCommand.call(args);
        }
        if (modifyFormCommand.name().equals(name)) {
            JsonObject denied = writeTokenGate(modifyFormCommand.isWrite(), name);
            return denied != null ? denied : modifyFormCommand.call(args);
        }
        if (removeFormCommand.name().equals(name)) {
            JsonObject denied = writeTokenGate(removeFormCommand.isWrite(), name);
            return denied != null ? denied : removeFormCommand.call(args);
        }
        if (addFormItem.name().equals(name)) {
            JsonObject denied = writeTokenGate(addFormItem.isWrite(), name);
            return denied != null ? denied : addFormItem.call(args);
        }
        if (modifyFormItem.name().equals(name)) {
            JsonObject denied = writeTokenGate(modifyFormItem.isWrite(), name);
            return denied != null ? denied : modifyFormItem.call(args);
        }
        if (removeFormItem.name().equals(name)) {
            JsonObject denied = writeTokenGate(removeFormItem.isWrite(), name);
            return denied != null ? denied : removeFormItem.call(args);
        }
        if (addMethod.name().equals(name)) {
            JsonObject denied = writeTokenGate(addMethod.isWrite(), name);
            return denied != null ? denied : addMethod.call(args);
        }
        if (deleteMethod.name().equals(name)) {
            JsonObject denied = writeTokenGate(deleteMethod.isWrite(), name);
            return denied != null ? denied : deleteMethod.call(args);
        }
        if (removeAttribute.name().equals(name)) {
            JsonObject denied = writeTokenGate(removeAttribute.isWrite(), name);
            return denied != null ? denied : removeAttribute.call(args);
        }
        if (modifyAttribute.name().equals(name)) {
            JsonObject denied = writeTokenGate(modifyAttribute.isWrite(), name);
            return denied != null ? denied : modifyAttribute.call(args);
        }
        if (rename.name().equals(name)) {
            JsonObject denied = writeTokenGate(rename.isWrite(), name);
            return denied != null ? denied : rename.call(args);
        }
        if (createObject.name().equals(name)) {
            JsonObject denied = writeTokenGate(createObject.isWrite(), name);
            return denied != null ? denied : createObject.call(args);
        }
        if (createExtension.name().equals(name)) {
            JsonObject denied = writeTokenGate(createExtension.isWrite(), name);
            return denied != null ? denied : createExtension.call(args);
        }
        if (createExternalObject.name().equals(name)) {
            JsonObject denied = writeTokenGate(createExternalObject.isWrite(), name);
            return denied != null ? denied : createExternalObject.call(args);
        }
        if (dumpExternalObject.name().equals(name)) {
            JsonObject denied = writeTokenGate(dumpExternalObject.isWrite(), name);
            return denied != null ? denied : dumpExternalObject.call(args);
        }
        if (infobases.name().equals(name)) {
            return infobases.call(args);
        }
        if (platformInstallations.name().equals(name)) {
            return platformInstallations.call(args);
        }
        if (registerPlatform.name().equals(name)) {
            JsonObject denied = writeTokenGate(registerPlatform.isWrite(), name);
            return denied != null ? denied : registerPlatform.call(args);
        }
        if (createInfobase.name().equals(name)) {
            JsonObject denied = writeTokenGate(createInfobase.isWrite(), name);
            return denied != null ? denied : createInfobase.call(args);
        }
        if (buildExtension.name().equals(name)) {
            JsonObject denied = writeTokenGate(buildExtension.isWrite(), name);
            return denied != null ? denied : buildExtension.call(args);
        }
        if (extensionProperties.name().equals(name)) {
            JsonObject denied = writeTokenGate(extensionProperties.isWrite(), name);
            return denied != null ? denied : extensionProperties.call(args);
        }
        if (platformHelp.name().equals(name)) {
            return platformHelp.call(args);
        }
        if (updateInfobase.name().equals(name)) {
            JsonObject denied = writeTokenGate(updateInfobase.isWrite(), name);
            return denied != null ? denied : updateInfobase.call(args);
        }
        if (deleteObject.name().equals(name)) {
            JsonObject denied = writeTokenGate(deleteObject.isWrite(), name);
            return denied != null ? denied : deleteObject.call(args);
        }
        if (deleteProject.name().equals(name)) {
            JsonObject denied = writeTokenGate(deleteProject.isWrite(), name);
            return denied != null ? denied : deleteProject.call(args);
        }
        if (cleanProject.name().equals(name)) {
            JsonObject denied = writeTokenGate(cleanProject.isWrite(), name);
            return denied != null ? denied : cleanProject.call(args);
        }
        if (searchModules.name().equals(name)) {
            return searchModules.call(args);
        }
        if (adoptObject.name().equals(name)) {
            JsonObject denied = writeTokenGate(adoptObject.isWrite(), name);
            return denied != null ? denied : adoptObject.call(args);
        }
        if (debugAttach.name().equals(name)) {
            JsonObject denied = writeTokenGate(debugAttach.isWrite(), name);
            return denied != null ? denied : debugAttach.call(args);
        }
        if (debugDetach.name().equals(name)) {
            JsonObject denied = writeTokenGate(debugDetach.isWrite(), name);
            return denied != null ? denied : debugDetach.call(args);
        }
        if (debugInspect.name().equals(name)) {
            return debugInspect.call(args);
        }
        if (debugControl.name().equals(name)) {
            JsonObject denied = writeTokenGate(debugControl.isWrite(), name);
            return denied != null ? denied : debugControl.call(args);
        }
        if (debugEvaluate.name().equals(name)) {
            JsonObject denied = writeTokenGate(debugEvaluate.isWrite(), name);
            return denied != null ? denied : debugEvaluate.call(args);
        }
        return toolError("unknown tool: " + name);
    }

    /**
     * Token gate for write tools: a write tool is refused on an unauthenticated server
     * (no {@code EDT_BRIDGE_TOKEN} configured). Returns the error result to short-circuit with, or
     * {@code null} when the call may proceed. When a token IS set, {@link #handle} has already
     * verified it on the request, so only the not-configured case is checked here.
     */
    private JsonObject writeTokenGate(boolean isWrite, String name) {
        if (isWrite && token() == null) {
            return toolError("write tool '" + name + "' requires a configured token "
                    + "(set EDT_BRIDGE_TOKEN); refusing on an unauthenticated server");
        }
        return null;
    }

    // ---- JSON-RPC envelope helpers ----

    private JsonObject result(JsonElement id, JsonElement value) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id == null ? JsonNull.INSTANCE : id);
        o.add("result", value);
        return o;
    }

    private JsonObject errorObj(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id == null ? JsonNull.INSTANCE : id);
        o.add("error", err);
        return o;
    }

    // ---- MCP tool-result helpers (used by tools) ----

    public static JsonObject textResult(String text) {
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonArray arr = new JsonArray();
        arr.add(content);
        JsonObject r = new JsonObject();
        r.add("content", arr);
        return r;
    }

    public static JsonObject toolError(String message) {
        JsonObject r = textResult(message);
        r.addProperty("isError", true);
        return r;
    }

    private void send(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
