import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const root = new URL('../../', import.meta.url);
const directory = 'apps/web/overlay/playground/__tests__/e2e/';
const read = path => readFileSync(new URL(path, root), 'utf8');

// Execute the maintained selector expression against a recording Locator contract.
// This checks literal arguments and scoping, not browser DOM rendering.
function scope() {
  const node = expression => ({
    expression,
    locator: selector => node(['locator', expression, selector]),
    getByText: (text, options) => node(['getByText', expression, text, options]),
    and: other => node(['and', expression, other.expression]),
    filter: ({ has }) => node(['filter', expression, has.expression]),
    first: () => node(['first', expression]),
  });
  return node('scope');
}
function helper(file, name) {
  const source = read(directory + file);
  const line = source.split('\n').find(value => value.startsWith(`const ${name} = `));
  assert.ok(line, 'maintained helper must exist');
  const expression = line.slice(line.indexOf('=>') + 2).trim().replace(/;$/, '');
  return new Function('scope', name === 'input' ? 'label' : 'name', `return ${expression};`);
}

for (const file of ['product-readiness-pc-form-package.ts', 'product-readiness-pc-process-design.ts']) {
  test(`${file}: form-item label text is literal, exact, and scoped`, () => {
    const item = helper(file, 'item');
    for (const label of ['显示名称', 'A.*[x](y)$', 'Name "quoted" \\ value']) {
      assert.deepEqual(item(scope(), label).expression, ['filter', ['locator', 'scope', '.el-form-item'],
        ['and', ['getByText', 'scope', label, { exact: true }], ['locator', 'scope', '.el-form-item__label']]]);
    }
  });
}

test('native calendar labels use exact text nodes with JSON-escaped literal content', () => {
  const input = helper('product-readiness-pc-admin-calendar.ts', 'input');
  for (const label of ['操作原因', '持续时间（ISO-8601）', 'A.*[x] "quoted" \\ value']) {
    assert.deepEqual(input(scope(), label).expression,
      ['first', ['locator', ['locator', 'scope', `label:text-is(${JSON.stringify(label)})`], 'input, textarea']]);
  }
});

test('calendar duration uses the complete maintained label and no helper builds a regex', () => {
  const calendar = read(directory + 'product-readiness-pc-admin-calendar.ts');
  assert.match(read('apps/web/overlay/apps/web-ele/src/views/approval/sla-policies/index.vue'), /<label>持续时间（ISO-8601）<ElInput/);
  assert.equal((calendar.match(/input\(editor, '持续时间（ISO-8601）'\)/g) || []).length, 3);
  for (const file of ['admin-calendar', 'form-package', 'process-design']) {
    assert.doesNotMatch(read(directory + `product-readiness-pc-${file}.ts`), /new RegExp/);
  }
});
