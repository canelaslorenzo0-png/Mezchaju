#!/usr/bin/env node
/**
 * Serves the OpenClaw Control UI (from the installed `openclaw` npm package)
 * on 127.0.0.1:19001 so the WebView can reach it without a login prompt.
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');

const arg = (name) => {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.split('=')[1] : '';
};
const PORT = Number(arg('port') || process.env.MEZCHAJU_CONTROL_UI_PORT || 19001);
const PREFIX = process.env.PREFIX || '/data/data/com.codex.mobile/files/usr';

const root = path.join(PREFIX, 'lib/node_modules/openclaw/dist/control-ui');
const fallback = path.join(root, 'index.html');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.wasm': 'application/wasm',
  '.map': 'application/json',
};

const server = http.createServer((req, res) => {
  let urlPath = (req.url || '/').split('?')[0];
  if (urlPath === '/') urlPath = '/index.html';
  const fp = path.normalize(path.join(root, urlPath));
  if (!fp.startsWith(root)) {
    res.writeHead(403);
    return res.end('forbidden');
  }
  fs.readFile(fp, (err, data) => {
    if (err) {
      fs.readFile(fallback, (e2, d2) => {
        if (e2) {
          res.writeHead(404);
          return res.end('control-ui not found: ' + e2.message);
        }
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(d2);
      });
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(fp).toLowerCase()] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
    });
    res.end(data);
  });
});

function listen() {
  server.listen(PORT, '127.0.0.1', () => console.log(`control-ui on 127.0.0.1:${PORT}`));
}
server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.log('control-ui port in use');
    process.exit(0);
  }
  console.error('control-ui error:', e.message);
  process.exit(1);
});
listen();
