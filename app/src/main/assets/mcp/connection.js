// Network functions copied from WordTok src/aiBridge.ts, blob 89618119aa74cd2a70dc67d0df0a62116eb80cc6.
// Only TypeScript annotations and the host persistence adapter differ.
let sequence=0;
const pending=new Map();
export function nativeCall(method,value=null){
 const id=String(++sequence);
 return new Promise((resolve,reject)=>{pending.set(id,{resolve,reject});WeNative.request(id,method,JSON.stringify(value));});
}
window.weNativeReply=({id,value,error})=>{const p=pending.get(id);if(!p)return;pending.delete(id);error?p.reject(Error(error)):p.resolve(value);};
// Older system WebViews lack these helpers; keep the same timeout and cancellation semantics.
if(!AbortSignal.timeout)AbortSignal.timeout=ms=>{const c=new AbortController();setTimeout(()=>c.abort(),ms);return c.signal;};
if(!AbortSignal.any)AbortSignal.any=signals=>{const c=new AbortController();for(const s of signals){if(s.aborted){c.abort();break;}s.addEventListener('abort',()=>c.abort(),{once:true});}return c.signal;};
export async function loadPair(){return nativeCall('load');}
export async function savePair(c){await nativeCall('save',c);}
export async function forgetPair(){await nativeCall('clear');}
async function api(c,path,body,signal){
 const timeout=AbortSignal.timeout(15000);const r=await fetch(c.url+path,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+c.token,...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:signal?AbortSignal.any([timeout,signal]):timeout});
 if(r.status===401)throw Error('PAIR_REVOKED');if(!r.ok)throw Error('服务暂时不可用');return r.json();
}
export async function connectAi(url){const base=url.replace(/\/$/,'');if(!base.startsWith('https://'))throw Error('连接服务需要 HTTPS 地址');const health=await fetch(base+'/health',{signal:AbortSignal.timeout(15000)});if(!health.ok)throw Error('连接服务暂时不可用');const info=await health.json();if(info.protocol!==2||!info.persistent)throw Error('连接服务尚未升级为固定配对版本，请先更新服务');const r=await fetch(base+'/pair',{method:'POST',signal:AbortSignal.timeout(15000)});if(!r.ok)throw Error('连接服务暂时不可用');const data=await r.json();const c={url:base,token:data.deviceToken,mcpUrl:base+'/mcp/'+data.agentToken};await savePair(c);return c}
export async function disconnectAi(c){try{await api(c,'/device/close',{})}catch(e){if(!(e instanceof Error)||e.message!=='PAIR_REVOKED')throw e}await forgetPair()}

