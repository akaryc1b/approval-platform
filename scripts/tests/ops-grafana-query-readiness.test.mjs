import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';
import { runInNewContext } from 'node:vm';
import { panelReadExpression, waitForPanelQueries, assertPanelQueriesSettled } from '../ops/grafana-browser-driver.mjs';

const root = resolve(import.meta.dirname, '../..');
const title = '流程完成耗时 P95';
const loadingSelector = '[aria-label="Panel loading bar"], [data-testid="panel-streaming"], [data-testid="panel-cancel-query"]';

// Only the pinned PanelChrome/Scenes DOM contract is mocked. The production
// expression executes unchanged; no browser, HTTP fixture, or native process.
function page(specs) {
  const scrolled = [];
  const panels = specs.map(spec => {
    const { title: name = title, text = '', plugin = 'timeseries', hidden = false } = spec;
    const bounds = () => ({ width: hidden ? 0 : 400, height: hidden ? 0 : 200 });
    const content = {
      innerText: text, getBoundingClientRect: bounds,
      querySelector(selector) { assert.equal(selector, 'canvas'); return spec.canvas ? {} : null; },
    };
    return { name, element: {
      scrollIntoView() { scrolled.push(name); },
      closest(selector) {
        assert.equal(selector, '[data-plugin-id]');
        return plugin === null ? null : { getAttribute(attribute) { assert.equal(attribute, 'data-plugin-id'); return plugin; } };
      },
      querySelector(selector) {
        if (selector === '[data-testid="data-testid panel content"]') return spec.noContent ? null : content;
        if (selector === loadingSelector) return spec.pending ? {} : null;
        assert.equal(selector, '[data-testid="data-testid Panel status error"]');
        return spec.error ? {} : null;
      },
    } };
  });
  const document = {
    querySelectorAll(selector) {
      return panels.filter(panel => selector === '[data-testid=' + JSON.stringify('data-testid Panel header ' + panel.name) + ']')
        .map(panel => panel.element);
    },
    createTreeWalker() {
      let read = false;
      return { nextNode() {
        if (read) return null;
        read = true;
        return { textContent: '未知', parentElement: { getBoundingClientRect: () => ({ width: 20, height: 20 }) } };
      } };
    },
  };
  return { scrolled, read: expression => runInNewContext(expression, {
    document, NodeFilter: { SHOW_TEXT: 4 }, getComputedStyle: () => ({ color: 'rgb(107, 114, 128)' }),
  }) };
}

test('visible blank chrome and plugin placeholders cannot stand in for a finished query', () => {
  for (const spec of [{}, { text: '   ' }, { text: 'Loading plugin panel...', plugin: 'loading-plugin' },
    { text: 'No data', plugin: null }, { text: 'No data', plugin: 'unrecognized-plugin' }]) {
    const panel = page([spec]).read(panelReadExpression(title));
    assert.equal(panel.visible, true); assert.equal(panel.error, false); assert.equal(panel.settled, false);
  }
});

test('completed empty results are valid for every shipped overview plugin', () => {
  for (const plugin of ['stat', 'timeseries', 'table']) {
    assert.equal(page([{ plugin, text: 'No data' }]).read(panelReadExpression(title)).settled, true);
  }
  assert.equal(page([{ canvas: true }]).read(panelReadExpression(title)).settled, true);
  const unknown = page([{ title: '流程采样', plugin: 'stat', text: '未知' }]).read(panelReadExpression('流程采样'));
  assert.equal(unknown.settled, true); assert.equal(unknown.error, false);
  for (const text of ['3', '5', '2', '1', '0']) {
    assert.equal(page([{ plugin: 'stat', text }]).read(panelReadExpression(title)).settled, true);
  }
});

test('loading and streaming markers block stale text, empty results, and existing canvases', () => {
  // The selector covers the immediate loading bar, streaming, and delayed cancel
  // icon. No visibility test may ignore the loading bar during its CSS delay.
  for (const pending of ['loading-bar', 'streaming', 'cancel-query']) {
    for (const spec of [{ text: 'No data' }, { text: '3', plugin: 'stat' }, { canvas: true }]) {
      assert.equal(page([{ ...spec, pending }]).read(panelReadExpression(title)).settled, false);
    }
  }
});

test('missing, duplicate, hidden, and not-yet-rendered panels do not complete the query wait', async () => {
  for (const specs of [[], [{ text: 'No data' }, { text: 'No data' }],
    [{ text: 'No data', hidden: true }], [{ noContent: true }]]) {
    const view = page(specs);
    await waitForPanelQueries({ evaluate: async expression => view.read(expression) }, async read => {
      assert.equal(await read(), false);
    }, [title]);
  }
});

test('a lazy panel waits through blank and loading chrome before accepting its empty result', async () => {
  const frames = [[{}], [{ text: 'No data', pending: true }], [{ text: 'No data' }]].map(page);
  let evaluations = 0, screenshots = 0;
  const browser = { evaluate: async expression => frames[evaluations++].read(expression) };
  const wait = async (read, label) => {
    assert.equal(label, 'panel-queries');
    assert.equal(await read(), false); assert.equal(screenshots, 0);
    assert.equal(await read(), false); assert.equal(screenshots, 0);
    return await read();
  };
  const panels = await waitForPanelQueries(browser, wait, [title], { scroll: true }); screenshots++;
  assert.equal(panels[0].text, 'No data'); assert.equal(evaluations, 3); assert.equal(screenshots, 1);
  for (const frame of frames) assert.deepEqual(frame.scrolled, [title]);
});

test('the final overview sample waits for every panel together without changing screenshot scroll', async () => {
  const titles = ['服务状态', title];
  const frames = [
    [{ title: titles[0], plugin: 'stat', text: '正常', pending: true }, { title, text: 'No data' }],
    [{ title: titles[0], plugin: 'stat', text: '正常' }, { title, text: 'No data', pending: true }],
    [{ title: titles[0], plugin: 'stat', text: '正常' }, { title, text: 'No data' }],
  ].map(page);
  let evaluations = 0;
  const result = await waitForPanelQueries({ evaluate: async expression => frames[evaluations++].read(expression) }, async read => {
    assert.equal(await read(), false); assert.equal(await read(), false); return await read();
  }, titles);
  assert.equal(result.length, 2); assert.equal(evaluations, 3);
  for (const frame of frames) assert.deepEqual(frame.scrolled, []);
});

test('query errors fail immediately even when another panel is still loading', async () => {
  for (const noContent of [false, true]) {
    const view = page([{ title: 'first', pending: true }, { title, text: 'No data', error: true, noContent }]);
    let evaluations = 0;
    await assert.rejects(waitForPanelQueries({ evaluate: async expression => { evaluations++; return view.read(expression); } },
      async read => await read(), ['first', title]), { message: 'GRAFANA_BROWSER_PANEL_ERROR:' + title });
    assert.equal(evaluations, 1);
  }
});

test('all panel waits consume the caller deadline and propagate timeout or transport failure', async () => {
  let now = 104600, evaluations = 0;
  const deadline = 105000, timeout = new Error('GRAFANA_BROWSER_DEADLINE:panel-queries');
  const view = page([{ text: 'No data', pending: true }]);
  const browser = { evaluate: async expression => { evaluations++; return view.read(expression); } };
  const wait = async read => {
    while (now < deadline) { const value = await read(); if (value) return value; now += 200; }
    throw timeout;
  };
  await assert.rejects(waitForPanelQueries(browser, wait, [title]), error => error === timeout);
  await assert.rejects(waitForPanelQueries(browser, wait, [title]), error => error === timeout);
  assert.equal(evaluations, 2); assert.equal(now, deadline);
  const transport = new Error('GRAFANA_BROWSER_PIPE_ENDED');
  await assert.rejects(waitForPanelQueries({ evaluate: async () => { throw transport; } }, async read => await read(), [title]),
    error => error === transport);
});

test('post-capture verification takes exactly one sample and rejects changed queries without waiting', async () => {
  for (const spec of [{ text: 'No data', pending: true }, { text: 'No data', error: true }, {}, { text: 'No data', hidden: true }]) {
    const view = page([spec]); let evaluations = 0;
    const browser = { evaluate: async expression => { evaluations++; return view.read(expression); } };
    await assert.rejects(assertPanelQueriesSettled(browser, [title]),
      spec.error ? /GRAFANA_BROWSER_PANEL_ERROR/u : /GRAFANA_BROWSER_CAPTURE_QUERIES_UNSETTLED/u);
    assert.equal(evaluations, 1); assert.deepEqual(view.scrolled, []);
  }
  const view = page([{ text: 'No data' }]); let evaluations = 0;
  await assertPanelQueriesSettled({ evaluate: async expression => { evaluations++; return view.read(expression); } }, [title]);
  assert.equal(evaluations, 1); assert.deepEqual(view.scrolled, []);
});

test('runtime gates the original overview screenshot using the same wait and untouched query source', () => {
  const source = readFileSync(resolve(root, 'scripts/ops/grafana-browser-runtime.mjs'), 'utf8');
  assert.match(source, /const deadline = performance\.now\(\) \+ 105000;/u);
  assert.match(source, /const overviewTitles = imported\[0\]\.panels\.filter\(p => p\.type !== 'row'\)\.map\(p => p\.title\);/u);
  assert.match(source, /for \(const title of overviewTitles\) await waitForPanelQueries\(browser, wait, \[title\], \{ scroll: true \}\);/u);
  assert.match(source, /await seePanel\('服务状态'\);\s+await waitForPanelQueries\(browser, wait, overviewTitles\); await screenshot\('overview'\);/u);
  assert.match(source, /const screenshot = async name => \{\s+if \(name === 'overview'\) assert\.ok\(performance\.now\(\) < deadline && !abort\.signal\.aborted, 'GRAFANA_BROWSER_DEADLINE:overview-capture'\);\s+const \{ data \} = await browser\.call/u);
  assert.match(source, /const \{ data \} = await browser\.call\('Page\.captureScreenshot',[^\n]+\n\s+if \(name === 'overview'\) \{\s+assert\.ok\(performance\.now\(\) < deadline && !abort\.signal\.aborted, 'GRAFANA_BROWSER_DEADLINE:overview-capture'\);\s+await assertPanelQueriesSettled\(browser, overviewTitles\);\s+assert\.ok\(performance\.now\(\) < deadline && !abort\.signal\.aborted, 'GRAFANA_BROWSER_DEADLINE:overview-capture'\);\s+\}\s+const bytes =/u);
  assert.equal(source.split("await screenshot('overview')").length, 2);
  const dashboard = JSON.parse(readFileSync(resolve(root, 'deploy/observability/grafana/approval-operations.json')));
  assert.equal(dashboard.panels.filter(panel => panel.type !== 'row').length, 25);
  assert.deepEqual([...new Set(dashboard.panels.filter(panel => panel.type !== 'row').map(panel => panel.type))].sort(),
    ['stat', 'table', 'timeseries']);
});
