export const registeredComponentTypes = new Set([
  'ATTACHMENT', 'BOOLEAN', 'BUSINESS_REFERENCE', 'DATE', 'DATETIME',
  'DEPARTMENT_SELECTOR', 'MONEY', 'NUMBER', 'SELECT', 'TEXT', 'TEXTAREA',
  'USER_SELECTOR',
]);

export function effectiveFieldAccess(baseAccess, readonlySummary = false) {
  if (baseAccess === 'HIDDEN' || baseAccess === 'READONLY') return baseAccess;
  return readonlySummary ? 'READONLY' : 'EDITABLE';
}

export function resolveComponent(fieldType, component) {
  const componentType = component?.componentType || fieldType;
  const componentVersion = component?.componentVersion || 1;
  return {
    componentType,
    componentVersion,
    registered: componentVersion === 1 && registeredComponentTypes.has(componentType),
    fallbackRenderer: component?.fallbackRenderer || 'READONLY_TEXT',
  };
}

export function flattenVisibleSections(sections, values = {}, depth = 0) {
  const ordered = sections.some(section => section.order != null)
    ? [...sections].sort((left, right) => (left.order ?? 0) - (right.order ?? 0))
    : sections;
  const result = [];
  for (const section of ordered) {
    const visibility = section.visibility || { mode: 'ALWAYS' };
    const current = visibility.fieldKey ? values[visibility.fieldKey] : undefined;
    const visible = visibility.mode === 'ALWAYS'
      || visibility.mode === 'FIELD_NOT_EMPTY'
        && current != null && current !== '' && (!Array.isArray(current) || current.length > 0)
      || visibility.mode === 'FIELD_EQUALS'
        && JSON.stringify(current) === JSON.stringify(visibility.expectedValue);
    if (!visible) continue;
    result.push({ depth, section });
    result.push(...flattenVisibleSections(section.children || [], values, depth + 1));
  }
  return result;
}
