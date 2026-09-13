const variable = 'APPROVAL_DEMO_CAPACITY_REUSE_BUILD';
let active = false;

/** Scoped to the CI payment runs, never the cold Quick Start or a different SHA. */
export async function withExactBackendBuildReuse(operation) {
  if (typeof operation !== 'function' || active) throw new Error('BUILD_REUSE_SCOPE_INVALID');
  const previous = process.env[variable];
  if (previous !== undefined && previous !== 'true' && previous !== 'false') {
    throw new Error('BUILD_REUSE_SETTING_INVALID');
  }
  active = true;
  process.env[variable] = previous ?? 'true';
  try { return await operation(); }
  finally {
    if (previous === undefined) delete process.env[variable];
    else process.env[variable] = previous;
    active = false;
  }
}
