import { mkdir, writeFile, access } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';

export function engineConfig(model, baseURL = 'http://127.0.0.1:43187/v1') {
  if (!model || /[\s/]/u.test(model)) throw new Error('Pass the exact account model ID');
  const url = new URL(baseURL);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || url.pathname !== '/v1' || url.username || url.password)
    throw new Error('Use the local ADB-forwarded /v1 endpoint');
  return {
    $schema: 'https://opencode.ai/config.json',
    enabled_providers: ['we'],
    model: `we/${model}`,
    small_model: `we/${model}`,
    provider: {
      we: {
        npm: '@ai-sdk/openai', name: 'WE subscription bridge',
        options: { baseURL, apiKey: '{env:WE_BRIDGE_KEY}', timeout: 120000 },
        models: { [model]: { name: model, tool_call: true, attachment: true,
          modalities: { input: ['text', 'image'], output: ['text'] } } }
      }
    },
    // Local functions/MCP are executed by the existing engine, not by the model endpoint.
    permission: { '*': 'ask', skill: 'allow', read: 'allow', list: 'allow', glob: 'allow' }
  };
}

async function main() {
  const [directory, model] = process.argv.slice(2);
  if (!directory || !model) throw new Error('Usage: npm run setup -- <workspace-directory> <account-model-id>');
  const workspace = resolve(directory);
  await mkdir(join(workspace, '.opencode', 'skills'), { recursive: true });
  const target = join(workspace, 'opencode.json');
  try { await access(target); throw new Error('opencode.json already exists; merge configuration manually'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  await writeFile(target, JSON.stringify(engineConfig(model), null, 2) + '\n', { flag: 'wx' });
  console.log(`Created ${target}. Put Skills in .opencode/skills/<name>/SKILL.md. No credentials were written.`);
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href)
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
