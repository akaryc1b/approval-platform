import test from 'node:test';
import assert from 'node:assert/strict';
import {
  effectiveFieldAccess,
  flattenVisibleSections,
  resolveComponent,
} from '../lib/approval-form-renderer-protocol.mjs';

test('flattens nested sections in deterministic visible order', () => {
  const sections = [
    { key: 'second', order: 1, visibility: { mode: 'FIELD_NOT_EMPTY', fieldKey: 'owner' }, children: [] },
    { key: 'first', order: 0, visibility: { mode: 'ALWAYS' }, children: [
      { key: 'child', order: 0, visibility: { mode: 'FIELD_EQUALS', fieldKey: 'kind', expectedValue: 'A' }, children: [] },
    ] },
  ];
  assert.deepEqual(
    flattenVisibleSections(sections, { kind: 'A', owner: 'user-1' }).map(entry => [entry.section.key, entry.depth]),
    [['first', 0], ['child', 1], ['second', 0]],
  );
  assert.deepEqual(
    flattenVisibleSections(sections, { kind: 'B', owner: '' }).map(entry => entry.section.key),
    ['first'],
  );
});

test('section readonly can only reduce field access', () => {
  assert.equal(effectiveFieldAccess('HIDDEN', false), 'HIDDEN');
  assert.equal(effectiveFieldAccess('READONLY', false), 'READONLY');
  assert.equal(effectiveFieldAccess('EDITABLE', true), 'READONLY');
  assert.equal(effectiveFieldAccess('EDITABLE', false), 'EDITABLE');
});

test('resolves only host-registered versioned components and safe fallback', () => {
  assert.deepEqual(resolveComponent('TEXT', undefined), {
    componentType: 'TEXT', componentVersion: 1, registered: true, fallbackRenderer: 'READONLY_TEXT',
  });
  assert.equal(resolveComponent('TEXT', { componentType: 'USER_SELECTOR', componentVersion: 1 }).registered, true);
  assert.equal(resolveComponent('TEXT', { componentType: 'REMOTE_SCRIPT', componentVersion: 1 }).registered, false);
  assert.equal(resolveComponent('TEXT', { componentType: 'TEXT', componentVersion: 2 }).registered, false);
});

test('actual PC and H5 renderers never promote hidden fields when the form or section is readonly', async () => {
  const { readFileSync } = await import('node:fs');
  const { stripTypeScriptTypes } = await import('node:module');
  const { runInNewContext } = await import('node:vm');
  for (const path of [
    'apps/mobile/overlay/src/components/approval/ApprovalFormRenderer.vue',
    'apps/web/overlay/apps/web-ele/src/components/approval/ApprovalFormRenderer.vue',
  ]) {
    const source = readFileSync(new URL('../../' + path, import.meta.url), 'utf8');
    const functions = ['access', 'visible', 'disabled', 'setValue'].map(name => {
      const start = source.indexOf(`function ${name}(`);
      assert.ok(start >= 0);
      const end = source.indexOf('\nfunction ', start + 1);
      assert.ok(end > start);
      return source.slice(start, end);
    }).join('\n');
    for (const permission of [undefined, 'EDITABLE', 'READONLY', 'HIDDEN']) {
      for (const readonly of [false, true]) for (const readonlySummary of [false, true]) {
        const original = { amount: '9999.99', attachments: ['isolated-existing-id'] };
        const updates = [];
        const props = { readonly, fieldPermissions: permission === undefined ? {} : { attachments: permission }, modelValue: original };
        const api = runInNewContext(stripTypeScriptTypes(functions) + '\n;({access,visible,disabled,setValue})', {
          props, model: { value: original }, emit: (...args) => updates.push(args),
        });
        const item = { key: 'attachments' }, section = { readonlySummary };
        const expected = permission === 'HIDDEN' ? 'HIDDEN'
          : permission === 'READONLY' || readonly || readonlySummary ? 'READONLY' : 'EDITABLE';
        assert.equal(api.access(item, section), expected, `${path}/${permission}/${readonly}/${readonlySummary}`);
        assert.equal(api.visible(item, section), expected !== 'HIDDEN');
        assert.equal(api.visible(undefined, section), false);
        assert.equal(api.disabled(item, section), expected !== 'EDITABLE');
        api.setValue(item, section, ['replacement-id']);
        assert.equal(updates.length, Number(expected === 'EDITABLE'));
        if (updates.length) {
          assert.equal(updates[0][0], 'update:modelValue');
          assert.deepEqual(JSON.parse(JSON.stringify(updates[0][1])), { amount: '9999.99', attachments: ['replacement-id'] });
        }
        assert.deepEqual(original, { amount: '9999.99', attachments: ['isolated-existing-id'] });
      }
    }
  }
});
