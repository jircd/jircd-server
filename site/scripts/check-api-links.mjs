#!/usr/bin/env node
// Offline check of every link from the site's pages into /api/ (the Javadoc), run over the assembled
// site/dist after the Javadoc has been copied to dist/api (spec 017, research R3). The Starlight link
// validator exempts /api/, so this is the only check of those links. Node built-ins only.
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, posix, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const SITE_ORIGIN = 'https://jircd.org';
const dist = resolve(process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), '..', 'dist'));
const apiDir = join(dist, 'api');

if (!existsSync(join(apiDir, 'index.html'))) {
  console.error(`check-api-links: ${join(apiDir, 'index.html')} does not exist (was the Javadoc copied to dist/api?)`);
  process.exit(1);
}

function* htmlFiles(dir) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (path === apiDir) continue; // the Javadoc's own links are checked by javadoc itself
    if (statSync(path).isDirectory()) yield* htmlFiles(path);
    else if (name.endsWith('.html')) yield path;
  }
}

const decodeEntities = (text) =>
  text.replace(/&(amp|lt|gt|quot|#39|#x27);/g, (_, e) => ({ amp: '&', lt: '<', gt: '>', quot: '"', '#39': "'", '#x27': "'" })[e]);

function safeDecode(text) {
  try {
    return decodeURIComponent(text);
  } catch {
    return text;
  }
}

const anchorCache = new Map();
function anchorsOf(file) {
  let anchors = anchorCache.get(file);
  if (!anchors) {
    anchors = new Set();
    const html = readFileSync(file, 'utf8');
    for (const match of html.matchAll(/\s(?:id|name)\s*=\s*(?:"([^"]*)"|'([^']*)')/gi)) {
      anchors.add(decodeEntities(match[1] ?? match[2]));
    }
    anchorCache.set(file, anchors);
  }
  return anchors;
}

const problems = [];
let checked = 0;
for (const page of htmlFiles(dist)) {
  const pageUrl = '/' + relative(dist, page).split(sep).join('/');
  const html = readFileSync(page, 'utf8');
  for (const match of html.matchAll(/<a\s[^>]*?\bhref\s*=\s*(?:"([^"]*)"|'([^']*)')/gi)) {
    let href = decodeEntities(match[1] ?? match[2]).trim();
    if (href.startsWith(SITE_ORIGIN + '/')) href = href.slice(SITE_ORIGIN.length);
    if (/^[a-z][a-z0-9+.-]*:/i.test(href) || href.startsWith('//')) continue; // external
    const [beforeHash, ...hashParts] = href.split('#');
    const hash = hashParts.length > 0 ? safeDecode(hashParts.join('#')) : undefined;
    const pathPart = beforeHash.split('?')[0];
    if (pathPart === '') continue; // an in-page anchor
    // normalize() would silently resolve /api/../api-private/x into a path outside /api/ and skip it
    if (/^\/api(\/|$)/.test(pathPart) && safeDecode(pathPart).split('/').includes('..')) {
      checked++;
      problems.push(`${pageUrl}: ${href} (traversal segment in a link into /api/)`);
      continue;
    }
    const url = pathPart.startsWith('/') ? posix.normalize(pathPart) : posix.join(posix.dirname(pageUrl), pathPart);
    if (url !== '/api' && !url.startsWith('/api/')) continue;
    checked++;
    const where = `${pageUrl}: ${href}`;
    const target = join(dist, safeDecode(url));
    const file = url.endsWith('/') || url === '/api' ? join(target, 'index.html') : target;
    const inApi = file === apiDir || file.startsWith(apiDir + sep);
    if (!inApi || !existsSync(file) || !statSync(file).isFile()) {
      problems.push(`${where} (no such Javadoc page)`);
    } else if (hash && file.endsWith('.html') && !anchorsOf(file).has(hash)) {
      problems.push(`${where} (no anchor "${hash}" in ${relative(dist, file)})`);
    }
  }
}

if (problems.length > 0) {
  console.error(`check-api-links: ${problems.length} unresolved link(s) into /api/`);
  for (const problem of [...new Set(problems)]) console.error(`  - ${problem}`);
  process.exit(1);
}
console.log(`check-api-links: ${checked} link(s) into /api/ resolve`);
