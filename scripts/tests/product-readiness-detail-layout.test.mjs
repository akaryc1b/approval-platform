import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { resolve } from 'node:path';
import test from 'node:test';
import { runInNewContext } from 'node:vm';

import { selectedFiles } from '../product-readiness/capacity-recovery/ci-scope.mjs';
import { relevantChangeSet } from '../product-readiness/pc-h5-runtime/ci-scope.mjs';

const layoutPaths = [
  'apps/web/overlay/playground/__tests__/e2e/product-readiness-detail-layout.ts',
  'scripts/tests/product-readiness-detail-layout.test.mjs',
];

for (const path of layoutPaths) {
  test(`layout-only change independently selects native browser verification: ${path}`, () => {
    assert.equal(relevantChangeSet([path]), true);
  });
}

test('layout selection remains exact and excludes unrelated or lookalike paths', () => {
  const unrelated = [
    'docs/unrelated-notes.md',
    'apps/web/overlay/playground/__tests__/unit/product-readiness-detail-layout.ts',
    'apps/web/overlay/playground/__tests__/e2e/product-readiness-detail-layout.tsx',
    'apps/web/overlay/playground/__tests__/e2e/product-readiness-detail-layout-extra.ts',
    'scripts/tests/product-readiness-detail-layout.test.py',
    'scripts/tests/product-readiness-detail-layout-extra.test.mjs',
    ...layoutPaths.flatMap(path => [`copy/${path}`, `${path}.bak`, `${path}/nested`]),
  ];
  assert.equal(relevantChangeSet([]), false);
  for (const path of unrelated) assert.equal(relevantChangeSet([path]), false, path);
  assert.equal(relevantChangeSet(unrelated), false);
  for (const path of layoutPaths) assert.equal(relevantChangeSet([...unrelated, path]), true, path);
});

test('layout helper, geometry regression and shared selector changes do not select capacity execution', () => {
  assert.deepEqual(selectedFiles([
    ...layoutPaths,
    'scripts/product-readiness/pc-h5-runtime/ci-scope.mjs',
  ]), []);
  const capacityConfig = 'config/demo/capacity-recovery.json';
  for (const path of layoutPaths) {
    assert.deepEqual(selectedFiles([path]), []);
    assert.deepEqual(selectedFiles([path, capacityConfig]), [capacityConfig]);
  }
});

const root = resolve(import.meta.dirname, '../..');
const source = readFileSync(resolve(root,
  'apps/web/overlay/playground/__tests__/e2e/product-readiness-detail-layout.ts'), 'utf8');
const compiled = stripTypeScriptTypes(source.replace(/^import type .*\n/gmu, '').replace(/^export /gmu, ''));
const { detailLayoutViolations, footerLayoutViolations } = runInNewContext(
  `${compiled}\n({ detailLayoutViolations, footerLayoutViolations })`,
);

function detail() {
  return {
    viewportWidth: 390, documentWidth: 390, contentLeft: 12, contentRight: 378,
    boundaries: [{ category: '.snapshot-grid > uni-view', index: 1,
      left: 200, right: 360, clientWidth: 160, scrollWidth: 160,
      textFragments: 24, textOverflow: 0 }],
  };
}

function footer(safeArea = 0) {
  return {
    viewportWidth: 390, viewportHeight: 844,
    footer: { left: 0, right: 390, top: 779 - safeArea, bottom: 844, height: 65 + safeArea },
    buttons: Array.from({ length: 4 }, (_, index) => ({
      left: 12 + index * 92, right: 96 + index * 92,
      top: 789 - safeArea, bottom: 833 - safeArea, width: 84, height: 44,
    })),
    bottomPadding: 94 + safeArea, lastContentBottom: 749 - safeArea,
  };
}

test('detail regression rejects both retained 390px H5 overflow widths', () => {
  assert.equal(detailLayoutViolations(detail()).length, 0);
  for (const documentWidth of [512, 565]) {
    assert.ok(detailLayoutViolations({ ...detail(), documentWidth }).includes('document-width'));
  }
});

test('a contained panel cannot conceal overflowing long text or a wide nested table', () => {
  for (const change of [{ textOverflow: 120 }, { scrollWidth: 280 }, { right: 510 }]) {
    const fixture = detail();
    Object.assign(fixture.boundaries[0], change);
    assert.ok(detailLayoutViolations(fixture).length > 0);
  }
  const clippedDrawer = detail();
  Object.assign(clippedDrawer, { viewportWidth: 1440, documentWidth: 1440, contentLeft: 740, contentRight: 1420 });
  Object.assign(clippedDrawer.boundaries[0], { category: '.el-descriptions__cell', left: 1200, right: 1490 });
  assert.ok(detailLayoutViolations(clippedDrawer).some(value => value.endsWith(':box')));
});

test('layout evidence cannot silently skip text or all governed surfaces', () => {
  assert.ok(detailLayoutViolations({ ...detail(), boundaries: [] }).includes('missing-boundaries'));
  const fixture = detail();
  fixture.boundaries[0].textFragments = 0;
  assert.ok(detailLayoutViolations(fixture).includes('missing-text-fragments'));
});

test('one-pixel geometry tolerance does not admit larger text overflow', () => {
  const fixture = detail();
  fixture.boundaries[0].textOverflow = 1;
  assert.equal(detailLayoutViolations(fixture).length, 0);
  fixture.boundaries[0].textOverflow = 1.01;
  assert.ok(detailLayoutViolations(fixture).some(value => value.endsWith(':text')));
});

test('fixed footer permits reachable final content with and without safe-area space', () => {
  for (const inset of [0, 34]) assert.equal(footerLayoutViolations(footer(inset)).length, 0);
  const fixture = footer(34);
  fixture.bottomPadding = 94;
  assert.ok(footerLayoutViolations(fixture).includes('footer-reserved-space'));
  fixture.lastContentBottom = fixture.footer.top + 2;
  assert.ok(footerLayoutViolations(fixture).includes('last-content-obscured'));
});

test('footer regression rejects original four 120px buttons and overlapping or missing actions', () => {
  const tooWide = footer();
  tooWide.buttons.forEach((button, index) => Object.assign(button, {
    left: 12 + index * 130, right: 132 + index * 130, width: 120,
  }));
  assert.ok(footerLayoutViolations(tooWide).some(value => value.endsWith(':bounds')));
  const overlapping = footer();
  overlapping.buttons[1] = { ...overlapping.buttons[0] };
  assert.ok(footerLayoutViolations(overlapping).some(value => value.endsWith(':overlap')));
  assert.ok(footerLayoutViolations({ ...footer(), buttons: [] }).includes('missing-actions'));
});
