const SENSITIVE_KEY = /(authorization|credential|password|secret|signature|token)/iu;

export function redact(value) {
  if (Array.isArray(value)) {
    return value.map(redact);
  }
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, fieldValue]) => [
        key,
        SENSITIVE_KEY.test(key) ? '[REDACTED]' : redact(fieldValue),
      ]),
    );
  }
  return value;
}

export function safeErrorMessage(error, sensitiveValues = []) {
  let message = error instanceof Error ? error.message : String(error);
  for (const sensitiveValue of sensitiveValues) {
    if (typeof sensitiveValue === 'string' && sensitiveValue.length > 0) {
      message = message.split(sensitiveValue).join('[REDACTED]');
    }
  }
  return message.slice(0, 2_000);
}
