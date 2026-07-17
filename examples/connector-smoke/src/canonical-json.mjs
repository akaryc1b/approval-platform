function numberToPlainString(value) {
  if (!Number.isFinite(value)) {
    throw new TypeError('Canonical JSON does not support non-finite numbers.');
  }
  if (Object.is(value, -0)) {
    return '0';
  }

  const source = String(value).toLowerCase();
  if (!source.includes('e')) {
    return source;
  }

  const negative = source.startsWith('-');
  const unsigned = negative ? source.slice(1) : source;
  const [coefficient, exponentSource] = unsigned.split('e');
  const exponent = Number.parseInt(exponentSource, 10);
  const [whole, fraction = ''] = coefficient.split('.');
  const digits = `${whole}${fraction}`;
  const decimalPosition = whole.length + exponent;

  let expanded;
  if (decimalPosition <= 0) {
    expanded = `0.${'0'.repeat(-decimalPosition)}${digits}`;
  } else if (decimalPosition >= digits.length) {
    expanded = `${digits}${'0'.repeat(decimalPosition - digits.length)}`;
  } else {
    expanded = `${digits.slice(0, decimalPosition)}.${digits.slice(decimalPosition)}`;
  }

  if (expanded.includes('.')) {
    expanded = expanded.replace(/0+$/u, '').replace(/\.$/u, '');
  }
  return `${negative ? '-' : ''}${expanded}`;
}

function writeValue(value) {
  if (value === null) {
    return 'null';
  }
  if (typeof value === 'string') {
    return JSON.stringify(value);
  }
  if (typeof value === 'boolean') {
    return value ? 'true' : 'false';
  }
  if (typeof value === 'number') {
    return numberToPlainString(value);
  }
  if (typeof value === 'bigint') {
    return value.toString();
  }
  if (Array.isArray(value)) {
    return `[${value.map(writeValue).join(',')}]`;
  }
  if (typeof value === 'object') {
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) {
      throw new TypeError(`Unsupported canonical JSON object: ${value.constructor?.name ?? 'unknown'}`);
    }
    const fields = Object.keys(value)
      .sort()
      .map((key) => {
        const fieldValue = value[key];
        if (fieldValue === undefined) {
          throw new TypeError(`Canonical JSON field ${key} must not be undefined.`);
        }
        return `${JSON.stringify(key)}:${writeValue(fieldValue)}`;
      });
    return `{${fields.join(',')}}`;
  }
  throw new TypeError(`Unsupported canonical JSON value type: ${typeof value}`);
}

export function canonicalJson(value) {
  return writeValue(value);
}
