from pathlib import Path

path = Path('apps/web/overlay/apps/web-ele/src/views/approval/designer/use-approval-designer.ts')
text = path.read_text()

def replace_once(old: str, new: str, label: str):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one marker, found {count}')
    text = text.replace(old, new, 1)

replace_once(
'''    const end = draft.value.definition.nodes.find(node => node.kind === 'END');
    const predecessor = end && draft.value.definition.nodes.find(
      node => outgoing(node).includes(end.id),
    );
''',
'''    const end = topology.value.orderedNodes.find(node => node.kind === 'END');
    const predecessorId = end
      ? topology.value.incomingById.get(end.id)?.[0]
      : undefined;
    const predecessor = predecessorId
      ? topology.value.byId.get(predecessorId)
      : undefined;
''',
    'add node predecessor index',
)
replace_once(
    "    const entry = draft.value.definition.nodes.find(node => node.id === branch?.next);\n",
    "    const entry = branch ? topology.value.byId.get(branch.next) : undefined;\n",
    'branch entry index',
)
old_delete = '''  function deleteNode() {
    const node = selectedNode.value;
    if (!draft.value || !node) return;
    if (node.kind === 'PARALLEL_SPLIT') {
      ElMessage.info('请先删除并行分支中的节点');
      return;
    }
    if (!('next' in node) || ['END', 'PARALLEL_JOIN', 'START'].includes(node.kind)) return;
    const predecessor = draft.value.definition.nodes.find(
      candidate => outgoing(candidate).includes(node.id),
    );
    if (!predecessor) return;
    remember();
    replaceTarget(predecessor, node.id, node.next);
    draft.value.definition.nodes = draft.value.definition.nodes.filter(
      candidate => candidate.id !== node.id,
    );
    selectedNodeId.value = predecessor.id;
  }
'''
new_delete = '''  async function deleteNode() {
    const node = selectedNode.value;
    if (!draft.value || !node || !editable.value) return;
    const impact = describeApprovalDesignerDeletion(topology.value, node.id);
    if (!impact.deletable) {
      ElMessage.info(impact.reason ?? '当前节点不能安全删除');
      return;
    }
    const predecessorId = impact.incomingNodeIds[0];
    const predecessor = predecessorId
      ? topology.value.byId.get(predecessorId)
      : undefined;
    if (!predecessor || !('next' in node)) return;
    const details = [
      `节点：${node.name}（${node.id}）`,
      `前置引用：${predecessor.id}`,
      `删除后重连到：${node.next}`,
      impact.joinNodeId ? `该节点直接进入并行汇聚 ${impact.joinNodeId}` : '',
      node.kind === 'APPROVAL' && node.rejectNext
        ? `驳回目标 ${node.rejectNext} 将随节点一起移除`
        : '',
    ].filter(Boolean).join('\n');
    try {
      await ElMessageBox.confirm(details, '确认删除节点及重连影响', {
        confirmButtonText: '确认删除',
        type: impact.joinNodeId ? 'warning' : 'info',
      });
    } catch {
      return;
    }
    remember();
    replaceTarget(predecessor, node.id, node.next);
    draft.value.definition.nodes = draft.value.definition.nodes.filter(
      candidate => candidate.id !== node.id,
    );
    selectedNodeId.value = predecessor.id;
  }
'''
replace_once(old_delete, new_delete, 'safe delete node')
old_unload = '''  function handleBeforeUnload(event: BeforeUnloadEvent) {
    if (!hasPendingChanges.value) return;
    event.preventDefault();
    event.returnValue = '';
  }

  function clearResults() {
'''
new_unload = '''  function handleBeforeUnload(event: BeforeUnloadEvent) {
    if (!hasPendingChanges.value) return;
    event.preventDefault();
    event.returnValue = '';
  }

  function focusNode(subject: string) {
    const nodeId = resolveNodeId(subject);
    if (!nodeId) return false;
    if (!visibleNodes.value.some(node => node.id === nodeId)) {
      nodeSearch.value = '';
      nodeKindFilter.value = [];
    }
    const order = topology.value.orderById.get(nodeId) ?? 0;
    nodeRenderLimit.value = Math.max(
      nodeRenderLimit.value,
      Math.ceil((order + 1) / 120) * 120,
    );
    selectedNodeId.value = nodeId;
    return true;
  }

  function resolveNodeId(subject?: string) {
    return resolveApprovalDesignerNodeId(topology.value, subject);
  }

  function showMoreNodes() {
    nodeRenderLimit.value += 120;
  }

  function toggleBranchCollapse(nodeId: string) {
    const next = new Set(collapsedBranchNodeIds.value);
    if (next.has(nodeId)) next.delete(nodeId);
    else next.add(nodeId);
    collapsedBranchNodeIds.value = next;
  }

  function isBranchCollapsed(nodeId: string) {
    return collapsedBranchNodeIds.value.has(nodeId);
  }

  function collapseAllBranches() {
    collapsedBranchNodeIds.value = new Set(
      topology.value.orderedNodes
        .filter(node => ['CONDITION', 'PARALLEL_SPLIT'].includes(node.kind))
        .map(node => node.id),
    );
  }

  function expandAllBranches() {
    collapsedBranchNodeIds.value = new Set();
  }

  function handleKeyboardShortcut(event: KeyboardEvent) {
    const key = event.key.toLocaleLowerCase();
    const modifier = event.ctrlKey || event.metaKey;
    if (modifier && key === 's') {
      event.preventDefault();
      void save();
      return;
    }
    if (isEditableEventTarget(event.target)) return;
    if (modifier && key === 'z') {
      event.preventDefault();
      if (event.shiftKey) redo();
      else undo();
      return;
    }
    if (modifier && key === 'y') {
      event.preventDefault();
      redo();
      return;
    }
    if (!modifier && !event.repeat && ['backspace', 'delete'].includes(key)) {
      event.preventDefault();
      void deleteNode();
    }
  }

  function isEditableEventTarget(target: EventTarget | null) {
    if (!(target instanceof HTMLElement)) return false;
    if (target.isContentEditable) return true;
    return Boolean(target.closest(
      'input, textarea, select, [contenteditable="true"], button, a, [role="button"]',
    ));
  }

  function clearResults() {
'''
replace_once(old_unload, new_unload, 'focus collapse shortcuts')
old_outgoing = '''function outgoing(node: ApprovalNode) {
  if (node.kind === 'END') return [];
  if (node.kind === 'CONDITION') {
    return [...node.routes.map(route => route.next), node.defaultNext];
  }
  if (node.kind === 'PARALLEL_SPLIT') return node.branches.map(branch => branch.next);
  return [
    node.next,
    ...(node.kind === 'APPROVAL' && node.rejectNext ? [node.rejectNext] : []),
  ];
}

'''
replace_once(old_outgoing, '', 'remove repeated outgoing scan')
path.write_text(text)
