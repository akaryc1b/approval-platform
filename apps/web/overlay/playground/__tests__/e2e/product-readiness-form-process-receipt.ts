/** Closed synthetic artifact receipt; never serialize arbitrary API responses. */
export function formProcessReceiptMarkdown(value: unknown): string {
  const keys = ['formKey', 'formDraftId', 'processDraftId', 'definitionKey', 'packageVersion',
    'formHash', 'uiSchemaHash', 'packageHash'] as const;
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || Object.getPrototypeOf(value) !== Object.prototype
    || Reflect.ownKeys(value).length !== keys.length
    || !keys.every(key => Object.hasOwn(value, key))) throw new Error('Invalid form/process receipt');
  const data = value as Record<typeof keys[number], unknown>;
  // Values must be data properties, never arbitrary getters or inherited values.
  if (!keys.every(key => Object.hasOwn(Object.getOwnPropertyDescriptor(value, key)!, 'value')))
    throw new Error('Invalid form/process receipt');
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
  const syntheticKey = /^browser-form-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
  if (typeof data.formKey !== 'string' || !syntheticKey.test(data.formKey)
    || data.definitionKey !== data.formKey || data.packageVersion !== 1
    || !['formDraftId', 'processDraftId'].every(key =>
      typeof data[key as keyof typeof data] === 'string' && uuid.test(data[key as keyof typeof data] as string))
    || !['formHash', 'uiSchemaHash', 'packageHash'].every(key =>
      typeof data[key as keyof typeof data] === 'string' && /^[0-9a-f]{64}$/.test(data[key as keyof typeof data] as string)))
    throw new Error('Invalid form/process receipt');
  return '# Isolated form/process artifact receipt\n\n'
    + keys.map(key => `- ${key}: ${data[key]}`).join('\n') + '\n';
}
