import type {
  ApprovalDesignDraft,
  ApprovalDesignDraftStatus,
  ApprovalDesignDraftSummary,
  ApprovalDesignValidationResult,
  ApprovalNode,
  ApprovalPreflightReport,
  ApprovalPublishResult,
  ApprovalSimulationResponse,
  ApprovalStep,
  ConditionStep,
  HandleStep,
  ParallelSplitNode,
} from '#/api/approval/process-design';

import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';

import {
  ApprovalDesignApiError,
  archiveApprovalDesignDraft,
  copyPublishedApprovalDesignDraft,
  createApprovalDesignDraft,
  findApprovalDesignDraft,
  findApprovalDesignDrafts,
  preflightApprovalPublication,
  publishApprovalDesignDraft,
  simulateApprovalDesignDraft,
  updateApprovalDesignDraft,
  validateApprovalDesignDraft,
} from '#/api/approval/process-design';

import {
  analyzeDesignerConflict,
  mergeDesignerConflict,
  snapshotApprovalDesignerDraft,
} from './designer-conflict';
import type {
  ApprovalDesignerConflict,
  ApprovalDesignerSnapshot,
} from './designer-conflict';

type Decision = 'APPROVE' | 'REJECT';
type AddableNodeKind = 'APPROVAL' | 'CONDITION' | 'HANDLE' | 'PARALLEL';

type DesignerSaveState =
  | 'conflict'
  | 'dirty'
  | 'failed'
  | 'idle'
  | 'saved'
  | 'saving';

interface DesignerConflictState {
  analysis: ApprovalDesignerConflict;
  serverDraft: ApprovalDesignDraft;
}

export function useApprovalDesigner() {
  const drafts = ref<ApprovalDesignDraftSummary[]>([]);
  const draft = ref<ApprovalDesignDraft>();
  const selectedNodeId = ref('');
  const keyword = ref('');
  const status = ref<ApprovalDesignDraftStatus>();
  const busy = ref(false);
  const saveState = ref<DesignerSaveState>('idle');
  const validation = ref<ApprovalDesignValidationResult>();
  const simulation = ref<ApprovalSimulationResponse>();
  const published = ref<ApprovalPublishResult>();
  const preflight = ref<ApprovalPreflightReport>();
  const decisions = ref<Record<string, Decision>>({});
  const amount = ref(1200);
  const undoStack = ref<ApprovalDesignerSnapshot[]>([]);
  const redoStack = ref<ApprovalDesignerSnapshot[]>([]);
  const baseSnapshot = ref<ApprovalDesignerSnapshot>();
  const conflict = ref<DesignerConflictState>();

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
  const hasPendingChanges = computed(() => [
    'conflict',
    'dirty',
    'failed',
    'saving',
  ].includes(saveState.value));

  watch(() => draft.value?.definition, () => markChanged(), {
    deep: true,
    flush: 'sync',
  });
  watch(() => draft.value?.name, () => markChanged(), { flush: 'sync' });
  watch(
    () => draft.value?.formPackage.packageVersion,
    () => markChanged(),
    { flush: 'sync' },
  );
  if (typeof window !== 'undefined') {
    window.addEventListener('beforeunload', handleBeforeUnload);
  }
  onBeforeRouteLeave(() => confirmDiscardChanges('离开流程设计器'));
  onBeforeUnmount(() => {
    if (saveTimer) clearTimeout(saveTimer);
    if (typeof window !== 'undefined') {
      window.removeEventListener('beforeunload', handleBeforeUnload);
    }
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

  async function openDraft(
    draftId: string,
    options: { force?: boolean } = {},
  ) {
    if (!options.force && draft.value
      && !(await confirmDiscardChanges('重新加载或切换流程草稿'))) {
      return false;
    }
    suspended = true;
    try {
      const loaded = await findApprovalDesignDraft(draftId);
      draft.value = loaded;
      baseSnapshot.value = snapshotApprovalDesignerDraft(loaded);
      conflict.value = undefined;
      selectedNodeId.value = loaded.definition.startNodeId;
      clearResults();
      decisions.value = {};
      undoStack.value = [];
      redoStack.value = [];
      saveState.value = 'saved';
      return true;
    } catch (error) {
      showError(error);
      return false;
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
    if (!(await confirmDiscardChanges('新建流程草稿'))) return;
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
      await openDraft(created.draftId, { force: true });
      ElMessage.success('流程草稿已创建');
    } catch (error) {
      showError(error);
    }
  }

  async function save(mode: 'AUTO_SAVE' | 'EXPLICIT' = 'EXPLICIT') {
    const current = draft.value;
    if (!current || !editable.value || saveState.value === 'saving'
      || saveState.value === 'conflict') return false;
    if (saveTimer) clearTimeout(saveTimer);
    saveState.value = 'saving';
    try {
      const saved = await updateApprovalDesignDraft(current.draftId, {
        definition: current.definition,
        expectedRevision: current.revision,
        formPackageVersion: current.formPackage.packageVersion,
        name: current.name,
        saveMode: mode,
      });
      suspended = true;
      draft.value = saved;
      baseSnapshot.value = snapshotApprovalDesignerDraft(saved);
      conflict.value = undefined;
      suspended = false;
      saveState.value = 'saved';
      await loadDrafts();
      if (mode === 'EXPLICIT') ElMessage.success('流程草稿已保存');
      return true;
    } catch (error) {
      if (error instanceof ApprovalDesignApiError
        && error.code === 'APPROVAL_DESIGN_REVISION_CONFLICT') {
        await captureConflict(current);
      } else {
        saveState.value = 'failed';
        showError(error);
      }
      return false;
    } finally {
      suspended = false;
    }
  }

  async function reload() {
    await reloadServerVersion();
  }

  async function reloadServerVersion() {
    const current = draft.value;
    if (!current || !(await confirmDiscardChanges('加载服务端版本'))) return false;
    return openDraft(current.draftId, { force: true });
  }

  async function applySafeConflictMerge() {
    const current = draft.value;
    const state = conflict.value;
    const base = baseSnapshot.value;
    if (!current || !state || !base) return false;
    if (!state.analysis.canAutoMerge) {
      ElMessage.warning('本地与服务端存在重叠修改，请加载服务端版本后手动重做');
      return false;
    }
    const serverBase = snapshotApprovalDesignerDraft(state.serverDraft);
    const merged = mergeDesignerConflict(
      base,
      snapshotApprovalDesignerDraft(current),
      serverBase,
    );
    suspended = true;
    draft.value = structuredClone(state.serverDraft);
    applySnapshot(merged);
    baseSnapshot.value = serverBase;
    conflict.value = undefined;
    undoStack.value = [];
    redoStack.value = [];
    suspended = false;
    markChanged(false);
    ElMessage.success('非重叠修改已安全合并，请保存确认');
    return true;
  }

  async function captureConflict(localDraft: ApprovalDesignDraft) {
    saveState.value = 'conflict';
    try {
      const serverDraft = await findApprovalDesignDraft(localDraft.draftId);
      const base = baseSnapshot.value ?? snapshotApprovalDesignerDraft(localDraft);
      conflict.value = {
        analysis: analyzeDesignerConflict(
          base,
          snapshotApprovalDesignerDraft(localDraft),
          snapshotApprovalDesignerDraft(serverDraft),
        ),
        serverDraft,
      };
      ElMessage.warning('草稿已被其他操作更新，请比较本地与服务端修改');
    } catch (error) {
      conflict.value = undefined;
      showError(error);
    }
  }

  async function ensureSaved() {
    if (saveState.value === 'conflict') {
      ElMessage.warning('请先处理版本冲突');
      return false;
    }
    if (saveState.value === 'saving') {
      ElMessage.info('草稿正在保存，请稍后重试');
      return false;
    }
    if (saveState.value === 'dirty' || saveState.value === 'failed') {
      return save();
    }
    return true;
  }

  async function validate() {
    if (!draft.value) return undefined;
    if (!(await ensureSaved())) return undefined;
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
    if (!(await ensureSaved())) return;
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
    if (!(await ensureSaved())) return;
    const current = draft.value;
    const scenario = {
      decisions: decisions.value,
      formValues: { amount: amount.value },
      maxTransitions: 200,
    };
    try {
      const report = await preflightApprovalPublication({
        definitionKey: current.definitionKey,
        deploymentTarget: 'default',
        draftId: current.draftId,
        expectedRevision: current.revision,
        scenario,
        targetDefinitionVersion: current.definition.version,
        targetReleaseVersion: current.definition.version,
      });
      preflight.value = report;
      if (!report.publishable || report.errors.length > 0) {
        ElMessage.error(
          `发布前检查存在阻断项：${report.errors.map(item => item.code).join('、')}`,
        );
        return;
      }
      const warningCodes = [...new Set(report.warnings.map(item => item.code))].sort();
      const summary = [
        `DSL v${report.targetDefinitionVersion} / Release v${report.targetReleaseVersion}`,
        `Preflight ${report.preflightHash.slice(0, 12)}…`,
        `BPMN ${report.generatedHashes.bpmnHash?.slice(0, 12) ?? '—'}…`,
        warningCodes.length
          ? `需确认警告：${warningCodes.join('、')}`
          : '没有需要确认的警告',
      ].join('\n');
      await ElMessageBox.confirm(summary, '发布前综合检查', {
        confirmButtonText: warningCodes.length ? '确认警告并发布' : '确认发布',
        type: warningCodes.length ? 'warning' : 'info',
      });
      const result = await publishApprovalDesignDraft(
        current.draftId,
        {
          acknowledgedWarningCodes: warningCodes,
          definitionVersion: current.definition.version,
          deploymentTarget: report.deploymentTarget,
          expectedRevision: current.revision,
          preflightHash: report.preflightHash,
          preflightScenario: scenario,
          releaseVersion: current.definition.version,
        },
      );
      await openDraft(current.draftId);
      published.value = result;
      ElMessage.success('Release Package 已发布');
    } catch (error) {
      showError(error);
    }
  }

  async function archive() {
    if (!draft.value || !(await confirmDiscardChanges('归档流程草稿'))) return;
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
    remember();
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
    if (!condition || condition.routes.length <= 1) return;
    remember();
    condition.routes.splice(index, 1);
  }

  function undo() {
    if (!draft.value) return;
    const state = undoStack.value.pop();
    if (!state) return;
    pushHistory(redoStack.value, snapshotApprovalDesignerDraft(draft.value));
    applySnapshot(state);
    markChanged(false);
  }

  function redo() {
    if (!draft.value) return;
    const state = redoStack.value.pop();
    if (!state) return;
    pushHistory(undoStack.value, snapshotApprovalDesignerDraft(draft.value));
    applySnapshot(state);
    markChanged(false);
  }

  function markChanged(clearRedo = true) {
    if (suspended || !draft.value || !editable.value) return;
    clearResults();
    if (clearRedo) redoStack.value = [];
    if (conflict.value && baseSnapshot.value) {
      conflict.value.analysis = analyzeDesignerConflict(
        baseSnapshot.value,
        snapshotApprovalDesignerDraft(draft.value),
        snapshotApprovalDesignerDraft(conflict.value.serverDraft),
      );
      saveState.value = 'conflict';
      if (saveTimer) clearTimeout(saveTimer);
      return;
    }
    saveState.value = 'dirty';
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = setTimeout(() => save('AUTO_SAVE'), 900);
  }

  function remember() {
    if (!draft.value) return;
    pushHistory(undoStack.value, snapshotApprovalDesignerDraft(draft.value));
    redoStack.value = [];
  }

  function applySnapshot(snapshot: ApprovalDesignerSnapshot) {
    if (!draft.value) return;
    suspended = true;
    draft.value.definition = structuredClone(snapshot.definition);
    draft.value.formPackage.packageVersion = snapshot.formPackageVersion;
    draft.value.name = snapshot.name;
    if (!draft.value.definition.nodes.some(node => node.id === selectedNodeId.value)) {
      selectedNodeId.value = draft.value.definition.startNodeId;
    }
    suspended = false;
  }

  function pushHistory(
    stack: ApprovalDesignerSnapshot[],
    snapshot: ApprovalDesignerSnapshot,
  ) {
    stack.push(snapshot);
    if (stack.length > 30) stack.shift();
  }

  async function confirmDiscardChanges(action: string) {
    if (!hasPendingChanges.value) return true;
    try {
      await ElMessageBox.confirm(
        `当前草稿存在未保存修改或版本冲突，确认${action}并放弃本地状态？`,
        '未保存修改',
        {
          cancelButtonText: '继续编辑',
          confirmButtonText: '放弃并继续',
          type: 'warning',
        },
      );
      return true;
    } catch {
      return false;
    }
  }

  function handleBeforeUnload(event: BeforeUnloadEvent) {
    if (!hasPendingChanges.value) return;
    event.preventDefault();
    event.returnValue = '';
  }

  function clearResults() {
    validation.value = undefined;
    simulation.value = undefined;
    published.value = undefined;
    preflight.value = undefined;
  }

  function nextId(prefix: string) {
    const ids = new Set(draft.value?.definition.nodes.map(node => node.id) ?? []);
    let id = `${prefix}${sequence++}`;
    while (ids.has(id)) id = `${prefix}${sequence++}`;
    return id;
  }

  return {
    addBranch,
    applySafeConflictMerge,
    addConditionRoute,
    addNode,
    amount,
    archive,
    busy,
    conflict,
    copyNode,
    createDraft,
    decisions,
    deleteNode,
    draft,
    drafts,
    editable,
    errors,
    hasPendingChanges,
    json,
    keyword,
    loadDrafts,
    moveNode,
    nodeOptions,
    openDraft,
    preflight,
    publish,
    published,
    redo,
    redoStack,
    reload,
    reloadServerVersion,
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
