// Serves the production build for the e2e suite. Deliberately not a dev
// server and deliberately not a proxy: every backend call is intercepted by
// page.route, so this process must have no way to reach a backend even if a
// stub is missed. A missing stub then fails as a visible request error rather
// than quietly hitting someone's running instance (sqlx-6jy).
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {extname, join, normalize} from 'node:path';

const ROOT = new URL('../build/sql-explorer-ui/browser/', import.meta.url).pathname;
const PORT = Number(process.env.E2E_PORT || 4173);

const TYPES = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.ico': 'image/x-icon', '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.png': 'image/png'
};

createServer(async (req, res) => {
  const path = normalize(decodeURIComponent(new URL(req.url, 'http://x').pathname));
  // The app owns its routes, so anything without a file extension is a deep
  // link and gets index.html — the same fallback nginx does in production.
  const file = extname(path) ? join(ROOT, path) : join(ROOT, 'index.html');
  try {
    const body = await readFile(file);
    res.writeHead(200, {'content-type': TYPES[extname(file)] || 'application/octet-stream'});
    res.end(body);
  } catch {
    res.writeHead(404).end('not found');
  }
}).listen(PORT, () => console.log(`e2e static server on ${PORT}`));
