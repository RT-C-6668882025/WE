import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createRelay,tools} from './relay.mjs';

test('paired MCP -> device -> persistent receipt, retry, restart and revocation',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'we-relay-'));
 let server,base;
 const start=async()=>{server=createRelay({dataFile:join(dir,'state.json'),timeout:400,pollWait:10});await new Promise(r=>server.listen(0,'127.0.0.1',r));base=`http://127.0.0.1:${server.address().port}`};
 const close=async()=>{server.closeAllConnections();await new Promise(r=>server.close(r))};
 const http=async(path,body,token,headers={})=>{const response=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{ }),...headers},...(body===undefined?{}:{body:JSON.stringify(body)})});const text=await response.text();return {status:response.status,body:text?JSON.parse(text):null}};
 try{
  await start();assert.equal((await http('/health')).body.name,'write-everything');
  assert.equal((await http('/pair',{},null,{Origin:'https://hostile.example'})).status,403);
  const pair=(await http('/pair',{})).body,path='/mcp/'+pair.agentToken;
  const rpc=async(method,params={})=>http(path,{jsonrpc:'2.0',id:1,method,params});
  assert.equal((await rpc('initialize',{protocolVersion:'2025-11-25'})).body.result.protocolVersion,'2025-11-25');
  assert.deepEqual((await rpc('tools/list')).body.result.tools,tools);
  assert.equal((await http(path)).status,405);
  assert.equal((await http(path,{jsonrpc:'2.0',method:'notifications/initialized'})).status,202);
  assert.equal((await http('/device/poll',undefined,'wrong')).status,401);
  await http('/device/poll',undefined,pair.deviceToken);
  const request={name:'create_workspace',arguments:{operation_id:'create-once',name:'Fixture'}};
  const pending=rpc('tools/call',request);
  let commands=[];for(let i=0;i<10&&!commands.length;i++)commands=(await http('/device/poll',undefined,pair.deviceToken)).body;
  assert.equal(commands.length,1);assert.equal(commands[0].id,'create-once');assert.equal(commands[0].name,'create_workspace');
  await http('/device/result',{id:'create-once',result:{id:'workspace-fixture',name:'Fixture'}},pair.deviceToken);
  assert.equal((await pending).body.result.structuredContent.status,'succeeded');
  assert.equal((await rpc('tools/call',request)).body.result.structuredContent.result.id,'workspace-fixture');
  assert.equal((await rpc('tools/call',{...request,arguments:{operation_id:'create-once',name:'Other'}})).body.result.structuredContent.status,'conflict');
  await close();await start();
  assert.equal((await rpc('tools/call',{name:'get_operation_result',arguments:{request_id:'create-once'}})).body.result.structuredContent.status,'succeeded');
  assert.equal((await rpc('tools/call',{name:'create_workspace',arguments:{operation_id:'offline',name:'Offline'}})).body.result.structuredContent.status,'offline');
  await http('/device/close',{},pair.deviceToken);assert.equal((await rpc('initialize')).status,404);
 }finally{if(server?.listening)await close();rmSync(dir,{recursive:true,force:true})}
});
