#!/usr/bin/env node
// Uses the user's separate, unmodified DevKit checkout. No DevKit source is bundled.
// Calls its exported OAuth module directly; credentials stay in memory, no SDK store is used.
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { homedir } from 'node:os';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

const root = process.argv[2];
if (!root) {
  process.stderr.write('Usage: node tools/devkit-auth-control.mjs /absolute/path/to/sign-in-with-chatgpt-devkit [--check]\n');
  process.exit(2);
}
const modulePath = pathToFileURL(join(resolve(root), 'packages/local/dist/oauth.js')).href;
const sdk = await import(modulePath);
if (typeof sdk.authorize !== 'function' || typeof sdk.revokeConnection !== 'function') throw new Error('Build @siwc/local in the official checkout first.');
if (process.argv.includes('--check')) {
  process.stdout.write('Official OAuth module import passed. No authorization or account test performed.\n');
  process.exit(0);
}
const directory = join(homedir(), '.config', 'moling-devkit-control');
await mkdir(directory, { recursive: true, mode: 0o700 });
const metadataFile = join(directory, 'registration.json');
let saved;
try { saved = JSON.parse(await readFile(metadataFile, 'utf8')); }
catch (e) { if (e.code !== 'ENOENT') throw e; }
const hostId = saved?.hostId ?? `urn:uuid:${randomUUID()}`;
const saveMetadata = async (clientId, subject) => {
  // Explicit whitelist: never persist access, refresh or ID tokens.
  saved = { hostId, ...(clientId ? { clientId } : {}), ...(subject ? { subject } : {}) };
  await writeFile(metadataFile, JSON.stringify(saved), { mode: 0o600 });
};
await saveMetadata(saved?.clientId, saved?.subject);
const previous = saved.clientId ? {
  version: 1, clientId: saved.clientId, status: 'disconnected', scopes: [],
  savedAt: new Date().toISOString(), ...(saved.subject ? { subject: saved.subject } : {}),
} : undefined;
const controller = new AbortController();
const timeout = setTimeout(() => controller.abort(), 300000);
const originalFetch = globalThis.fetch;
// Observe only fixed request categories, never request bodies, query strings or credentials.
globalThis.fetch = async (input, options) => {
  const url = new URL(typeof input === 'string' || input instanceof URL ? input : input.url);
  if (url.origin === 'https://auth.openai.com' && url.pathname === '/api/accounts/oauth/token')
    process.stdout.write('Official module received callback; token exchange started.\n');
  if (url.origin === 'https://auth.openai.com' && url.pathname.includes('jwks'))
    process.stdout.write('Official module fetching identity verification keys.\n');
  return originalFetch(input, options);
};
let connection;
try {
  process.stdout.write('Opening system browser using the official OAuth module. Complete sign-in on THIS device.\n');
  connection = await sdk.authorize({ appName: 'Moling AI Paper', appId: 'moling-devkit-control', redirectPort: 0, sendHostId: true },
    previous, hostId, controller.signal, { onRegistration: clientId => saveMetadata(clientId, saved.subject) });
  await saveMetadata(connection.clientId, connection.subject);
  process.stdout.write(JSON.stringify({ identityVerified: true, planGranted: connection.scopes.includes('chatgpt.tokens.use.direct') }) + '\n');
} catch (e) {
  const allowed = new Set(['cancelled', 'access_denied', 'authorization_timeout', 'network_error', 'callback_failed', 'browser_unavailable', 'invalid_grant', 'registration_incomplete', 'account_mismatch', 'identity_verification_unavailable']);
  process.stderr.write('Official control did not complete: ' + (allowed.has(e.code) ? e.code : 'oauth_failed') + '\n');
  process.exitCode = 1;
} finally {
  clearTimeout(timeout);
  if (connection?.credentials?.refreshToken) {
    try { await sdk.revokeConnection(connection); process.stdout.write('Temporary control session revoked.\n'); }
    catch { process.stderr.write('Remote revocation not confirmed. Disconnect this control app in ChatGPT settings.\n'); }
  }
  connection = undefined;
  globalThis.fetch = originalFetch;
}
