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
