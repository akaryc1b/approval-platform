import test from 'node:test';
import assert from 'node:assert/strict';
import {
  collectFormFieldOrder,
  countSectionFields,
  findFormSection,
  flattenFormSections,
  moveFormSection,
  normalizeFormSections,
  removeFormSection,
} from '../../apps/web/overlay/apps/web-ele/src/views/approval/forms/form-designer-sections.mjs';

function section(key, fields = [], children = []) {
  return { key, title: key, collapsed: false, fields, children };
}

test('normalizes and flattens nested sections deterministically', () => {
  const sections = [section('root', [{ fieldKey: 'a' }], [section('child', [{ fieldKey: 'b' }])])];
  normalizeFormSections(sections);
  assert.deepEqual(flattenFormSections(sections).map(entry => [entry.section.key, entry.depth]), [
    ['root', 0],
    ['child', 1],
  ]);
  assert.equal(sections[0].order, 0);
  assert.equal(sections[0].columns, 1);
  assert.deepEqual(sections[0].visibility, { mode: 'ALWAYS' });
  assert.deepEqual(collectFormFieldOrder(sections), ['a', 'b']);
  assert.equal(countSectionFields(sections[0]), 2);
});

test('moves and removes sections within the correct sibling container', () => {
  const sections = [section('root', [], [section('first'), section('second')])];
  normalizeFormSections(sections);
  assert.equal(moveFormSection(sections, 'second', -1), true);
  assert.deepEqual(sections[0].children.map(item => item.key), ['second', 'first']);
  assert.equal(findFormSection(sections, 'second').parent.key, 'root');
  assert.equal(removeFormSection(sections, 'second'), true);
  assert.deepEqual(sections[0].children.map(item => item.key), ['first']);
});
