/** Bounded, read-only startup diagnostics. Raw container logs never leave this module. */
const signals = Object.freeze([
  ['APPLICATION_START_FAILED', /APPLICATION FAILED TO START/u],
  ['BEAN_CYCLE', /BeanCurrentlyInCreationException|dependencies of some of the beans[^\n]*cycle/u],
  ['MISSING_BEAN', /NoSuchBeanDefinitionException|No qualifying bean of type/u],
  ['AMBIGUOUS_BEAN', /NoUniqueBeanDefinitionException/u],
  ['BEAN_NAME_COLLISION', /BeanDefinitionOverrideException|already a bean bound/u],
  ['CONFIGURATION_BINDING', /ConfigurationPropertiesBindException|BindValidationException/u],
  ['DEPENDENCY_INITIALIZATION', /UnsatisfiedDependencyException|BeanCreationException/u],
  ['DATABASE_CONNECTION', /PSQLException|CannotGetJdbcConnectionException|PoolInitializationException/u],
  ['DATABASE_MIGRATION', /FlywayException|FlywayMigrateException|ScriptStatementFailedException/u],
  ['OUT_OF_MEMORY', /OutOfMemoryError/u],
  ['PORT_IN_USE', /BindException|PortInUseException/u],
  ['SEED_APPLIED', /PURCHASE_PAYMENT_DEMO_SEED_APPLIED/u],
]);
const beanNames = Object.freeze(['onlineEvaluationBusinessIdentity', 'onlineEvaluationDatabaseMigration',
  'onlineEvaluationWorkflowSeedConfiguration', 'purchasePaymentDemoScenario', 'purchasePaymentDemoSeeder',
  'purchasePaymentDemoSeedRunner', 'purchasePaymentDemoPaymentSandbox', 'processEngine',
  'requestMappingHandlerMapping', 'outboxDispatcher']);
const hex = value => typeof value === 'string' && /^[0-9a-f]{64}$/u.test(value) && !/^0+$/u.test(value);
const generation = value => typeof value === 'string' && /^[0-9a-f]{32}$/u.test(value) && !/^0+$/u.test(value);
const base = () => ({ kind: 'EVALUATION_BACKEND_STARTUP_DIAGNOSTIC', inspection: 'UNAVAILABLE',
  logs: 'NOT_READ', state: null, signals: [], beans: [] });

export function classifyEvaluationStartupLog(text) {
  if (typeof text !== 'string' || Buffer.byteLength(text) > 262_144) {
    return { logs: 'UNAVAILABLE', signals: [], beans: [] };
  }
  return { logs: 'CLASSIFIED', signals: signals.filter(([, pattern]) => pattern.test(text)).map(([code]) => code),
    beans: beanNames.filter(name => text.includes("'" + name + "'") || text.includes('"' + name + '"')) };
}

export async function observeEvaluationStartupFailure({ slot, run }) {
  const result = base();
  const target = slot?.containers?.backend;
  if (typeof run !== 'function' || !target || !hex(target.id) || !generation(slot.namespace)
      || !generation(slot.generation) || !['slot-a', 'slot-b'].includes(slot.slotId)
      || target.name !== `ap-evaluation-${slot.namespace}-${slot.slotId}-backend`) return result;
  try {
    const raw = await run(['container', 'inspect', target.id], { timeoutMs: 1500 });
    if (typeof raw !== 'string' || Buffer.byteLength(raw) > 262_144) return result;
    const values = JSON.parse(raw);
    const actual = values?.length === 1 ? values[0] : null;
    const labels = actual?.Config?.Labels;
    const owned = actual?.Id === target.id && actual.Name === '/' + target.name
      && labels?.['io.approval.evaluation.owner'] === slot.namespace
      && labels?.['io.approval.evaluation.slot'] === slot.slotId
      && labels?.['io.approval.evaluation.generation'] === slot.generation
      && labels?.['io.approval.evaluation.role'] === 'backend';
    if (!owned) { result.inspection = 'OWNERSHIP_REJECTED'; return result; }
    result.inspection = 'OWNED';
    const state = actual.State || {};
    result.state = { running: state.Running === true, oomKilled: state.OOMKilled === true,
      restarting: state.Restarting === true,
      exitCode: Number.isSafeInteger(state.ExitCode) && state.ExitCode >= 0 && state.ExitCode <= 255
        ? state.ExitCode : null };
    // No inspect Env, state Error, application messages or log text enters a receipt.
    const text = await run(['logs', '--tail', '160', target.id], { timeoutMs: 1500 });
    Object.assign(result, classifyEvaluationStartupLog(text));
  } catch { result.logs = result.inspection === 'OWNED' ? 'UNAVAILABLE' : 'NOT_READ'; }
  return result;
}
