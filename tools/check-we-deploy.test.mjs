import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,mkdtempSync,rmSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
const shell=readFileSync(new URL('./deploy-we-drawing-relay.sh',import.meta.url),'utf8');
const block=shell.split("<<'JS'\n")[1].split('\nJS')[0];
test('deployment changes only the module import, retaining origins and service options',()=>{
 const dir=mkdtempSync(join(tmpdir(),'we-deploy-'));
 try {
  const input=join(dir,'launcher.mjs'),out=join(dir,'next.mjs');
  const original="import {createRelay} from './old.mjs';\ncreateRelay({origins:['https://custom.example'],timeout:15000,dataFile:'/var/lib/we-mcp/relay.json'}).listen(8787,'127.0.0.1');\n";
  writeFileSync(input,original);
  const result=spawnSync(process.execPath,['--input-type=module','-',input,'/versioned/mcp/relay.mjs',out],{input:block,encoding:'utf8'});
  assert.equal(result.status,0,result.stderr);
  assert.equal(readFileSync(input,'utf8'),original);
  assert.equal(readFileSync(out,'utf8'),original.replace("import {createRelay} from './old.mjs';",'import {createRelay} from "/versioned/mcp/relay.mjs";'));
 } finally {rmSync(dir,{recursive:true,force:true})}
});
test('unrecognized launcher is rejected before a replacement is written',()=>{
 const dir=mkdtempSync(join(tmpdir(),'we-deploy-'));
 try {
  const input=join(dir,'launcher.mjs'),out=join(dir,'next.mjs');writeFileSync(input,'function createRelay(){}');
  const result=spawnSync(process.execPath,['--input-type=module','-',input,'/new.mjs',out],{input:block,encoding:'utf8'});
  assert.notEqual(result.status,0);assert.equal(existsSync(out),false);
 } finally {rmSync(dir,{recursive:true,force:true})}
});
