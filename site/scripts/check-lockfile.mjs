#!/usr/bin/env node
// Supply-chain guard for site/package-lock.json (spec 017, research R10).
// Fails when a package resolves from anywhere but https://registry.npmjs.org/, has no `resolved`
// URL, or has no `integrity` hash. Run before `npm ci`; Node built-ins only.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REGISTRY_HOST = 'registry.npmjs.org';
const REGISTRY = `https://${REGISTRY_HOST}/`;
const lockPath = process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), '..', 'package-lock.json');

let lock;
try {
  lock = JSON.parse(readFileSync(lockPath, 'utf8'));
} catch (error) {
  console.error(`check-lockfile: cannot read ${lockPath}: ${error.message}`);
  process.exit(1);
}
if (lock.lockfileVersion < 2 || typeof lock.packages !== 'object' || lock.packages === null) {
  console.error('check-lockfile: expected a lockfileVersion 2 or 3 file with a "packages" map');
  process.exit(1);
}

// Parses the URL instead of matching a prefix: a prefix test accepts https://registry.npmjs.org/../evil/x.tgz
// and credentials or a port in the authority. Returns a problem description, or undefined when acceptable.
function resolvedProblem(resolved) {
  if (resolved.includes('\\')) return 'contains a backslash';
  let url;
  try {
    url = new URL(resolved);
  } catch {
    return 'not a valid URL';
  }
  if (url.protocol !== 'https:') return 'protocol is not https:';
  if (url.hostname !== REGISTRY_HOST) return `host is not ${REGISTRY_HOST}`;
  if (url.username !== '' || url.password !== '') return 'has credentials';
  if (url.port !== '') return 'has a port';
  // new URL() resolves dot segments, so inspect the raw path of the original string.
  const rawPath = resolved.slice(resolved.indexOf(url.host) + url.host.length).split(/[?#]/)[0];
  if (rawPath.replaceAll('\\', '/').split('/').some((segment) => /^(\.|%2e){1,2}$/i.test(segment))) return 'path has a dot segment';
  return undefined;
}

function expectedTarballPath(packagePath, entry) {
  const packageName = typeof entry.name === 'string' ? entry.name : packagePath.split(/(?:^|\/)node_modules\//).at(-1);
  if (typeof packageName !== 'string' || packageName === packagePath || typeof entry.version !== 'string') return undefined;
  const filename = packageName.startsWith('@') ? packageName.split('/').at(-1) : packageName;
  if (!filename) return undefined;
  return `/${packageName}/-/${filename}-${entry.version}.tgz`;
}

const problems = [];
let checked = 0;
for (const [name, entry] of Object.entries(lock.packages)) {
  if (name === '') continue; // the root project
  if (entry.link === true) problems.push(`${name}: link entries are not allowed`);
  checked++;
  if (typeof entry.resolved !== 'string') {
    problems.push(`${name}: no "resolved" URL`);
  } else {
    const problem = resolvedProblem(entry.resolved);
    if (problem) problems.push(`${name}: resolved ${entry.resolved}: ${problem}`);
    else {
      const expectedPath = expectedTarballPath(name, entry);
      if (!expectedPath) {
        problems.push(`${name}: cannot determine expected tarball without a package name and version`);
      } else if (new URL(entry.resolved).pathname !== expectedPath) {
        problems.push(`${name}: resolved tarball path does not match package and version (expected ${expectedPath})`);
      }
    }
  }
  if (typeof entry.integrity !== 'string' || entry.integrity === '') {
    problems.push(`${name}: no "integrity" hash`);
  }
}
if (checked === 0) problems.push('the lockfile lists no packages');

if (problems.length > 0) {
  console.error(`check-lockfile: ${problems.length} problem(s) in ${lockPath}`);
  for (const problem of problems) console.error(`  - ${problem}`);
  process.exit(1);
}
console.log(`check-lockfile: ${checked} packages, all from ${REGISTRY} with integrity hashes`);
