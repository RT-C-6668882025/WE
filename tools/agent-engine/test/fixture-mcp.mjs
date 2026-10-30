import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
const server = new McpServer({ name: 'we-contract-fixture', version: '1.0.0' });
server.registerTool('add', {
  description: 'Add two numbers in the isolated contract fixture',
  inputSchema: { a: z.number(), b: z.number() }
}, async ({ a, b }) => ({ content: [{ type: 'text', text: JSON.stringify({ sum: a + b }) }] }));
await server.connect(new StdioServerTransport());
