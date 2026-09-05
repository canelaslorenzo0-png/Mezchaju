#!/usr/bin/env node
/**
 * Mezchaju Dashboard Server
 * --------------------------
 * Serves the native in-app dashboard on 127.0.0.1:18922.
 *
 *  - HTTP  /               -> dashboard.html (the UI)
 *  - HTTP  /health         -> { services:[...], workspace, updates, controlToken }
 *  - HTTP  /openui/openclaw-> 302 redirect straight into the OpenClaw Control UI
 *                             (auto-authenticated, no device token prompt)
 *  - WS    /term?service=X -> real terminal session for service X (shared workspace)
 *  - WS    /api            -> control actions: start/stop/restart/update/status/checks
 *
 * Watches upstream repos (openclaw, deepseek-harness, claw-code) and broadcasts
 * an "update available" event the moment a new release lands. Runs with zero
 * npm dependencies — pure Node.
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const net = require('net');
const tls = require('tls');
const crypto = require('crypto');
const { spawn, spawnSync } = require('child_process');

// ─── Environment / paths ────────────────────────────────────────────────────

const arg = (name) => {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.split('=')[1] : '';
};

const PORT = Number(arg('port') || process.env.MEZCHAJU_DASHBOARD_PORT || 18922);
const HOME = process.env.HOME || os.homedir();
const PREFIX = process.env.PREFIX || '/data/data/com.codex.mobile/files/usr';
const WORKSPACE = process.env.MEZCHAJU_WORKSPACE || path.join(HOME, 'workspace');
const STATE_DIR = process.env.MEZCHAJU_STATE_DIR || path.join(HOME, '.mezchaju');
const RUN_DIR = path.join(STATE_DIR, 'run');
const LOG_DIR = path.join(STATE_DIR, 'logs');
const DASH_DIR = __dirname;

fs.mkdirSync(WORKSPACE, { recursive: true });
fs.mkdirSync(RUN_DIR, { recursive: true });
fs.mkdirSync(LOG_DIR, { recursive: true });
try {
  if (!fs.existsSync(path.join(WORKSPACE, '.git'))) {
    spawnSync('git', ['init', '-q'], { cwd: WORKSPACE, env: process.env });
  }
} catch {}

const log = (...args) => console.log(`[dashboard ${new Date().toISOString()}]`, ...args);

// ─── Control token (auto-auth for OpenClaw Control UI) ──────────────────────

function controlToken() {
  const file = path.join(STATE_DIR, 'control-token');
  try {
    const existing = fs.readFileSync(file, 'utf8').trim();
    if (existing.length >= 16) return existing;
  } catch {}
  const token = crypto.randomBytes(16).toString('hex');
  try {
    fs.mkdirSync(STATE_DIR, { recursive: true });
    fs.writeFileSync(file, token);
  } catch {}
  return token;
}

// ─── Version helpers ─────────────────────────────────────────────────────────

function readPkgVersion(pkg) {
  try {
    return JSON.parse(fs.readFileSync(path.join(PREFIX, 'lib/node_modules', pkg, 'package.json'), 'utf8')).version || '';
  } catch {
    return '';
  }
}

function cleanVersion(v) {
  return String(v || '').trim().replace(/^v/i, '').replace(/[^\d.].*$/, '');
}

function installedVersion(name) {
  switch (name) {
    case 'openclaw-gateway':
    case 'openclaw-control-ui':
      return readPkgVersion('openclaw');
    case 'deepseek-harness':
      return readPkgVersion('@deepseek-ai/dsh') || readPkgVersion('deepseek-harness');
    case 'claw-code': {
      const r = spawnSync('claw', ['--version'], { encoding: 'utf8', env: process.env, timeout: 15000 });
      const out = (r.stdout || r.stderr || '').trim();
      const m = out.match(/(\d+\.\d+\.\d+[^\s]*)/);
      return m ? m[1] : '';
    }
    case 'mezchaju-web':
      return readPkgVersion('codex-web-local');
  }
  return '';
}

// ─── Service registry ────────────────────────────────────────────────────────

const SERVICES = {
  'openclaw-gateway': {
    name: 'OpenClaw Gateway',
    icon: '🦞',
    kind: 'server',
    port: 18789,
    ui: '/openui/openclaw',
    repo: 'openclaw/openclaw',
    source: 'openclaw',
    description: 'MCP WebSocket gateway + agent runtime',
    startCmd: (ws) => `cd "${ws}" && exec openclaw gateway run --force --port 18789`,
  },
  'openclaw-control-ui': {
    name: 'OpenClaw Control UI',
    icon: '🧭',
    kind: 'server',
    port: 19001,
    ui: '/openui/openclaw',
    repo: 'openclaw/openclaw',
    source: 'openclaw',
    description: 'OpenClaw dashboard (auto-authenticated)',
    startCmd: (ws) => `cd "${ws}" && exec node "${path.join(DASH_DIR, 'control-ui-server.js')}" --port 19001`,
  },
  'deepseek-harness': {
    name: 'DeepSeek Harness',
    icon: '⚡',
    kind: 'cli',
    repo: 'deepseek-ai/deepseek-harness',
    source: '@deepseek-ai/dsh',
    description: 'dsh — the everything-is-a-plugin agent harness',
    cli: 'dsh',
  },
  'claw-code': {
    name: 'Claw Code',
    icon: '🦀',
    kind: 'cli',
    repo: 'ultraworkers/claw-code',
    source: 'claw-code',
    description: 'Rust-powered coding agent (ultraworkers/claw-code)',
    cli: 'claw',
  },
  'mezchaju-web': {
    name: 'Mezchaju Web UI',
    icon: '🌐',
    kind: 'server',
    port: 18923,
    ui: 'http://127.0.0.1:18923/',
    source: 'codex-web-local',
    description: 'Legacy web UI served on port 18923',
    startCmd: (ws) => `cd "${ws}" && exec node "${PREFIX}/lib/node_modules/codex-web-local/dist-cli/index.js" --port 18923 --no-password`,
  },
};

// Lite builds ship without the bundled web UI — drop that service entirely
// (status, start/stop and the web UI card all disappear from the dashboard).
if (process.env.MEZCHAJU_LITE === '1') {
  delete SERVICES['mezchaju-web'];
}

const UPDATE_CMDS = {
  'openclaw-gateway': 'npm install -g openclaw@latest',
  'openclaw-control-ui': 'npm install -g openclaw@latest',
  'deepseek-harness': 'npm install -g @deepseek-ai/dsh@latest',
  'claw-code': `cargo install --git https://github.com/ultraworkers/claw-code --root "${PREFIX}"`,
};

const UPDATE_SOURCES = [
  { service: 'openclaw-gateway', repo: 'openclaw/openclaw', npm: 'openclaw' },
  { service: 'openclaw-control-ui', repo: 'openclaw/openclaw', npm: 'openclaw' },
  { service: 'deepseek-harness', repo: 'deepseek-ai/deepseek-harness', npm: '@deepseek-ai/dsh' },
  { service: 'claw-code', repo: 'ultraworkers/claw-code', commit: true },
];

// ─── PID / process helpers ───────────────────────────────────────────────────

const pidFile = (name) => path.join(RUN_DIR, `${name}.pid`);

function readPid(name) {
  try {
    return parseInt(fs.readFileSync(pidFile(name), 'utf8').trim(), 10);
  } catch {
    return 0;
  }
}

function pidAlive(pid) {
  if (!pid || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function killPid(name) {
  const pid = readPid(name);
  if (pidAlive(pid)) {
    try { process.kill(pid, 'SIGKILL'); } catch {}
    try { process.kill(pid, 'SIGTERM'); } catch {}
  }
  try { fs.unlinkSync(pidFile(name)); } catch {}
}

function killByPort(port) {
  let pids = [];
  try {
    pids = fs.readdirSync('/proc').filter((p) => /^\d+$/.test(p));
  } catch {
    return;
  }
  for (const p of pids) {
    try {
      const cmdline = fs.readFileSync(`/proc/${p}/cmdline`, 'utf8');
      if (cmdline.includes(String(port))) {
        const pid = parseInt(p, 10);
        if (pid > 1) {
          try { process.kill(pid, 'SIGKILL'); } catch {}
        }
      }
    } catch {}
  }
}

function isPortOpen(port) {
  return new Promise((resolve) => {
    const s = net.connect({ host: '127.0.0.1', port });
    s.setTimeout(1500, () => { s.destroy(); resolve(false); });
    s.once('connect', () => { s.destroy(); resolve(true); });
    s.once('error', () => resolve(false));
  });
}

async function startService(name) {
  const svc = SERVICES[name];
  if (!svc) return { ok: false, error: 'unknown service' };
  if (svc.kind === 'cli') {
    return { ok: false, error: 'CLI agent — launch it from its Terminal' };
  }
  if (svc.port && (await isPortOpen(svc.port))) {
    return { ok: true, alreadyRunning: true };
  }
  killByPort(svc.port);
  const cmd = svc.startCmd(WORKSPACE);
  const child = spawn('sh', ['-c', cmd], {
    env: process.env,
    cwd: WORKSPACE,
    detached: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const out = fs.createWriteStream(path.join(LOG_DIR, `${name}.log`), { flags: 'a' });
  child.stdout.pipe(out);
  child.stderr.pipe(out);
  child.on('error', (e) => log(`start ${name} error:`, e.message));
  fs.writeFileSync(pidFile(name), String(child.pid));
  child.unref();
  log(`started ${name} (pid ${child.pid})`);
  return { ok: true, pid: child.pid };
}

function stopService(name) {
  const svc = SERVICES[name];
  if (!svc) return { ok: false, error: 'unknown service' };
  killPid(name);
  if (svc.port) killByPort(svc.port);
  return { ok: true };
}

async function restartService(name) {
  stopService(name);
  return startService(name);
}

// ─── GitHub update watcher ───────────────────────────────────────────────────

const UPDATES = {}; // service -> { latest, fetchedAt }

function httpsGetJson(host, reqPath, timeoutMs = 20000) {
  return new Promise((resolve, reject) => {
    const finish = (err, value) => { clearTimeout(timer); err ? reject(err) : resolve(value); };
    const timer = setTimeout(() => finish(new Error('timeout')), timeoutMs);
    const proxy = process.env.HTTPS_PROXY || process.env.https_proxy || process.env.HTTP_PROXY || process.env.http_proxy;
    const doTls = (socket, headersKey) => {
      const tlsSock = tls.connect({ socket, servername: host, rejectUnauthorized: false }, () => {
        tlsSock.write(
          `GET ${reqPath} HTTP/1.1\r\nHost: ${host}\r\nUser-Agent: MezchajuDashboard/1.0\r\nAccept: application/json\r\nConnection: close\r\n\r\n`,
        );
      });
      let data = Buffer.alloc(0);
      tlsSock.on('data', (c) => {
        data = Buffer.concat([data, c]);
        if (data.length > 3 * 1024 * 1024) { tlsSock.destroy(); finish(new Error('response too large')); }
      });
      tlsSock.on('end', () => {
        const text = data.toString('utf8');
        const headEnd = text.indexOf('\r\n\r\n');
        const status = parseInt(text.slice(9, 12), 10);
        if (!status || status < 100 || status >= 600) return finish(new Error(`bad status line: ${text.slice(0, 60)}`));
        const headers = text.slice(0, headEnd).toLowerCase();
        let body = headEnd >= 0 ? text.slice(headEnd + 4) : text;
        if (headers.includes('transfer-encoding: chunked')) {
          // decode HTTP/1.1 chunked body
          const out = [];
          let p = 0;
          for (;;) {
            const eol = body.indexOf('\r\n', p);
            if (eol < 0) break;
            const size = parseInt(body.slice(p, eol).split(';')[0].trim(), 16);
            if (!size || isNaN(size)) break;
            out.push(body.slice(eol + 2, eol + 2 + size));
            p = eol + 2 + size + 2;
          }
          body = out.join('');
        }
        if (!status || status < 200 || status >= 300) return finish(new Error(`HTTP ${status} ${body.slice(0, 80)}`));
        try { finish(null, JSON.parse(body)); } catch (e) { finish(new Error(`bad json: ${body.slice(0, 80)}`)); }
      });
      tlsSock.on('error', (e) => finish(e));
    };
    if (proxy) {
      let u;
      try { u = new URL(proxy); } catch { return finish(new Error('bad proxy url')); }
      const pPort = parseInt(u.port, 10) || (u.protocol === 'http:' ? 80 : 443);
      const sock = net.connect(pPort, u.hostname, () => {
        sock.write(`CONNECT ${host}:443 HTTP/1.1\r\nHost: ${host}:443\r\n\r\n`);
      });
      sock.setTimeout(timeoutMs, () => { sock.destroy(); finish(new Error('proxy timeout')); });
      let buf = '';
      sock.on('data', function onData(c) {
        buf += c.toString();
        if (buf.includes('\r\n\r\n')) {
          sock.removeListener('data', onData);
          if (!buf.startsWith('HTTP/1.1 200')) {
            sock.destroy();
            return finish(new Error('proxy CONNECT failed'));
          }
          doTls(sock);
        }
      });
      sock.on('error', (e) => finish(e));
    } else {
      doTls(net.connect(443, host));
    }
  });
}

async function fetchLatestFor(src) {
  const candidates = src.commit
    ? [{ host: 'api.github.com', path: `/repos/${src.repo}/commits?per_page=1`, pick: (d) => String(d?.[0]?.sha || '').slice(0, 12) }]
    : [{ host: 'api.github.com', path: `/repos/${src.repo}/releases/latest`, pick: (d) => cleanVersion(d?.tag_name || d?.name || '') }];
  if (src.npm) {
    candidates.push({
      host: 'registry.npmjs.org',
      path: `/${src.npm.replace('/', '%2F')}/latest`,
      pick: (d) => cleanVersion(d?.version || ''),
    });
  }
  for (const c of candidates) {
    try {
      const data = await httpsGetJson(c.host, c.path);
      const latest = c.pick(data);
      if (latest) return latest;
    } catch (e) {
      log(`fetch failed for ${c.host}${c.path}: ${e.message}`);
    }
  }
  return '';
}

async function checkUpdates(broadcastResult = false) {
  let changed = false;
  for (const src of UPDATE_SOURCES) {
    const latest = await fetchLatestFor(src);
    if (latest) {
      const prev = UPDATES[src.service];
      UPDATES[src.service] = { latest, fetchedAt: Date.now() };
      if (!prev || prev.latest !== latest) changed = true;
    }
    // delay between calls to be gentle with rate limits
    await new Promise((r) => setTimeout(r, 500));
  }
  if (changed || broadcastResult) broadcast({ type: 'updates', updates: getUpdates() });
  return getUpdates();
}

function installedMarker(name) {
  if (name === 'claw-code') {
    try { return fs.readFileSync(path.join(STATE_DIR, 'claw-update-sha'), 'utf8').trim(); } catch { return ''; }
  }
  return installedVersion(name);
}

function getUpdates() {
  const out = {};
  for (const svcName of Object.keys(SERVICES)) {
    const u = UPDATES[svcName];
    if (!u) continue;
    const marker = installedMarker(svcName);
    const installed = installedVersion(svcName);
    const available = svcName === 'claw-code'
      ? !!u.latest && u.latest !== marker
      : !!cleanVersion(u.latest) && cleanVersion(u.latest) !== cleanVersion(marker);
    out[svcName] = { latest: u.latest, installed: svcName === 'claw-code' ? (marker || installed || '—') : (installed || '—'), available, fetchedAt: u.fetchedAt };
  }
  return out;
}

// ─── Terminal sessions ───────────────────────────────────────────────────────

/** @type {Map<string, {shell:any, clients:Set, watchers:Set}>} */
const sessions = new Map();

function ensureTerminal(name) {
  if (sessions.has(name)) return sessions.get(name);
  const session = { name, shell: null, clients: new Set(), watchers: new Set() };
  const shell = spawn('sh', ['-i'], {
    env: {
      ...process.env,
      TERM: 'xterm-256color',
      COLORTERM: 'truecolor',
      PS1: '\u001b[38;5;208mmezchaju\u001b[0m:\w$ ',
      PROMPT_COMMAND: '',
    },
    cwd: WORKSPACE,
  });
  session.shell = shell;
  session.send = (data) => {
    try { shell.stdin.write(data); } catch {}
  };
  session.notice = (data) => {
    for (const c of session.clients) c.send(data);
  };
  const onData = (buf) => {
    const text = buf.toString('utf8');
    for (const w of session.watchers) {
      try { w(text); } catch {}
    }
    for (const c of session.clients) c.send(text);
  };
  shell.stdout.on('data', onData);
  shell.stderr.on('data', onData);
  shell.on('error', (e) => {
    for (const c of session.clients) c.send(`\r\n[Mezchaju] shell error: ${e.message}\r\n`);
  });
  shell.on('exit', (code) => {
    const msg = `\r\n[Mezchaju] ${name} session closed (exit ${code})\r\n`;
    for (const w of session.watchers) { try { w(msg); } catch {} }
    for (const c of session.clients) { try { c.send(msg); c.close(); } catch {} }
    sessions.delete(name);
  });
  sessions.set(name, session);
  session.notice(`\r\n\u001b[1;38;5;208mMezchaju terminal — ${name}\u001b[0m\r\n`);
  session.notice(`Workspace: ${WORKSPACE}\r\n`);
  session.notice(`Type 'help' for quick commands. Use Ctrl-C to interrupt.\r\n\r\n`);
  return session;
}

// ─── Update runner (update -> terminal output -> auto restart) ───────────────

function runUpdate(name) {
  const svc = SERVICES[name];
  const cmd = UPDATE_CMDS[name];
  if (!svc || !cmd) return { ok: false, error: 'not updatable' };
  const session = ensureTerminal(name);
  const marker = `__MEZCHAJU_UPDATE_DONE_${Date.now()}__`;
  let finished = false;
  const finish = () => {
    if (finished) return;
    finished = true;
    session.watchers.delete(watcher);
    if (name === 'claw-code') {
      try { fs.mkdirSync(STATE_DIR, { recursive: true }); fs.writeFileSync(path.join(STATE_DIR, 'claw-update-sha'), UPDATES[name]?.latest || ''); } catch {}
    }
    session.send(`\r\n[Mezchaju] Update finished. Restarting ${svc.name}…\r\n`);
    if (svc.kind === 'server') {
      const res = restartService(name);
      session.send(`[Mezchaju] ${svc.name} ${res.ok ? 'restarted ✔' : 'restart failed: ' + (res.error || '')}\r\n`);
    } else {
      session.send(`[Mezchaju] ${svc.name} updated ✔ (launch it from this terminal: ${svc.cli})\r\n`);
    }
    broadcast({ type: 'update-finished', service: name });
    getUpdates();
  };
  const watcher = (text) => {
    if (text.includes(marker)) finish();
  };
  session.watchers.add(watcher);
  broadcast({ type: 'update-started', service: name });
  session.notice(`\r\n[Mezchaju] Updating ${svc.name} — log attached to this terminal…\r\n`);
  session.send(`cd "${WORKSPACE}"\r\n${cmd}\r\necho "${marker}"\r\n`);
  setTimeout(finish, 20 * 60 * 1000);
  return { ok: true };
}

// ─── WebSocket (RFC 6455, dependency-free) ───────────────────────────────────

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

function encodeFrame(payload, opcode = 1) {
  const data = Buffer.isBuffer(payload) ? payload : Buffer.from(String(payload), 'utf8');
  const len = data.length;
  let header;
  if (len < 126) {
    header = Buffer.alloc(2);
    header[1] = len;
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  header[0] = 0x80 | opcode;
  return Buffer.concat([header, data]);
}

class WSClient {
  constructor(socket) {
    this.socket = socket;
    this.buffer = Buffer.alloc(0);
    this.onMessage = () => {};
    this.onClose = () => {};
    socket.on('data', (chunk) => this._onData(chunk));
    socket.on('close', () => this.onClose());
    socket.on('error', () => {});
  }
  _onData(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    for (;;) {
      const frame = this._decodeFrame();
      if (!frame) return;
      this.buffer = this.buffer.slice(frame.consumed);
      if (frame.opcode === 8) return this.close();
      if (frame.opcode === 9) { this.send(frame.payload, 10); continue; }
      if (frame.opcode === 1 || frame.opcode === 2) this.onMessage(frame.payload.toString('utf8'));
    }
  }
  _decodeFrame() {
    const b = this.buffer;
    if (b.length < 2) return null;
    const opcode = b[0] & 0x0f;
    const masked = (b[1] & 0x80) !== 0;
    let len = b[1] & 0x7f;
    let offset = 2;
    if (len === 126) {
      if (b.length < 4) return null;
      len = b.readUInt16BE(2);
      offset = 4;
    } else if (len === 127) {
      if (b.length < 10) return null;
      len = Number(b.readBigUInt64BE(2));
      offset = 10;
    }
    const maskLen = masked ? 4 : 0;
    if (b.length < offset + maskLen + len) return null;
    let payload = b.slice(offset + maskLen, offset + maskLen + len);
    if (masked) {
      const mask = b.slice(offset, offset + 4);
      payload = Buffer.from(payload);
      for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
    }
    return { opcode, payload, consumed: offset + maskLen + len };
  }
  send(payload, opcode = 1) {
    try { this.socket.write(encodeFrame(payload, opcode)); } catch {}
  }
  close() {
    try { this.socket.end(encodeFrame(Buffer.alloc(0), 8)); } catch {}
  }
}

/** @type {Set<WSClient>} */
const allClients = new Set();

function broadcast(msg) {
  const data = JSON.stringify(msg);
  for (const c of allClients) if (c.kind === 'api') c.send(data);
}

// ─── HTTP routes ─────────────────────────────────────────────────────────────

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.map': 'application/json',
};

function sendFile(res, file, status = 200) {
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      return res.end('not found');
    }
    res.writeHead(status, {
      'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
    });
    res.end(data);
  });
}

function sendJson(res, obj, status = 200) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-cache' });
  res.end(body);
}

async function healthPayload() {
  const statuses = {};
  for (const [name, svc] of Object.entries(SERVICES)) {
    if (svc.kind === 'server') {
      statuses[name] = (await isPortOpen(svc.port)) ? 'online' : 'offline';
    } else {
      statuses[name] = installedVersion(name) ? 'installed' : 'missing';
    }
  }
  return {
    ok: true,
    app: 'Mezchaju',
    version: '1.5.0',
    workspace: WORKSPACE,
    prefix: PREFIX,
    controlToken: controlToken(),
    services: Object.fromEntries(
      Object.entries(SERVICES).map(([name, svc]) => [
        name,
        { ...svc, startCmd: undefined, status: statuses[name], version: installedVersion(name) },
      ]),
    ),
    updates: getUpdates(),
  };
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const p = url.pathname;

  if (req.method === 'GET' && (p === '/' || p === '/index.html' || p === '/dashboard')) {
    return sendFile(res, path.join(DASH_DIR, 'dashboard.html'));
  }
  if (req.method === 'GET' && p === '/health') {
    return healthPayload().then((h) => sendJson(res, h)).catch((e) => sendJson(res, { ok: false, error: e.message }, 500));
  }
  if (req.method === 'GET' && p === '/openui/openclaw') {
    const target = `http://127.0.0.1:19001/?token=${controlToken()}`;
    res.writeHead(302, { Location: target });
    return res.end();
  }
  if (req.method === 'GET' && (p === '/xterm.js' || p === '/xterm.css' || p === '/addon-fit.js')) {
    return sendFile(res, path.join(DASH_DIR, path.basename(p)));
  }
  if (req.method === 'POST' && p === '/api') {
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 100_000) req.destroy(); });
    req.on('end', () => {
      let msg = {};
      try { msg = JSON.parse(body || '{}'); } catch {}
      handleApi(msg, null, (r) => sendJson(res, r, r.error ? 400 : 200));
    });
    return;
  }
  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('not found');
});

// ─── API actions ─────────────────────────────────────────────────────────────

async function handleApi(msg, ws, respond) {
  const action = msg.action || 'status';
  const name = msg.service || '';
  const reply = (data) => {
    const r = { type: 'api', action, service: name, ...data };
    if (respond) respond(r);
    else if (ws) ws.send(JSON.stringify(r));
    broadcast({ type: 'api', action, service: name, ok: !!data.ok });
  };
  switch (action) {
    case 'status':
      try { reply({ ok: true, ...(await healthPayload()) }); } catch (e) { reply({ ok: false, error: e.message }); }
      break;
    case 'start': {
      const r = await startService(name);
      reply(r);
      healthPayload().then((h) => broadcast({ type: 'health', ...h })).catch(() => {});
      break;
    }
    case 'stop': {
      const r = await stopService(name);
      reply(r);
      healthPayload().then((h) => broadcast({ type: 'health', ...h })).catch(() => {});
      break;
    }
    case 'restart': {
      const r = await restartService(name);
      reply(r);
      healthPayload().then((h) => broadcast({ type: 'health', ...h })).catch(() => {});
      break;
    }
    case 'update':
      reply(runUpdate(name));
      break;
    case 'checks':
      try { reply({ ok: true, updates: await checkUpdates(true) }); } catch (e) { reply({ ok: false, error: e.message }); }
      break;
    case 'version':
      reply({ ok: true, version: installedVersion(name) });
      break;
    default:
      reply({ ok: false, error: `unknown action ${action}` });
  }
}

// ─── WebSocket upgrade ───────────────────────────────────────────────────────

server.on('upgrade', (req, socket) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const key = req.headers['sec-websocket-key'];
  if (!key) { socket.destroy(); return; }

  const accept = crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  );

  const client = new WSClient(socket);
  allClients.add(client);
  client.kind = url.pathname === '/term' ? 'term' : 'api';
  client.onClose = () => {
    allClients.delete(client);
    if (url.pathname === '/term' && client._session) {
      client._session.clients.delete(client);
      if (client._session.clients.size === 0 && client._session.shell) {
        // keep the session alive briefly so Update can re-attach
        clearTimeout(client._session._closeTimer);
        client._session._closeTimer = setTimeout(() => {
          if (client._session.clients.size === 0 && client._session.shell) {
            try { client._session.shell.kill(); } catch {}
            sessions.delete(client._session.name);
          }
        }, 60_000);
      }
    }
  };

  if (url.pathname === '/term') {
    const name = url.searchParams.get('service') || '';
    if (!SERVICES[name]) { client.send('unknown service\r\n'); client.close(); return; }
    const session = ensureTerminal(name);
    client._session = session;
    session.clients.add(client);
    client.onMessage = (data) => session.send(data);
  } else if (url.pathname === '/api') {
    client.onMessage = (data) => {
      let msg = {};
      try { msg = JSON.parse(data); } catch {}
      handleApi(msg, client, null);
    };
  } else {
    client.send('unknown ws endpoint');
    client.close();
  }
});

// ─── Startup housekeeping ────────────────────────────────────────────────────

async function boot() {
  log(`Mezchaju dashboard starting on 127.0.0.1:${PORT}`);
  log(`workspace: ${WORKSPACE}`);
  log(`control token: ${controlToken().slice(0, 8)}…`);
  server.listen(PORT, '127.0.0.1', () => {
    log(`dashboard ready`);
    checkUpdates(true);
    setInterval(() => checkUpdates(false), 30 * 60 * 1000);
  });
  server.on('error', (e) => {
    if (e.code === 'EADDRINUSE') {
      log('port in use — exiting; the running dashboard keeps serving');
      process.exit(0);
    }
    log(`server error: ${e.message}`);
  });
}

for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, () => {
    log(`received ${sig} — shutting down`);
    for (const [name] of sessions) {
      try { sessions.get(name).shell.kill(); } catch {}
    }
    try {
      const pids = fs.readdirSync(RUN_DIR);
      for (const f of pids) {
        if (!f.endsWith('.pid')) continue;
        try { process.kill(parseInt(fs.readFileSync(path.join(RUN_DIR, f), 'utf8'), 10), 'SIGKILL'); } catch {}
      }
    } catch {}
    process.exit(0);
  });
}

boot().catch((e) => {
  log('fatal:', e);
  process.exit(1);
});
