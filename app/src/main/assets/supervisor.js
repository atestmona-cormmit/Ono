'use strict';
// Runs inside nodejs-mobile. Each bot runs in its own worker_thread.
const http = require('http');
const path = require('path');
const { Worker } = require('worker_threads');
const { pathToFileURL } = require('url');

const PORT = parseInt(process.argv[2] || '45731', 10);
const TOKEN = process.argv[3] || '';
const MAX_LOG = 1000;
const bots = new Map();

process.on('uncaughtException', (e) => console.error('[supervisor] uncaught', e));
process.on('unhandledRejection', (e) => console.error('[supervisor] rejection', e));

function get(id) {
  let b = bots.get(id);
  if (!b) {
    b = { worker: null, running: false, manual: false, last: 0, endedAt: 0, exit: null, logs: [], base: 0 };
    bots.set(id, b);
  }
  return b;
}

function push(id, text) {
  const b = get(id);
  b.last = Date.now();
  for (const line of String(text).split(/\r?\n/)) {
    if (line) b.logs.push(line.slice(0, 2000));
  }
  if (b.logs.length > MAX_LOG) {
    const cut = b.logs.length - MAX_LOG;
    b.logs.splice(0, cut);
    b.base += cut;
  }
}

function startBot(id, dir, rel) {
  const b = get(id);
  if (b.running) return 'already running';
  const file = path.join(dir, rel);
  try { process.chdir(dir); } catch (e) {}
  let w;
  try {
    w = new Worker(pathToFileURL(file), { stdout: true, stderr: true, env: Object.assign({}, process.env) });
  } catch (e) {
    push(id, 'START ERROR: ' + (e && e.stack || e));
    return 'start failed: ' + (e && e.message || e);
  }
  b.worker = w; b.running = true; b.manual = false; b.exit = null;
  push(id, '▶ start ' + rel);
  w.stdout.on('data', (d) => push(id, d.toString()));
  w.stderr.on('data', (d) => push(id, d.toString()));
  w.on('error', (e) => push(id, 'ERROR: ' + (e && e.stack || e)));
  w.on('exit', (code) => {
    if (b.worker === w) { b.worker = null; b.running = false; b.exit = code; b.endedAt = Date.now(); }
    push(id, '■ exited (' + code + ')');
  });
  return 'ok';
}

function stopBot(id) {
  const b = bots.get(id);
  if (!b || !b.worker) return 'not running';
  const w = b.worker;
  b.worker = null; b.running = false; b.manual = true; b.endedAt = Date.now();
  w.terminate();
  return 'ok';
}

http.createServer((req, res) => {
  const u = new URL(req.url, 'http://127.0.0.1');
  const q = u.searchParams;
  let out;
  if (q.get('t') !== TOKEN) {
    res.statusCode = 403; res.end('{"error":"forbidden"}'); return;
  }
  try {
    switch (u.pathname) {
      case '/status': {
        const o = {};
        for (const [id, b] of bots) {
          o[id] = { running: b.running, manual: b.manual, last: b.last, endedAt: b.endedAt, exit: b.exit };
        }
        out = { bots: o };
        break;
      }
      case '/start': out = { result: startBot(q.get('id'), q.get('dir'), q.get('entry')) }; break;
      case '/stop': out = { result: stopBot(q.get('id')) }; break;
      case '/logs': {
        const b = get(q.get('id'));
        let since = parseInt(q.get('since') || '0', 10);
        const end = b.base + b.logs.length;
        if (since > end || since < b.base) since = b.base;
        out = { lines: b.logs.slice(since - b.base), next: end };
        break;
      }
      default: res.statusCode = 404; out = { error: 'not found' };
    }
  } catch (e) {
    res.statusCode = 500; out = { error: String(e && e.message || e) };
  }
  res.setHeader('content-type', 'application/json');
  res.end(JSON.stringify(out));
}).listen(PORT, '127.0.0.1', () => console.log('[supervisor] listening on ' + PORT));
