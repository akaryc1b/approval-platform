import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';

import {
  buildApprovalDesignerTopologyIndex,
  filterApprovalDesignerNodes,
} from '../../apps/web/overlay/apps/web-ele/src/views/approval/designer/designer-topology.mjs';

const sizes = [100, 300, 500];
const rounds = 60;
const report = [];

for (const size of sizes) {
  const nodes = linearDefinition(size);
  for (let warmup = 0; warmup < 10; warmup += 1) {
    buildApprovalDesignerTopologyIndex(nodes);
  }
  const durations = [];
  let lastIndex;
  for (let round = 0; round < rounds; round += 1) {
    const started = performance.now();
    lastIndex = buildApprovalDesignerTopologyIndex(nodes);
    durations.push(performance.now() - started);
  }
  assert.equal(lastIndex.orderedNodes.length, size);
  assert.equal(lastIndex.byId.size, size);
  assert.equal(lastIndex.incomingById.get('end')?.length, 1);
  assert.equal(filterApprovalDesignerNodes(lastIndex, `approval-${size - 2}`).length, 1);
  durations.sort((left, right) => left - right);
  const averageMs = durations.reduce((sum, value) => sum + value, 0) / rounds;
  const p95Ms = durations[Math.floor(rounds * 0.95)] ?? durations.at(-1) ?? 0;
  assert.ok(averageMs < 250, `${size}-node index average exceeded 250ms`);
  assert.ok(p95Ms < 500, `${size}-node index p95 exceeded 500ms`);
  report.push({
    averageMs: Number(averageMs.toFixed(3)),
    nodes: size,
    p95Ms: Number(p95Ms.toFixed(3)),
    rounds,
  });
}

console.log(JSON.stringify({ benchmark: 'approval-designer-topology', report }, null, 2));

function linearDefinition(size) {
  assert.ok(size >= 2);
  const nodes = [{ id: 'start', kind: 'START', name: '开始', next: 'approval-1' }];
  for (let index = 1; index <= size - 2; index += 1) {
    nodes.push({
      id: `approval-${index}`,
      kind: 'APPROVAL',
      name: `审批节点 ${index}`,
      next: index === size - 2 ? 'end' : `approval-${index + 1}`,
    });
  }
  nodes.push({ id: 'end', kind: 'END', name: '结束' });
  return nodes;
}
