import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source=readFileSync(new URL('../app/src/main/assets/mcp/page.js',import.meta.url),'utf8');
test('reconnect clicks remain single flight and failure releases controls',async()=>{
 const nodes=new Map();
 const node=()=>({hidden:false,disabled:false,textContent:'',value:'',lastElementChild:{textContent:''},classList:{toggle(){}},setAttribute(){}});
 let stops=0,release;let fail=false;
 const nativeCall=async method=>{
  if(method==='stop'){stops++;await new Promise(resolve=>{release=resolve});if(fail)throw Error('failed');}
  if(method==='state')return {state:'ready'};
  if(method==='backgroundStatus')return true;
 };
 const context=vm.createContext({document:{getElementById(id){if(!nodes.has(id))nodes.set(id,node());return nodes.get(id)},querySelector(){return node()}},window:{},navigator:{clipboard:{writeText:async()=>{}}},setTimeout(){},confirm:()=>true,nativeCall,loadPair:async()=>({mcpUrl:'https://example.test/mcp/test'}),connectAi:async()=>{},disconnectAi:async()=>{}});
 vm.runInContext(source.replace(/^import[^\n]+\n/,''),context);
 for(let i=0;i<10;i++)await Promise.resolve();
 const reconnect=nodes.get('reconnect');
 const first=reconnect.onclick();await Promise.resolve();const repeated=reconnect.onclick();await repeated;
 assert.equal(stops,1);assert.equal(reconnect.disabled,true);
 release();await first;assert.equal(reconnect.disabled,false);
 fail=true;const retry=reconnect.onclick();await Promise.resolve();release();await retry;
 assert.equal(reconnect.disabled,false);assert.match(nodes.get('notice').textContent,/重连未完成/);
});
