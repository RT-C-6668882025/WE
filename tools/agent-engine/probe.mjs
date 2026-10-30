// An opt-in real-account capability test. Never runs during installation or CI.
const key = process.env.WE_BRIDGE_KEY;
const model = process.argv[2];
if (!key || !model) throw new Error('Set WE_BRIDGE_KEY and run npm run probe -- <account-model-id>');
const input = [{ role: 'user', content: 'Call we_add with a=7 and b=5 exactly once, then report the returned sum.' }];
const tools = [{ type: 'function', name: 'we_add', description: 'Add two integers', strict: true,
  parameters: { type: 'object', properties: { a: { type: 'integer' }, b: { type: 'integer' } }, required: ['a', 'b'], additionalProperties: false } }];
async function response(extra) {
  const res = await fetch('http://127.0.0.1:43187/v1/responses', {
    method: 'POST', headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, stream: true, store: false, include: ['reasoning.encrypted_content'], input, tools, ...extra })
  });
  if (!res.ok) throw new Error(`Bridge returned HTTP ${res.status}; inspect the app operation status`);
  const raw = await res.text();
  for (const block of raw.split(/\r?\n\r?\n/u)) {
    const data = block.split(/\r?\n/u).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
    if (!data || data === '[DONE]') continue;
    const event = JSON.parse(data);
    if (event.type === 'response.completed' && event.response?.status === 'completed') return event.response;
    if (['error', 'response.failed', 'response.incomplete'].includes(event.type)) throw new Error(`Model request ended: ${event.type}`);
  }
  throw new Error('Stream ended without response.completed');
}
const first = await response({ tool_choice: { type: 'function', name: 'we_add' }, parallel_tool_calls: false });
const calls = first.output.filter(item => item.type === 'function_call');
if (calls.length !== 1 || calls[0].name !== 'we_add') throw new Error('Expected exactly one we_add call');
const args = JSON.parse(calls[0].arguments);
if (args.a !== 7 || args.b !== 5) throw new Error('Tool arguments did not match');
input.push(...first.output, { type: 'function_call_output', call_id: calls[0].call_id, output: JSON.stringify({ sum: 12 }) });
const second = await response({ tool_choice: 'none' });
const text = second.output.filter(item => item.type === 'message').flatMap(item => item.content ?? []).filter(item => item.type === 'output_text').map(item => item.text).join('');
if (!text.includes('12')) throw new Error('Tool result was not reflected in the final answer');
console.log('PASS: subscription → function_call → local execution → function_call_output → final answer.');
