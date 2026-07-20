const NODE_KINDS = [
  'START',
  'APPROVAL',
  'HANDLE',
  'CONDITION',
  'PARALLEL_SPLIT',
  'PARALLEL_JOIN',
  'END',
];

export function buildApprovalDesignerTopologyIndex(nodes) {
  const orderedNodes = [...nodes];
  const byId = new Map();
  const orderById = new Map();
  const outgoingById = new Map();
  const incomingMutable = new Map();
  const kindCounts = Object.fromEntries(NODE_KINDS.map(kind => [kind, 0]));

  orderedNodes.forEach((node, index) => {
    byId.set(node.id, node);
    orderById.set(node.id, index);
    incomingMutable.set(node.id, []);
    kindCounts[node.kind] = (kindCounts[node.kind] ?? 0) + 1;
  });

  for (const node of orderedNodes) {
    const outgoingIds = collectOutgoingNodeIds(node);
    outgoingById.set(node.id, outgoingIds);
    for (const targetId of outgoingIds) {
      const incomingIds = incomingMutable.get(targetId);
      if (incomingIds) incomingIds.push(node.id);
    }
  }

  const incomingById = new Map();
  for (const [nodeId, incomingIds] of incomingMutable.entries()) {
    incomingById.set(nodeId, Object.freeze([...new Set(incomingIds)]));
  }

  return {
    byId,
    incomingById,
    kindCounts: Object.freeze(kindCounts),
    orderById,
    orderedNodes: Object.freeze(orderedNodes),
    outgoingById,
  };
}

export function filterApprovalDesignerNodes(index, search, kinds = []) {
  const normalizedSearch = search.trim().toLocaleLowerCase();
  const kindSet = new Set(kinds);
  if (!normalizedSearch && kindSet.size === 0) return index.orderedNodes;
  return index.orderedNodes.filter((node) => {
    if (kindSet.size > 0 && !kindSet.has(node.kind)) return false;
    if (!normalizedSearch) return true;
    return [node.id, node.name, node.kind]
      .some(value => String(value).toLocaleLowerCase().includes(normalizedSearch));
  });
}

export function resolveApprovalDesignerNodeId(index, subject) {
  if (!subject) return undefined;
  if (index.byId.has(subject)) return subject;
  const tokens = String(subject)
    .split(/[/:.\s[\](){}]+/)
    .map(value => value.trim())
    .filter(Boolean);
  for (const token of tokens) {
    if (index.byId.has(token)) return token;
  }
  return [...index.byId.keys()]
    .sort((left, right) => right.length - left.length || left.localeCompare(right))
    .find(nodeId => String(subject).includes(nodeId));
}

export function describeApprovalDesignerDeletion(index, nodeId) {
  const node = index.byId.get(nodeId);
  if (!node) return blockedDeletion(nodeId, '节点不存在');
  if (['START', 'END', 'PARALLEL_JOIN', 'PARALLEL_SPLIT'].includes(node.kind)) {
    return blockedDeletion(nodeId, `${node.kind} 节点不能通过普通删除移除`);
  }
  const incomingNodeIds = [...(index.incomingById.get(nodeId) ?? [])];
  const outgoingNodeIds = [...(index.outgoingById.get(nodeId) ?? [])];
  if (incomingNodeIds.length !== 1) {
    return {
      deletable: false,
      incomingNodeIds,
      joinNodeId: undefined,
      nodeId,
      outgoingNodeIds,
      reason: incomingNodeIds.length === 0
        ? '节点没有可安全重连的前置节点'
        : `节点被 ${incomingNodeIds.length} 个前置路径引用，禁止自动重连`,
    };
  }
  if (!('next' in node) || !node.next) {
    return {
      deletable: false,
      incomingNodeIds,
      joinNodeId: undefined,
      nodeId,
      outgoingNodeIds,
      reason: '节点没有确定的通过目标',
    };
  }
  const target = index.byId.get(node.next);
  return {
    deletable: true,
    incomingNodeIds,
    joinNodeId: target?.kind === 'PARALLEL_JOIN' ? target.id : undefined,
    nodeId,
    outgoingNodeIds,
    reason: undefined,
  };
}

export function collectOutgoingNodeIds(node) {
  if (node.kind === 'END') return Object.freeze([]);
  if (node.kind === 'CONDITION') {
    return Object.freeze([...new Set([
      ...node.routes.map(route => route.next),
      node.defaultNext,
    ])]);
  }
  if (node.kind === 'PARALLEL_SPLIT') {
    return Object.freeze([...new Set(node.branches.map(branch => branch.next))]);
  }
  const targets = [node.next];
  if (node.kind === 'APPROVAL' && node.rejectNext) targets.push(node.rejectNext);
  return Object.freeze([...new Set(targets.filter(Boolean))]);
}

function blockedDeletion(nodeId, reason) {
  return {
    deletable: false,
    incomingNodeIds: [],
    joinNodeId: undefined,
    nodeId,
    outgoingNodeIds: [],
    reason,
  };
}
