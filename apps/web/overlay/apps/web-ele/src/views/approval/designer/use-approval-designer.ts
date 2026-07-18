import type {
  ApprovalDesignDraft,
  ApprovalDesignDraftStatus,
  ApprovalDesignDraftSummary,
  ApprovalDesignValidationResult,
  ApprovalNode,
  ApprovalPublishResult,
  ApprovalSimulationResponse,
  ApprovalStep,
  ConditionStep,
  HandleStep,
  ParallelSplitNode,
} from '#/api/approval/process-design';

import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';

import {
  ApprovalDesignApiError,
  archiveApprovalDesignDraft,
  copyPublishedApprovalDesignDraft,
  createApprovalDesignDraft,
  findApprovalDesignDraft,
  findApprovalDesignDrafts,
  publishApprovalDesignDraft,
  simulateApprovalDesignDraft,
  updateApprovalDesignDraft,
  validateApprovalDesignDraft,
} from '#/api/approval/process-design';

type Decision = 'APPROVE' | 'REJECT';
type AddableNodeKind = 'APPROVAL' | 'CONDITION' | 'HANDLE' | 'PARALLEL';

interface DraftSnapshot {
  definition: ApprovalDesignDraft['definition'];
  name: string;
}

export function useApprovalDesigner() {
  const drafts = ref<ApprovalDesignDraftSummary[]>([]);
  const draft = ref<ApprovalDesignDraft>();
  const selectedNodeId = ref('');
  const keyword = ref('');
  const status = ref<ApprovalDesignDraftStatus>();
  const busy = ref(false);
  const saveState = ref<'conflict' | 'dirty' | 'idle' | 'saved' | 'saving'>('idle');
  const validation = ref<ApprovalDesignValidationResult>();
  const simulation = ref<ApprovalSimulationResponse>();
  const published = ref<ApprovalPublishResult>();
  const decisions = ref<Record<string, Decision>>({});
  const amount = ref(1200);
  const undoStack = ref<DraftSnapshot[]>([]);

  let suspended = true;
  let saveTimer: ReturnType<typeof setTimeout> | undefined;
  let sequence = 1;

  const selectedNode = computed(() => draft.value?.definition.nodes.find(
    node => node.id === selectedNodeId.value,
  ));
  const selectedApproval = computed<ApprovalStep | undefined>(() =>
    selectedNode.value?.kind === 'APPROVAL' ? selectedNode.value : undefined,
  );
  const selectedHandle = computed<HandleStep | undefined>(() =>
    selectedNode.value?.kind === 'HANDLE' ? selectedNode.value : undefined,
  );
  const selectedCondition = computed<ConditionStep | undefined>(() =>
    selectedNode.value?.kind === 'CONDITION' ? selectedNode.value : undefined,
  );
  const selectedParallel = computed<ParallelSplitNode | undefined>(() =>
    selectedNode.value?.kind === 'PARALLEL_SPLIT' ? selectedNode.value : undefined,
  );
  const editable = computed(() => draft.value?.status === 'DRAFT'
    || draft.value?.status === 'VALIDATED');
  const nodeOptions = computed(() => draft.value?.definition.nodes.map(node => ({
    label: `${node.name} · ${node.id}`,
    value: node.id,
  })) ?? []);
  const errors = computed(() => validation.value?.issues.filter(
    issue => issue.severity === 'ERROR',
  ) ?? []);
  const warnings = computed(() => validation.value?.issues.filter(
    issue => issue.severity === 'WARNING',
  ) ?? []);
  const json = computed(() => JSON.stringify(draft.value?.definition ?? {}, null, 2));

  watch(() => draft.value?.definition, markChanged, { deep: true });
  watch(() => draft.value?.name, markChanged);
  onBeforeUnmount(() => {
    if (saveTimer) clearTimeout(saveTimer);
  });

  async function loadDrafts() {
    busy.value = true;
    try {
      const page = await findApprovalDesignDrafts(keyword.value, status.value, 100, 0);
      drafts.value = page.items;
      if (!draft.value && page.items[0]) await openDraft(page.items[0].draftId);
    } catch (error) {
      showError(error);
    } finally {
      busy.value = false;
    }
  }

  async function openDraft(draftId: string) {
    suspended = true;
    try {
      draft.value = await findApprovalDesignDraft(draftId);
      selectedNodeId.value = draft.value.definition.startNodeId;
      clearResults();
      decisions.value = {};
      undoStack.value = [];
      saveState.value = 'saved';
    } catch (error) {
      showError(error);
    } finally {
      suspended = false;
    }
  }

  async function createDraft(input: {
    definitionKey: string;
    definitionVersion: number;
    formPackageVersion: number;
    name: string;
    source: 'BLANK' | 'COPY' | 'PURCHASE_PAYMENT_TEMPLATE';
    sourceDefinitionVersion: number;
  }) {
    try {
      const created = input.source === 'COPY'
        ? await copyPublishedApprovalDesignDraft({
            definitionKey: input.definitionKey,
            formPackageVersion: input.formPackageVersion,
            name: input.name,
            sourceDefinitionVersion: input.sourceDefinitionVersion,
            targetDefinitionVersion: input.definitionVersion,
          })
        : await createApprovalDesignDraft({
            definitionKey: input.definitionKey,
            definitionVersion: input.definitionVersion,
            formPackageVersion: input.formPackageVersion,
            name: input.name,
            source: input.source,
          });
      await loadDrafts();
      await openDraft(created.draftId);
      ElMessage.success('流程草稿已创建');
    } catch (error) {
      showError(error);
    }
  }

  async function save(mode: 'AUTO_SAVE' | 'EXPLICIT' = 'EXPLICIT') {
    const current = draft.value;
    if (!current || !editable.value || saveState.value === 'saving') return false;
    if (saveTimer) clearTimeout(saveTimer);
    saveState.value = 'saving';
    try {
      draft.value = await updateApprovalDesignDraft(current.draftId, {
        definition: current.definition,
        expectedRevision: current.revision,
        formPackageVersion: current.formPackage.packageVersion,
        name: current.name,
        saveMode: mode,
      });
      saveState.value = 'saved';
      await loadDrafts();
      if (mode === 'EXPLICIT') ElMessage.success('流程草稿已保存');
      return true;
    } catch (error) {
      if (error instanceof ApprovalDesignApiError
        && error.code === 'APPROVAL_DESIGN_REVISION_CONFLICT') {
        saveState.value = 'conflict';
        ElMessage.warning('草稿已被其他操作更新，请重新加载');
      } else {
        saveState.value = 'dirty';
        showError(error);
      }
      return false;
    }
  }

  async function reload() {
    if (draft.value) await openDraft(draft.value.draftId);
  }

  async function validate() {
    if (!draft.value) return undefined;
    if (saveState.value === 'dirty' && !(await save())) return undefined;
    const current = draft.value;
    try {
      const result = await validateApprovalDesignDraft(current.draftId, current.revision);
      if (result.revision !== current.revision) await openDraft(current.draftId);
      validation.value = result;
      ElMessage.success(result.issues.some(issue => issue.severity === 'ERROR')
        ? '检查完成，存在阻断项'
        : '服务端检查通过');
      return result;
    } catch (error) {
      showError(error);
      return undefined;
    }
  }

  async function simulate() {
    if (!draft.value) return;
    if (saveState.value === 'dirty' && !(await save())) return;
    try {
      simulation.value = await simulateApprovalDesignDraft(
        draft.value.draftId,
        draft.value.revision,
        { decisions: decisions.value, formValues: { amount: amount.value } },
      );
    } catch (error) {
      showError(error);
    }
  }

  async function publish() {
    if (!draft.value) return;
    const check = await validate();
    if (!check || check.issues.some(issue => issue.severity === 'ERROR') || !draft.value) return;
    const warningCount = check.issues.filter(issue => issue.severity === 'WARNING').length;
    const warningText = warningCount
      ? `存在 ${warningCount} 条警告，确认继续发布？`
      : '确认发布不可变流程版本和 Release Package？';
    await ElMessageBox.confirm(warningText, '发布确认', { type: 'warning' });
    try {
      const result = await publishApprovalDesignDraft(
        draft.value.draftId,
        draft.value.revision,
        draft.value.definition.version,
        draft.value.definition.version,
      );
      await openDraft(draft.value.draftId);
      published.value = result;
      ElMessage.success('Release Package 已发布');
    } catch (error) {
      showError(error);
    }
  }

  async function archive() {
    if (!draft.value) return;
    await ElMessageBox.confirm('确认归档当前流程草稿？', '归档草稿', { type: 'warning' });
    try {
      draft.value = await archiveApprovalDesignDraft(draft.value.draftId, draft.value.revision);
      await loadDrafts();
    } catch (error) {
      showError(error);
    }
  }

  function addNode(kind: AddableNodeKind) {
    if (!draft.value || !editable.value) return;
    const end = draft.value.definition.nodes.find(node => node.kind === 'END');
    const predecessor = end && draft.value.definition.nodes.find(
      node => outgoing(node).includes(end.id),
    );
    if (!end || !predecessor) return;
    remember();
    if (kind === 'PARALLEL') {
      addParallel(predecessor, end.id);
      return;
    }
    const id = nextId(kind.toLowerCase());
    const node: ApprovalNode = kind === 'APPROVAL'
      ? approvalNode(id, '审批节点', end.id)
      : kind === 'HANDLE'
        ? {
            assignee: assignee('initiatorId'),
            id,
            kind: 'HANDLE',
            name: '发起人处理',
            next: end.id,
          }
        : {
            defaultNext: end.id,
            id,
            kind: 'CONDITION',
            name: '条件分支',
            routes: [{
              condition: {
                field: 'amount',
                operator: 'GREATER_THAN_OR_EQUAL',
                value: 1000,
              },
              next: end.id,
            }],
          };
    replaceTarget(predecessor, end.id, id);
    draft.value.definition.nodes.splice(
      draft.value.definition.nodes.indexOf(end),
      0,
      node,
    );
    selectedNodeId.value = id;
  }

  function addParallel(predecessor: ApprovalNode, endId: string) {
    if (!draft.value) return;
    const splitId = nextId('parallel');
    const joinId = nextId('join');
    const leftId = nextId('approval');
    const rightId = nextId('approval');
    const split: ParallelSplitNode = {
      branches: [
        { id: `${splitId}A`, name: '分支一', next: leftId },
        { id: `${splitId}B`, name: '分支二', next: rightId },
      ],
      id: splitId,
      joinNodeId: joinId,
      kind: 'PARALLEL_SPLIT',
      name: '并行分支',
    };
    replaceTarget(predecessor, endId, splitId);
    const index = draft.value.definition.nodes.findIndex(node => node.id === endId);
    draft.value.definition.nodes.splice(
      index,
      0,
      split,
      approvalNode(leftId, '并行审批一', joinId),
      approvalNode(rightId, '并行审批二', joinId),
      { id: joinId, kind: 'PARALLEL_JOIN', name: '并行汇聚', next: endId },
    );
    selectedNodeId.value = splitId;
  }

  function addBranch() {
    const split = selectedParallel.value;
    if (!draft.value || !split || !editable.value) return;
    const joinIndex = draft.value.definition.nodes.findIndex(
      node => node.id === split.joinNodeId,
    );
    if (joinIndex < 0) return;
    remember();
    const number = split.branches.length + 1;
    const id = nextId('approval');
    split.branches.push({ id: `${split.id}${number}`, name: `分支${number}`, next: id });
    draft.value.definition.nodes.splice(
      joinIndex,
      0,
      approvalNode(id, `并行审批${number}`, split.joinNodeId),
    );
  }

  function removeBranch(index: number) {
    const split = selectedParallel.value;
    if (!draft.value || !split || split.branches.length <= 2) return;
    const branch = split.branches[index];
    const entry = draft.value.definition.nodes.find(node => node.id === branch?.next);
    if (!entry || !('next' in entry) || entry.next !== split.joinNodeId) {
      ElMessage.info('请先清空该分支中的复合节点');
      return;
    }
    remember();
    split.branches.splice(index, 1);
    draft.value.definition.nodes = draft.value.definition.nodes.filter(
      node => node.id !== entry.id,
    );
  }

  function copyNode() {
    const node = selectedNode.value;
    if (!draft.value || !node || !['APPROVAL', 'HANDLE'].includes(node.kind)) return;
    const source = node as ApprovalStep | HandleStep;
    remember();
    const copy = structuredClone(source);
    copy.id = nextId(copy.kind.toLowerCase());
    copy.name = `${copy.name} 副本`;
    copy.next = source.next;
    source.next = copy.id;
    draft.value.definition.nodes.splice(
      draft.value.definition.nodes.indexOf(source) + 1,
      0,
      copy,
    );
    selectedNodeId.value = copy.id;
  }

  function deleteNode() {
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

  function moveNode(offset: -1 | 1) {
    if (!draft.value || !selectedNode.value) return;
    const index = draft.value.definition.nodes.indexOf(selectedNode.value);
    const target = index + offset;
    if (target < 0 || target >= draft.value.definition.nodes.length) return;
    remember();
    const [node] = draft.value.definition.nodes.splice(index, 1);
    if (node) draft.value.definition.nodes.splice(target, 0, node);
  }

  function addConditionRoute() {
    const condition = selectedCondition.value;
    if (!condition) return;
    condition.routes.push({
      condition: {
        field: 'amount',
        operator: 'GREATER_THAN_OR_EQUAL',
        value: 1000,
      },
      next: condition.defaultNext,
    });
  }

  function removeConditionRoute(index: number) {
    const condition = selectedCondition.value;
    if (condition && condition.routes.length > 1) condition.routes.splice(index, 1);
  }

  function undo() {
    if (!draft.value) return;
    const state = undoStack.value.pop();
    if (!state) return;
    suspended = true;
    draft.value.definition = state.definition;
    draft.value.name = state.name;
    suspended = false;
    markChanged();
  }

  function markChanged() {
    if (suspended || !draft.value || !editable.value) return;
    saveState.value = 'dirty';
    clearResults();
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = setTimeout(() => save('AUTO_SAVE'), 900);
  }

  function remember() {
    if (!draft.value) return;
    undoStack.value.push({
      definition: structuredClone(draft.value.definition),
      name: draft.value.name,
    });
    if (undoStack.value.length > 30) undoStack.value.shift();
  }

  function clearResults() {
    validation.value = undefined;
    simulation.value = undefined;
    published.value = undefined;
  }

  function nextId(prefix: string) {
    const ids = new Set(draft.value?.definition.nodes.map(node => node.id) ?? []);
    let id = `${prefix}${sequence++}`;
    while (ids.has(id)) id = `${prefix}${sequence++}`;
    return id;
  }

  return {
    addBranch,
    addConditionRoute,
    addNode,
    amount,
    archive,
    busy,
    copyNode,
    createDraft,
    decisions,
    deleteNode,
    draft,
    drafts,
    editable,
    errors,
    json,
    keyword,
    loadDrafts,
    moveNode,
    nodeOptions,
    openDraft,
    publish,
    published,
    reload,
    removeBranch,
    removeConditionRoute,
    save,
    saveState,
    selectedApproval,
    selectedCondition,
    selectedHandle,
    selectedNode,
    selectedNodeId,
    selectedParallel,
    simulate,
    simulation,
    status,
    undo,
    undoStack,
    validate,
    validation,
    warnings,
  };
}

function approvalNode(id: string, name: string, next: string): ApprovalStep {
  return {
    assignee: assignee(`${id}UserId`),
    id,
    kind: 'APPROVAL',
    mode: { type: 'SINGLE' },
    name,
    next,
  };
}

function assignee(variable: string) {
  return {
    emptyPolicy: 'FAIL' as const,
    resolver: 'VARIABLE_USER' as const,
    variable,
  };
}

function outgoing(node: ApprovalNode) {
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

function replaceTarget(node: ApprovalNode, oldTarget: string, newTarget: string) {
  if (node.kind === 'END') return;
  if (node.kind === 'CONDITION') {
    node.routes.forEach((route) => {
      if (route.next === oldTarget) route.next = newTarget;
    });
    if (node.defaultNext === oldTarget) node.defaultNext = newTarget;
    return;
  }
  if (node.kind === 'PARALLEL_SPLIT') {
    node.branches.forEach((branch) => {
      if (branch.next === oldTarget) branch.next = newTarget;
    });
    return;
  }
  if (node.next === oldTarget) node.next = newTarget;
  if (node.kind === 'APPROVAL' && node.rejectNext === oldTarget) {
    node.rejectNext = newTarget;
  }
}

function showError(error: unknown) {
  ElMessage.error(error instanceof Error ? error.message : '操作失败');
}
