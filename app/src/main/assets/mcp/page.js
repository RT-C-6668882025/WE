import {nativeCall,loadPair,connectAi,disconnectAi} from './connection.js';
const $=id=>document.getElementById(id);
const labels={unpaired:'未配对',connecting:'正在连接',ready:'已就绪',working:'AI 正在操作',reconnecting:'重连中',revoked:'配对已失效'};
let connection=null,state='unpaired',busy=false,ready=false;
function render(){
 document.querySelector('.aiCard').setAttribute('aria-busy',String(busy));
 const label=busy?'处理中':labels[state];if($('status').lastElementChild.textContent!==label)$('status').lastElementChild.textContent=label;
 $('status').classList.toggle('isEnabled',state==='ready'||state==='working');
 $('pair').hidden=!!connection;$('pair').textContent=busy?'正在配对…':'配对连接';
 for(const id of ['endpoint','copy','reconnect','unpair'])$(id).hidden=!connection;
 for(const id of ['pair','copy','reconnect','unpair'])$(id).disabled=busy||!ready;
 $('ai-address').value=connection?.mcpUrl||'';
 $('hint').textContent=connection?'切换应用、锁屏后继续连接；退出软件后停止。地址长期保留。':'配对一次，将固定地址添加到支持 MCP 的 Agent。以后打开应用自动连接。';
}
async function start(){state='connecting';render();try{await nativeCall('start');}catch{state='reconnecting';render();}}
$('pair').onclick=async()=>{if(busy)return;busy=true;$('notice').textContent='';render();try{
 const url=await nativeCall('defaultRelay');connection=await connectAi(url);await start();
}catch(e){$('notice').textContent=e.message||'配对失败，请稍后重试';}finally{busy=false;render();}};
$('copy').onclick=async()=>{try{await navigator.clipboard.writeText(connection.mcpUrl);$('notice').textContent='固定连接地址已复制';}catch{$('notice').textContent='复制失败，请长按下方地址复制';}};
$('reconnect').onclick=async()=>{if(busy)return;busy=true;render();try{await nativeCall('stop');await start();}catch{$('notice').textContent='重连未完成，请重试';}finally{busy=false;render();}};
$('unpair').onclick=async()=>{if(!connection||busy||!confirm('解除配对后，原 MCP 地址将失效。确定解除？'))return;
 busy=true;render();try{await disconnectAi(connection);await nativeCall('stop');connection=null;state='unpaired';$('notice').textContent='已解除配对';}
 catch(e){$('notice').textContent=e.message||'解除失败，请联网后重试';}finally{busy=false;render();}};
$('ai-address').onfocus=()=>{$('ai-address').select();};
$('background').onclick=()=>nativeCall('allowBackground').catch(()=>{$('notice').textContent='请在系统电池设置中允许本应用后台运行';});
async function background(){try{$('background').hidden=!connection||await nativeCall('backgroundStatus');}catch{$('background').hidden=true;}}
async function monitor(){try{if(connection){const s=await nativeCall('state');state=labels[s.state]?s.state:'connecting';render();}await background();}catch{if(connection){state='reconnecting';render();}}finally{setTimeout(monitor,1000);}}
(async()=>{try{connection=await loadPair();ready=true;render();if(connection)await start();}
 catch{$('notice').textContent='配对凭据读取失败，请重试';}finally{render();monitor();}})();
// Instrumentation calls the same production fetch pairing; never inject a native network fallback.
window.weConnection={connectAi,disconnectAi,loadPair};
