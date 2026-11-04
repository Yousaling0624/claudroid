// Mock Anthropic-compatible API used to observe Claude Code's stream-json output contract.
// Usage: node mock-anthropic.mjs [port] [mode]
//   mode = text   -> answers with a plain assistant message (2 delta chunks)
//   mode = tool   -> first request asks for a Bash tool_use, later requests answer with text
import http from 'node:http';

const port = Number(process.argv[2] || 8787);
const mode = process.argv[3] || 'text';
let calls = 0;

const sse = (res, event, data) => {
  res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
};

function messageScript(turn) {
  // returns array of content blocks for this assistant message
  if (mode === 'tool' && turn === 1) {
    return [{ type: 'tool_use', id: 'toolu_01', name: 'Bash', input: { command: 'echo hello-from-tool', description: 'say hello' } }];
  }
  return [{ type: 'text', text: 'Hello from the mock endpoint. Second sentence for streaming.' }];
}

const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    console.log(`--> ${req.method} ${req.url} (${body.length} bytes)`);
    if (req.method !== 'POST' || !req.url.startsWith('/v1/messages')) {
      res.writeHead(404, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ type: 'error', error: { type: 'not_found_error', message: 'nope' } }));
      return;
    }
    calls += 1;
    let parsed = {};
    try { parsed = JSON.parse(body); } catch { /* ignore */ }
    console.log('    model=%s stream=%s msgs=%d betas=%s', parsed.model, parsed.stream, (parsed.messages || []).length, req.headers['anthropic-beta'] || '-');

    const blocks = messageScript(calls);
    res.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-cache',
      connection: 'keep-alive',
    });
    sse(res, 'message_start', {
      type: 'message_start',
      message: {
        id: `msg_${calls}`, type: 'message', role: 'assistant', model: parsed.model || 'mock-model',
        content: [], stop_reason: null, stop_sequence: null,
        usage: { input_tokens: 11, output_tokens: 1, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 },
      },
    });

    let index = 0;
    let outTokens = 1;
    for (const b of blocks) {
      if (b.type === 'text') {
        sse(res, 'content_block_start', { type: 'content_block_start', index, content_block: { type: 'text', text: '' } });
        // split into two deltas so we can observe partial streaming
        const cut = Math.ceil(b.text.length / 2);
        for (const chunk of [b.text.slice(0, cut), b.text.slice(cut)]) {
          sse(res, 'content_block_delta', { type: 'content_block_delta', index, delta: { type: 'text_delta', text: chunk } });
          outTokens += 4;
        }
        sse(res, 'content_block_stop', { type: 'content_block_stop', index });
      } else if (b.type === 'tool_use') {
        sse(res, 'content_block_start', { type: 'content_block_start', index, content_block: { type: 'tool_use', id: b.id, name: b.name, input: {} } });
        const json = JSON.stringify(b.input);
        sse(res, 'content_block_delta', { type: 'content_block_delta', index, delta: { type: 'input_json_delta', partial_json: json.slice(0, 8) } });
        sse(res, 'content_block_delta', { type: 'content_block_delta', index, delta: { type: 'input_json_delta', partial_json: json.slice(8) } });
        sse(res, 'content_block_stop', { type: 'content_block_stop', index });
        outTokens += 20;
      }
      index += 1;
    }

    const stopReason = blocks.some((b) => b.type === 'tool_use') ? 'tool_use' : 'end_turn';
    sse(res, 'message_delta', { type: 'message_delta', delta: { stop_reason: stopReason, stop_sequence: null }, usage: { output_tokens: outTokens } });
    sse(res, 'message_stop', { type: 'message_stop' });
    res.end();
  });
});

server.listen(port, '127.0.0.1', () => console.log(`mock anthropic listening on http://127.0.0.1:${port} mode=${mode}`));
