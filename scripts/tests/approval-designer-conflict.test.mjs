import assert from 'node:assert/strict';
import test from 'node:test';
import {
  analyzeDesignerConflict,
  diffDesignerValues,
  mergeDesignerConflict,
} from '../../apps/web/overlay/apps/web-ele/src/views/approval/designer/designer-conflict.ts';

function snapshot() {
  return {
    definition: {
      definitionKey: 'purchase',
      name: 'Purchase',
      nodes: [{ id: 'start', kind: 'START', name: 'Start', next: 'end' }],
      schemaVersion: '1.0',
      startNodeId: 'start',
      version: 1,
    },
    formPackageVersion: 1,
    name: 'Purchase draft',
  };
}

const marker = 'approvalDesignerPollutionProbe';
const unsafeError = /设计器快照包含不安全的属性名/;

test('rejects the JSON __proto__ merge probe without changing Object.prototype', () => {
  const base = { definition: {}, formPackageVersion: 1, name: 'base' };
  const local = JSON.parse(`{"definition":{"__proto__":{"${marker}":"benign-probe"}},"formPackageVersion":1,"name":"base"}`);
  try {
    assert.throws(() => mergeDesignerConflict(base, local, structuredClone(base)), unsafeError);
    assert.equal(Object.hasOwn(Object.prototype, marker), false);
    assert.equal(({})[marker], undefined);
  } finally {
    delete Object.prototype[marker];
  }
});

for (const token of ['__proto__', 'constructor', 'prototype']) {
  for (const placement of ['root', 'nested', 'array', 'replacement']) {
    test(`rejects ${token} in ${placement} values from every merge input`, () => {
      const payload = JSON.parse(`{"${token}":{"${marker}":"benign-probe"}}`);
      const unsafe = snapshot();
      if (placement === 'root') {
        Object.defineProperty(unsafe, token, {
          enumerable: true,
          value: payload[token],
        });
      } else if (placement === 'nested') {
        unsafe.definition = payload;
      } else if (placement === 'array') {
        unsafe.definition.nodes = [payload];
      } else {
        unsafe.definition.extra = { replacement: payload };
      }
      const original = structuredClone(unsafe);
      try {
        for (let position = 0; position < 3; position += 1) {
          const inputs = [snapshot(), snapshot(), snapshot()];
          inputs[position] = unsafe;
          assert.throws(() => analyzeDesignerConflict(...inputs), unsafeError);
          assert.throws(() => mergeDesignerConflict(...inputs), unsafeError);
        }
        assert.throws(() => diffDesignerValues(unsafe, unsafe), unsafeError);
        assert.throws(() => diffDesignerValues(undefined, unsafe), unsafeError);
        assert.throws(() => diffDesignerValues(unsafe, undefined), unsafeError);
        assert.equal(Object.hasOwn(Object.prototype, marker), false);
        assert.equal(Object.hasOwn(Array.prototype, marker), false);
        assert.equal(Object.hasOwn(Object, marker), false);
        assert.equal(({})[marker], undefined);
        assert.deepEqual(unsafe, original);
      } finally {
        // Keep a regressed implementation from contaminating later tests.
        delete Object.prototype[marker];
        delete Array.prototype[marker];
        delete Object[marker];
      }
    });
  }

  test(`rejects an explicit ${token} pointer before equality or recursion`, () => {
    assert.throws(() => diffDesignerValues({}, {}, `/definition/${token}/value`), unsafeError);
  });
}

test('validates own array properties as well as array elements', () => {
  const values = [];
  Object.defineProperty(values, '__proto__', { enumerable: true, value: {} });
  assert.throws(() => diffDesignerValues([], values), unsafeError);
});

test('merges ordinary independent business changes without mutating snapshots', () => {
  const base = snapshot();
  const local = structuredClone(base);
  const server = structuredClone(base);
  local.name = 'Updated purchase draft';
  local.definition.name = 'Updated purchase';
  local.definition.nodes.push({ id: 'end', kind: 'END', name: 'End' });
  server.formPackageVersion = 2;
  server.definition.version = 2;
  const originals = structuredClone([base, local, server]);
  const merged = mergeDesignerConflict(base, local, server);
  assert.deepEqual(merged, {
    ...local,
    definition: { ...local.definition, version: 2 },
    formPackageVersion: 2,
  });
  assert.deepEqual([base, local, server], originals);
  merged.definition.nodes[0].name = 'Merged only';
  assert.equal(local.definition.nodes[0].name, 'Start');
  assert.equal(server.definition.nodes[0].name, 'Start');
});

test('preserves additions, removals, nulls, and escaped pointer tokens', () => {
  const base = snapshot();
  base.definition.metadata = { remove: 'old', nullable: 'old', 'a/b~c': 1 };
  const local = structuredClone(base);
  delete local.definition.metadata.remove;
  local.definition.metadata.nullable = null;
  local.definition.metadata['a/b~c'] = 2;
  local.definition.metadata[''] = 'empty key';
  local.definition.metadata['__proto__/child'] = 'literal slash';
  local.definition.metadata['~1constructor'] = 'literal tilde';
  local.definition.metadata.description = '__proto__ constructor prototype';
  const changes = diffDesignerValues(base, local);
  assert.equal(changes.find(change => change.path.endsWith('/remove')).type, 'REMOVED');
  assert.ok(changes.some(change => change.path === '/definition/metadata/a~1b~0c'));
  assert.ok(changes.some(change => change.path === '/definition/metadata/__proto__~1child'));
  assert.ok(changes.some(change => change.path === '/definition/metadata/~01constructor'));
  assert.deepEqual(mergeDesignerConflict(base, local, structuredClone(base)), local);
});

test('keeps overlapping paths and atomic array changes blocked', () => {
  const base = snapshot();
  const local = structuredClone(base);
  const server = structuredClone(base);
  local.definition.nodes[0].name = 'Local start';
  server.definition.nodes[0].next = 'server-end';
  assert.deepEqual(analyzeDesignerConflict(base, local, server).overlappingPaths, ['/definition/nodes']);
  assert.throws(() => mergeDesignerConflict(base, local, server), /存在重叠修改/);

  local.definition = { name: 'Local replacement' };
  server.definition = null;
  assert.equal(analyzeDesignerConflict(base, local, server).canAutoMerge, false);
  assert.throws(() => mergeDesignerConflict(base, local, server), /存在重叠修改/);
});

test('treats inherited names as absent and never invokes inherited setters', () => {
  const key = 'approvalDesignerInheritedProbe';
  const inherited = { untouched: true };
  let setterCalls = 0;
  Object.defineProperty(Object.prototype, key, {
    configurable: true,
    get: () => inherited,
    set: () => { setterCalls += 1; },
  });
  try {
    const base = snapshot();
    const local = structuredClone(base);
    const server = structuredClone(base);
    Object.defineProperty(local.definition, key, {
      configurable: true,
      enumerable: true,
      value: { local: true },
      writable: true,
    });
    assert.deepEqual(diffDesignerValues(base, local), [{
      after: { local: true },
      before: undefined,
      path: `/definition/${key}`,
      type: 'ADDED',
    }]);
    const merged = mergeDesignerConflict(base, local, server);
    assert.equal(Object.hasOwn(merged.definition, key), true);
    assert.deepEqual(merged.definition[key], { local: true });
    assert.deepEqual(inherited, { untouched: true });
    assert.equal(setterCalls, 0);
    const removed = mergeDesignerConflict(local, base, structuredClone(local));
    assert.equal(Object.hasOwn(removed.definition, key), false);
    assert.equal(setterCalls, 0);
  } finally {
    delete Object.prototype[key];
  }
});

test('supports null-prototype records and unchanged snapshots', () => {
  const base = snapshot();
  base.definition.metadata = Object.assign(Object.create(null), { safe: 1 });
  const local = structuredClone(base);
  local.definition.metadata.safe = 2;
  assert.deepEqual(mergeDesignerConflict(base, local, structuredClone(base)), local);
  assert.deepEqual(diffDesignerValues(base, base), []);
  assert.equal(analyzeDesignerConflict(base, base, base).canAutoMerge, true);
});
