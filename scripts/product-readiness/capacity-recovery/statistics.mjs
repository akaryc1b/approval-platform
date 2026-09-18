function requireFinite(value, label) {
  if (!Number.isFinite(value)) {
    throw new Error(`${label} must be finite`);
  }
  return value;
}

function rounded(value) {
  return Number(value.toFixed(3));
}

export function percentile(values, quantile) {
  if (!Array.isArray(values) || values.length === 0) {
    throw new Error('percentile requires at least one value');
  }
  requireFinite(quantile, 'quantile');
  if (quantile < 0 || quantile > 1) {
    throw new Error('quantile must be between 0 and 1');
  }
  const sorted = values.map((value, index) =>
    requireFinite(value, `values[${index}]`)).sort((left, right) => left - right);
  if (sorted.length === 1) return sorted[0];
  const position = quantile * (sorted.length - 1);
  const lowerIndex = Math.floor(position);
  const upperIndex = Math.ceil(position);
  const lower = sorted[lowerIndex];
  const upper = sorted[upperIndex];
  if (lowerIndex === upperIndex) return lower;
  return lower + (upper - lower) * (position - lowerIndex);
}

export function summarizeSamples(samples, elapsedMs) {
  if (!Array.isArray(samples) || samples.length === 0) {
    throw new Error('samples must contain at least one observation');
  }
  requireFinite(elapsedMs, 'elapsedMs');
  if (elapsedMs <= 0) throw new Error('elapsedMs must be positive');
  const successful = samples.filter(sample => sample.ok === true);
  const failed = samples.filter(sample => sample.ok !== true);
  const latencies = successful.map((sample, index) =>
    requireFinite(sample.latencyMs, `successful[${index}].latencyMs`));
  if (latencies.length === 0) {
    throw new Error('at least one successful sample is required');
  }
  return {
    requests: samples.length,
    successful: successful.length,
    failed: failed.length,
    errorRate: rounded(failed.length / samples.length),
    elapsedMs: rounded(elapsedMs),
    throughputPerSecond: rounded(successful.length / (elapsedMs / 1000)),
    latencyMs: {
      minimum: rounded(Math.min(...latencies)),
      p50: rounded(percentile(latencies, 0.5)),
      p95: rounded(percentile(latencies, 0.95)),
      p99: rounded(percentile(latencies, 0.99)),
      maximum: rounded(Math.max(...latencies)),
    },
  };
}

export function groupSamples(samples) {
  const groups = new Map();
  for (const sample of samples) {
    const operation = String(sample.operation || '').trim();
    if (!operation) throw new Error('sample operation is required');
    if (!groups.has(operation)) groups.set(operation, []);
    groups.get(operation).push(sample);
  }
  return groups;
}

export function summarizeByOperation(samples) {
  const summaries = {};
  for (const [operation, values] of groupSamples(samples)) {
    const first = Math.min(...values.map(value => value.startedEpochMs));
    const last = Math.max(...values.map(value => value.completedEpochMs));
    summaries[operation] = summarizeSamples(values, Math.max(1, last - first));
  }
  return summaries;
}

export async function runBoundedPool(items, concurrency, worker) {
  if (!Array.isArray(items)) throw new Error('items must be an array');
  if (items.length === 0) return [];
  if (!Number.isInteger(concurrency)
      || concurrency < 1
      || concurrency > items.length) {
    throw new Error('concurrency must be within the item count');
  }
  if (typeof worker !== 'function') throw new Error('worker must be a function');
  const results = new Array(items.length);
  let cursor = 0;
  async function consume() {
    while (cursor < items.length) {
      const index = cursor;
      cursor += 1;
      if (index >= items.length) return;
      results[index] = await worker(items[index], index);
    }
  }
  await Promise.all(
    Array.from(
      { length: Math.min(concurrency, items.length) },
      () => consume(),
    ),
  );
  return results;
}
