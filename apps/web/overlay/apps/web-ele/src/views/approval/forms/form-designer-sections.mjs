export function normalizeFormSections(sections) {
  const visit = (items) => {
    items.forEach((section, index) => {
      section.order = index;
      section.columns ??= 1;
      section.collapsible ??= true;
      section.readonlySummary ??= false;
      section.visibility ??= { mode: 'ALWAYS' };
      section.children ??= [];
      section.fields ??= [];
      visit(section.children);
    });
  };
  visit(sections);
  return sections;
}

export function flattenFormSections(sections, depth = 0, parent = undefined) {
  const result = [];
  sections.forEach((section, index) => {
    result.push({ section, depth, parent, siblings: sections, index });
    result.push(...flattenFormSections(section.children || [], depth + 1, section));
  });
  return result;
}

export function findFormSection(sections, key) {
  return flattenFormSections(sections).find(entry => entry.section.key === key);
}

export function moveFormSection(sections, key, direction) {
  const entry = findFormSection(sections, key);
  if (!entry) return false;
  const target = entry.index + direction;
  if (target < 0 || target >= entry.siblings.length) return false;
  const [section] = entry.siblings.splice(entry.index, 1);
  entry.siblings.splice(target, 0, section);
  normalizeFormSections(sections);
  return true;
}

export function removeFormSection(sections, key) {
  const entry = findFormSection(sections, key);
  if (!entry) return false;
  entry.siblings.splice(entry.index, 1);
  normalizeFormSections(sections);
  return true;
}

export function collectFormFieldOrder(sections) {
  return flattenFormSections(sections)
    .flatMap(entry => entry.section.fields.map(layout => layout.fieldKey));
}

export function countSectionFields(section) {
  return section.fields.length
    + (section.children || []).reduce((count, child) => count + countSectionFields(child), 0);
}
