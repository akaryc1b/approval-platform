// Reuse only deterministic owner-report parsing, never source reads, evidence
// validation or admission. Exact input strings are the keys (not paths/hashes).
export function memoizePluginOwnerParser(parse) {
  const cache = new Map();
  const maxEntries = 8, maxBytes = 2 * 1024 * 1024;
  return function parseReport(raw) {
    if (typeof raw !== 'string' || Buffer.byteLength(raw) > maxBytes) return parse(raw);
    if (cache.has(raw)) return structuredClone(cache.get(raw));
    const result = parse(raw); // A refusal is never cached.
    if (cache.size >= maxEntries) cache.delete(cache.keys().next().value);
    cache.set(raw, structuredClone(result));
    return result;
  };
}
