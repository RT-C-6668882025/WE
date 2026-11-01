#!/usr/bin/env bash
set -euo pipefail
[[ ${EUID} -eq 0 ]] || { echo 'Run this script with sudo on the WE server.' >&2; exit 1; }
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
unit=we-mcp.service
target=/home/ubuntu/we-mcp
node=/opt/node22/bin/node
data=/var/lib/we-mcp/relay.json
[[ $(systemctl show "$unit" --property=WorkingDirectory --value) == "$target" ]] || { echo 'Unexpected WE working directory' >&2; exit 1; }
exec_start=$(systemctl show "$unit" --property=ExecStart --value)
[[ "$exec_start" == *"$node"* && "$exec_start" == *relay-local-we.mjs* ]] || { echo 'Unexpected WE launcher' >&2; exit 1; }
[[ -f "$target/relay-local-we.mjs" && -x "$node" && -f "$data" ]]
git -c safe.directory="$repo" -C "$repo" diff --quiet HEAD -- mcp/relay.mjs app/src/main/assets/we-mcp-tools.json
sha=$(git -c safe.directory="$repo" -C "$repo" rev-parse HEAD)
release="$target/releases/$sha"
[[ ! -e "$release" ]] || { echo 'This release directory already exists; inspect it before retrying.' >&2; exit 1; }
"$node" -e 'const fs=require("fs");const d=JSON.parse(fs.readFileSync(process.argv[1]));if(d.version!==2||!Array.isArray(d.sessions))throw Error("Unexpected pairing data format");' "$data"
install -d -m 755 "$target/releases" "$release" "$release/mcp" "$release/app" "$release/app/src" "$release/app/src/main" "$release/app/src/main/assets"
install -m 644 "$repo/mcp/relay.mjs" "$release/mcp/relay.mjs"
install -m 644 "$repo/app/src/main/assets/we-mcp-tools.json" "$release/app/src/main/assets/we-mcp-tools.json"
"$node" --check "$release/mcp/relay.mjs"
"$node" -e 'const t=require(process.argv[1]);if(t.length!==16)throw Error("Expected 16 tools");' "$release/app/src/main/assets/we-mcp-tools.json"
backup="$target/backups/drawing-$sha-$(date +%Y%m%d%H%M%S)"
install -d -m 700 "$backup"
cp -p "$target/relay-local-we.mjs" "$backup/relay-local-we.mjs"
cp -p "$data" "$backup/relay.json"
# Preserve custom allowed origins from the active launcher as well as the required app origin.
"$node" --input-type=module - "$target/relay-local-we.mjs" "$release/mcp/relay.mjs" "$target/relay-local-we.mjs.next" <<'JS'
import fs from 'node:fs';
const [oldPath,modulePath,out]=process.argv.slice(2);
const text=fs.readFileSync(oldPath,'utf8');
// Retain the entire existing launcher configuration; only switch its module import.
const pattern=/import\s*\{\s*createRelay\s*\}\s*from\s*(['"])[^'"]+\1\s*;?/;
if(!pattern.test(text)) throw Error('Expected a createRelay import; inspect the launcher before deploying.');
fs.writeFileSync(out,text.replace(pattern,`import {createRelay} from ${JSON.stringify(modulePath)};`));
JS
"$node" --check "$target/relay-local-we.mjs.next"
chmod --reference="$target/relay-local-we.mjs" "$target/relay-local-we.mjs.next"
chown --reference="$target/relay-local-we.mjs" "$target/relay-local-we.mjs.next"
rollback() {
    cp -p "$backup/relay-local-we.mjs" "$target/relay-local-we.mjs"
    systemctl restart "$unit"
    echo 'WE launcher rolled back. Pairing data was preserved.' >&2
}
trap rollback ERR
mv "$target/relay-local-we.mjs.next" "$target/relay-local-we.mjs"
systemctl restart "$unit"
systemctl is-active --quiet "$unit"
"$node" --input-type=module - "$data" <<'JS'
import fs from 'node:fs';
let response;
for(let attempt=0;attempt<20;attempt++){
 try {response=await fetch('http://127.0.0.1:8787/health',{headers:{Origin:'https://localhost'},signal:AbortSignal.timeout(1000)});if(response.ok)break;} catch {}
 await new Promise(r=>setTimeout(r,250));
}
if(!response)throw Error('WE did not become ready');
if(response.status!==200||response.headers.get('access-control-allow-origin')!=='https://localhost')throw Error('Health/CORS check failed');
const data=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
if(data.sessions.length){
 const r=await fetch('http://127.0.0.1:8787/mcp/'+data.sessions[0].agent,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list'})});
 const result=await r.json();const names=result.result?.tools?.map(x=>x.name)||[];
 if(names.length!==16||!['edit_canvas','canvas_history','export_canvas'].every(x=>names.includes(x)))throw Error('Drawing tools missing');
}
console.log('WE relay health, CORS and tool definitions verified. Existing pairings preserved.');
JS
trap - ERR
