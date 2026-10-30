import { spawn } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
if (!process.env.WE_BRIDGE_KEY || !process.argv[2]) throw new Error('Set WE_BRIDGE_KEY; usage: node start.mjs <workspace-directory>');
const root = fileURLToPath(new URL('./', import.meta.url));
const directory = resolve(process.argv[2]);
const child = spawn(process.execPath, [resolve(root, 'node_modules/opencode-ai/bin/opencode'), directory], {
  stdio: 'inherit',
  env: { ...process.env, OPENCODE_DISABLE_DEFAULT_PLUGINS: 'true', OPENCODE_DISABLE_AUTOUPDATE: 'true' }
});
child.on('error', error => { console.error(error.message); process.exitCode = 1; });
child.on('exit', code => { process.exitCode = code ?? 1; });
