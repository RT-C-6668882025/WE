import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createRelay} from '../mcp/relay.mjs';
const moduleSource=readFileSync(new URL('../app/src/main/assets/mcp/connection.js',import.meta.url),'utf8');
let iteration=0;
async function browser(responses,initial=null){
 const calls=[];let saved=initial;
 globalThis.window={};
 globalThis.WeNative={request(id,method,payload){
  let value=null;
  if(method==='save'){saved=JSON.parse(payload);value=true;}
  if(method==='load')value=saved;
  if(method==='clear'){saved=null;value=true;}
  queueMicrotask(()=>window.weNativeReply({id,value,error:null}));
 }};
 globalThis.fetch=async(url,options={})=>{calls.push({url,options});const next=responses.shift();if(next instanceof Error)throw next;assert.ok(next,'Unexpected extra network request');return next;};
 const api=await import('data:text/javascript;base64,'+Buffer.from(moduleSource).toString('base64')+'#'+(++iteration));
 return {api,calls,saved:()=>saved};
}
const health=()=>new Response(JSON.stringify({protocol:2,persistent:true}));
const pair=()=>new Response(JSON.stringify({agentToken:'a'.repeat(64),deviceToken:'b'.repeat(64)}));
const originalFetch=globalThis.fetch;
test('WordTok fetch pairing uses health, empty POST, default browser redirects and fixed identity',async()=>{
 const b=await browser([health(),pair()]);
 const c=await b.api.connectAi('https://we.wordrelay.dpdns.org:8444/');
 assert.equal(b.calls.length,2);assert.equal(b.calls[0].url,'https://we.wordrelay.dpdns.org:8444/health');
 assert.equal(b.calls[1].url,'https://we.wordrelay.dpdns.org:8444/pair');assert.equal(b.calls[1].options.method,'POST');
 assert.equal(b.calls[1].options.body,undefined);assert.equal(b.calls[1].options.redirect,undefined);
 assert.ok(b.calls.every(c=>c.options.signal instanceof AbortSignal));
 assert.equal(c.mcpUrl,'https://we.wordrelay.dpdns.org:8444/mcp/'+'a'.repeat(64));
 assert.equal((await b.api.loadPair()).mcpUrl,c.mcpUrl);
});
test('failed health or pairing does not persist an incomplete connection',async()=>{
 for(const responses of [[new Response('{}',{status:503})],[new Response('{"protocol":1}')],[health(),new Response('{}',{status:503})],[new TypeError('Failed to fetch')]]){
  const b=await browser(responses);await assert.rejects(()=>b.api.connectAi('https://we.wordrelay.dpdns.org:8444'));assert.equal(b.saved(),null);
 }
});
test('revocation uses WordTok fetch bearer request; unauthorized clears and transient failures retain identity',async()=>{
 const identity={url:'https://we.wordrelay.dpdns.org:8444',token:'b'.repeat(64),mcpUrl:'https://we.wordrelay.dpdns.org:8444/mcp/'+'a'.repeat(64)};
 for(const status of [200,401,503]){
  const b=await browser([new Response('{}',{status})],identity);
  if(status===503){await assert.rejects(()=>b.api.disconnectAi(identity));assert.equal(b.saved(),identity);}
  else{await b.api.disconnectAi(identity);assert.equal(b.saved(),null);}
  assert.equal(b.calls[0].url,identity.url+'/device/close');
  assert.equal(b.calls[0].options.headers.Authorization,'Bearer '+identity.token);
  assert.equal(b.calls[0].options.body,'{}');
 }
});
test('relay allows only the explicit WordTok app origin and supports browser preflight',async()=>{
 globalThis.fetch=originalFetch;
 const relay=createRelay();await new Promise(resolve=>relay.listen(0,'127.0.0.1',resolve));
 const base='http://127.0.0.1:'+relay.address().port;
 try{
  const health=await originalFetch(base+'/health',{headers:{Origin:'https://localhost'}});
  assert.equal(health.status,200);assert.equal(health.headers.get('Access-Control-Allow-Origin'),'https://localhost');
  const denied=await originalFetch(base+'/health',{headers:{Origin:'https://untrusted.example'}});assert.equal(denied.status,403);
  const preflight=await originalFetch(base+'/device/close',{method:'OPTIONS',headers:{Origin:'https://localhost','Access-Control-Request-Headers':'authorization,content-type'}});
  assert.equal(preflight.status,204);assert.match(preflight.headers.get('Access-Control-Allow-Headers'),/Authorization/);
 }finally{await new Promise(resolve=>relay.close(resolve));}
});
