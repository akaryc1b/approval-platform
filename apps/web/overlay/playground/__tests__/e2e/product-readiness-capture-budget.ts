/** A single monotonic deadline. Local caps can only shorten it, never restart it. */
export interface CaptureBudget {
  remaining: (cap?: number) => number;
  limit: (cap: number) => CaptureBudget;
  run: <T>(operation: (timeout: number) => Promise<T>, cap?: number) => Promise<T>;
}

function positive(value: number) {
  if (!Number.isFinite(value) || value <= 0) {
    throw new Error('Capture deadline/cap must have a finite positive remainder');
  }
  return value;
}

function atDeadline(deadline: number, now: () => number): CaptureBudget {
  const remaining = (cap?: number) => {
    if (cap !== undefined) positive(cap);
    const value = Math.floor(Math.min(cap ?? Number.MAX_SAFE_INTEGER, deadline - now()));
    // Playwright interprets timeout=0 as unlimited. Never pass it downstream.
    if (value <= 0) throw new Error('Capture deadline expired');
    return value;
  };
  return {
    remaining,
    limit(cap) {
      positive(cap);
      const child = atDeadline(Math.min(deadline, now() + cap), now);
      child.remaining();
      return child;
    },
    async run(operation, cap) {
      if (cap !== undefined) positive(cap);
      const operationBudget = atDeadline(Math.min(deadline, now() + (cap ?? Number.MAX_SAFE_INTEGER)), now);
      const timeout = operationBudget.remaining();
      let timer: ReturnType<typeof setTimeout> | undefined;
      try {
        const expired = new Promise<never>((_, reject) => {
          timer = setTimeout(() => reject(new Error('Capture operation exceeded its deadline')), timeout);
        });
        const result = await Promise.race([operation(timeout), expired]);
        // A delayed event loop must not let a late resolution beat the timer.
        operationBudget.remaining();
        return result;
      } finally {
        if (timer !== undefined) clearTimeout(timer);
      }
    },
  };
}

export function captureBudget(
  duration: number,
  now: () => number = () => performance.now(),
): CaptureBudget {
  return atDeadline(now() + positive(duration), now);
}

export function testCaptureBudget(
  info: { timeout: number },
  startedAt: number,
  stageDeadline = process.env.APPROVAL_DEMO_CAPTURE_DEADLINE_EPOCH_MS,
): CaptureBudget {
  const wallNow = Date.now();
  const monotonicNow = performance.now();
  // The spec records this before test setup; public TestInfo has no startTime.
  const testRemaining = startedAt + info.timeout - monotonicNow;
  const stageRemaining = stageDeadline === undefined
    ? testRemaining
    : Number(stageDeadline) - wallNow;
  return atDeadline(monotonicNow + positive(Math.min(testRemaining, stageRemaining)), () => performance.now());
}
