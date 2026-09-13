import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

// Fixed, source-controlled resources only. No request value becomes a path or HTML.
export const evaluationPage = readFileSync(new URL('./page/index.html', import.meta.url), 'utf8');
const script = readFileSync(new URL('./page/entry.mjs', import.meta.url), 'utf8');
const style = readFileSync(new URL('./page/entry.css', import.meta.url), 'utf8');
const integrity = source => 'sha256-' + createHash('sha256').update(source).digest('base64');
for (const source of [script, style]) {
  if (!evaluationPage.includes('integrity="' + integrity(source) + '"')) {
    throw new Error('evaluation page asset integrity mismatch');
  }
}

export const evaluationCsp = "default-src 'none'; script-src '" + integrity(script)
  + "'; script-src-attr 'none'; style-src 'self'; style-src-attr 'none'; connect-src 'self';"
  + " base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

const scriptResource = Object.freeze({ type: 'text/javascript; charset=utf-8', body: script });
const styleResource = Object.freeze({ type: 'text/css; charset=utf-8', body: style });
export function evaluationAsset(path) {
  if (path === '/evaluation/assets/entry.mjs') return scriptResource;
  if (path === '/evaluation/assets/entry.css') return styleResource;
  return null;
}
