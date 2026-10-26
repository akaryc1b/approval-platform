import test from 'node:test';
import assert from 'node:assert/strict';
import { slaDurationText, slaTransitionVersion, slaVersionIsImmutable } from '../../apps/web/overlay/apps/web-ele/src/views/approval/sla-shared/version-selection.mjs';

test('publish and activate target configured v2 rather than previously active v1', () => {
  assert.equal(slaTransitionVersion('calendar-a', 1, 'calendar-a', 2), 2);
  assert.equal(slaTransitionVersion('policy-a', 1, 'policy-a', 2), 2);
});
test('an unrelated row never inherits the editor version', () => {
  assert.equal(slaTransitionVersion('calendar-b', 3, 'calendar-a', 2), 3);
  assert.equal(slaTransitionVersion('policy-b', undefined, 'policy-a', 9), 1);
});
test('published v1 is immutable but does not prevent drafting v2', () => {
  assert.equal(slaVersionIsImmutable({ immutable: true, calendarVersion: 1 }, 1), true);
  assert.equal(slaVersionIsImmutable({ immutable: true, calendarVersion: 1 }, 2), false);
  assert.equal(slaVersionIsImmutable({ immutable: true, policyVersion: 1 }, 1), true);
  assert.equal(slaVersionIsImmutable({ immutable: true, policyVersion: 1 }, 2), false);
  assert.equal(slaVersionIsImmutable({ immutable: false, calendarVersion: 2 }, 2), false);
  assert.equal(slaVersionIsImmutable(undefined, 1), false);
});
test('invalid transition versions fail closed', () => {
  for (const version of [0, -1, 1.5, NaN, Infinity]) {
    assert.throws(() => slaTransitionVersion('a', 1, 'a', version), /positive integer/);
  }
});

test('published policy numeric durations remain valid ISO editor values when copied to v2', () => {
  assert.equal(slaDurationText(28800), 'PT28800S');
  assert.equal(slaDurationText(0), 'PT0S');
  assert.equal(slaDurationText(1.25), 'PT1.25S');
  assert.equal(slaDurationText('PT8H'), 'PT8H');
  assert.equal(slaDurationText(undefined), '');
  assert.equal(slaDurationText(null), '');
});

import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
for (const module of ['sla-calendars', 'sla-policies']) {
  for (const dismissal of ['cancel', 'close']) {
    test(`${module}: ${dismissal} of publish/activate is a no-op, not an unhandled error`, async () => {
      const source = readFileSync(new URL(`../../apps/web/overlay/apps/web-ele/src/views/approval/${module}/index.vue`, import.meta.url), 'utf8');
      const start = source.indexOf('async function transition(');
      const end = source.indexOf('\n}\n', start) + 2;
      const transition = runInNewContext(`${stripTypeScriptTypes(source.slice(start, end))}; transition`, {
        ElMessageBox: { prompt: () => Promise.reject(dismissal) },
        ElMessage: { error: () => assert.fail('dismissal must not show error') },
      });
      await assert.doesNotReject(() => transition({}, 'publish'));
      await assert.doesNotReject(() => transition({}, 'activate'));
    });
  }
}
