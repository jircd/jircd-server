// Remark plugin: rewrites relative links to other guides (`../admin/foo.md#bar`)
// into site URLs (`/admin/foo/#bar`) and fails the build when the target file
// does not exist. Guides keep relative `.md` links so they work on GitHub too.
import { existsSync } from 'node:fs';
import { dirname, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const docsRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../content/docs');
const MD_LINK = /^(?![a-z][a-z0-9+.-]*:|\/|#)([^?#]*\.mdx?)((?:[?#].*)?)$/i;

function visit(node, callback) {
  callback(node);
  if (Array.isArray(node.children)) {
    for (const child of node.children) visit(child, callback);
  }
}

export default function rewriteMdLinks() {
  return (tree, file) => {
    const source = file.path;
    if (!source || !resolve(source).startsWith(docsRoot + sep)) return;
    visit(tree, (node) => {
      if (node.type !== 'link' && node.type !== 'definition') return;
      const match = MD_LINK.exec(node.url ?? '');
      if (!match) return;
      const [, path, suffix] = match;
      const target = resolve(dirname(source), path);
      const targetSegments = relative(docsRoot, target).split(sep);
      const lastSegment = targetSegments.length - 1;
      targetSegments[lastSegment] = targetSegments[lastSegment].replace(/\.mdx?$/i, '');
      const invalidSegment = targetSegments.find((segment) => !/^[A-Za-z0-9_-]+$/.test(segment));
      if (invalidSegment) {
        throw new Error(
          `${relative(docsRoot, source)}: link "${node.url}" has target segment ${JSON.stringify(invalidSegment)}; guide filename segments must use only letters, numbers, underscores, and hyphens so Starlight's github-slugger preserves the route`,
        );
      }
      if (!target.startsWith(docsRoot + sep) || !existsSync(target)) {
        throw new Error(
          `${relative(docsRoot, source)}: link "${node.url}" points to a missing guide (${relative(docsRoot, target)})`,
        );
      }
      let route = relative(docsRoot, target)
        .split(sep)
        .join('/')
        .replace(/\.mdx?$/i, '')
        .split('/')
        .map((segment) => segment.toLowerCase())
        .join('/');
      if (route === 'index') route = '';
      else if (route.endsWith('/index')) route = route.slice(0, -'index'.length);
      node.url = `/${route}${route && !route.endsWith('/') ? '/' : ''}${suffix}`;
    });
  };
}
