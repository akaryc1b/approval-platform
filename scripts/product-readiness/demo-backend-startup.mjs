export const seedMarker = 'PURCHASE_PAYMENT_DEMO_SEED_APPLIED';
export const restoreMarker = 'PURCHASE_PAYMENT_DEMO_RESTORE_ONLY_STARTED';

const modes = Object.freeze({
  seed: Object.freeze({
    seedEnabled: 'true',
    applicationMarker: seedMarker,
    successMarkers: Object.freeze([
      'DEMO_BACKEND_ONE_COMMAND_STARTED',
      'BACKEND_LOCAL_START_VERIFIED',
    ]),
  }),
  'restore-only': Object.freeze({
    seedEnabled: 'false',
    applicationMarker: restoreMarker,
    successMarkers: Object.freeze([
      'DEMO_BACKEND_RESTORE_ONLY_STARTED',
      'BACKEND_LOCAL_RESTORE_ONLY_STARTED',
    ]),
  }),
});

export function startupMode(command) {
  if (command === 'start' || command === 'plan') return 'seed';
  if (command === 'start-restored' || command === 'plan-restored') return 'restore-only';
  throw new Error(`Unknown demo backend startup command: ${command}`);
}

export function startupContract(mode) {
  if (typeof mode !== 'string' || !Object.hasOwn(modes, mode)) {
    throw new Error(`Unknown demo backend startup mode: ${mode}`);
  }
  return modes[mode];
}

export function backendEnvironment(mode, inherited, database) {
  const contract = startupContract(mode);
  return {
    ...inherited,
    ...database,
    APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED: 'true',
    APPROVAL_DEMO_PURCHASE_PAYMENT_SEED_ENABLED: contract.seedEnabled,
  };
}

export function backendApplicationArguments(mode) {
  const contract = startupContract(mode);
  // Command-line properties also override inherited SPRING_APPLICATION_JSON.
  return '--approval.demo.purchase-payment.enabled=true '
    + `--approval.demo.purchase-payment.seed.enabled=${contract.seedEnabled}`;
}

export function backendReady(mode, healthReady, state) {
  startupContract(mode);
  if (mode === 'restore-only' && state.seedLine) {
    throw new Error('Restore-only startup unexpectedly executed the deterministic seed');
  }
  if (mode === 'seed' && state.restoreLine) {
    throw new Error('Seed startup unexpectedly entered restore-only mode');
  }
  return Boolean(healthReady && (mode === 'seed' ? state.seedLine : state.restoreLine));
}

export function observeBackendOutput(mode, state, text) {
  startupContract(mode);
  // Inspect the complete current chunk before bounding retained history. This also
  // detects a marker split over stream chunks or followed by a large log burst.
  const output = `${state.recentOutput}${text}`;
  const seed = output.match(new RegExp(`${seedMarker}[^\\r\\n]*`, 'u'));
  const restored = output.match(new RegExp(`${restoreMarker}[^\\r\\n]*`, 'u'));
  if (seed) state.seedLine = seed[0];
  if (restored) state.restoreLine = restored[0];
  state.recentOutput = output.slice(-65_536);
  backendReady(mode, false, state);
}
