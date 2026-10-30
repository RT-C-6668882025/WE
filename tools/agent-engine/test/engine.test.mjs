import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { once } from 'node:events';
import { engineConfig } from '../setup.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
test('config uses the Responses provider and never embeds credentials', () => {
  const config = engineConfig('account-model');
  assert.equal(config.provider.we.npm, '@ai-sdk/openai');
  assert.equal(config.provider.we.options.apiKey, '{env:WE_BRIDGE_KEY}');
  assert.throws(() => engineConfig('x', 'http://0.0.0.0:43187/v1'));
  assert.throws(() => engineConfig(''));
});

test('real OpenCode loads a Skill then calls an SDK MCP tool through the production bridge', {
  skip: !process.env.WE_BRIDGE_TEST_CLASSPATH, timeout: 90000
}, async () => {
  const temp = await mkdtemp(join(tmpdir(), 'we-engine-'));
  const fixture = spawn('java', ['-cp', process.env.WE_BRIDGE_TEST_CLASSPATH, 'com.opc.baize.subscription.AgentBridgeFixture'], { stdio: ['pipe', 'pipe', 'pipe'] });
  let fixtureLog = ''; fixture.stderr.on('data', x => fixtureLog += x);
  let engine;
  try {
    const lines = createInterface({ input: fixture.stdout });
    const firstLine = await Promise.race([once(lines, 'line'), new Promise((_, reject) => {
      const timer = setTimeout(() => reject(new Error('fixture startup timed out')), 10000); timer.unref();
    })]);
    const { baseURL, key } = JSON.parse(firstLine[0]);
    const config = engineConfig('we-fixture', baseURL);
    config.permission = { '*': 'allow' };
    config.mcp = { we_fixture: { type: 'local', command: [process.execPath, join(root, 'test/fixture-mcp.mjs')], enabled: true } };
    const skill = join(temp, '.opencode/skills/we-bridge-fixture');
    await mkdir(skill, { recursive: true });
    await writeFile(join(skill, 'SKILL.md'), '---\nname: we-bridge-fixture\ndescription: Fixture skill for the WE bridge contract\n---\nWE_SKILL_FIXTURE_MARKER\nCall the we_fixture_add tool with 7 and 5.\n');
    await writeFile(join(temp, 'opencode.json'), JSON.stringify(config));
    engine = spawn(process.execPath, [join(root, 'node_modules/opencode-ai/bin/opencode'), 'run', '--format', 'json', 'Load we-bridge-fixture, add 7 and 5 using MCP, then report the result.'], {
      cwd: temp,
      env: { ...process.env, WE_BRIDGE_KEY: key,
        XDG_CONFIG_HOME: join(temp, 'config'), XDG_DATA_HOME: join(temp, 'data'), XDG_CACHE_HOME: join(temp, 'cache'), XDG_STATE_HOME: join(temp, 'state'),
        OPENCODE_DISABLE_DEFAULT_PLUGINS: 'true', OPENCODE_DISABLE_EXTERNAL_SKILLS: 'true', OPENCODE_DISABLE_AUTOUPDATE: 'true', OPENCODE_DISABLE_MODELS_FETCH: 'true' },
      stdio: ['ignore', 'pipe', 'pipe']
    });
    let output = '', errors = '';
    engine.stdout.on('data', x => output += x); engine.stderr.on('data', x => errors += x);
    const timer = setTimeout(() => engine.kill('SIGTERM'), 70000);
    const [code] = await once(engine, 'exit'); clearTimeout(timer);
    assert.equal(code, 0, `${errors}\n${output}\n${fixtureLog}`);
    assert.match(output, /WE_ENGINE_OK_12/, `${errors}\n${output}\n${fixtureLog}`);
    assert.match(fixtureLog, /fixture_round=1/);
    assert.match(fixtureLog, /fixture_round=2/);
    assert.match(fixtureLog, /fixture_round=3/);
  } finally {
    engine?.kill('SIGTERM'); fixture.stdin.end(); fixture.kill('SIGTERM');
    await rm(temp, { recursive: true, force: true });
  }
});
