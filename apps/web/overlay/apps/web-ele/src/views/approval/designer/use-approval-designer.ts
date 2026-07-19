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
import {
  buildApprovalDesignerTopologyIndex,
  describeApprovalDesignerDeletion,
  filterApprovalDesignerNodes,
  resolveApprovalDesignerNodeId,
} from './designer-topology.mjs';
import type { ApprovalDesignerTopologyIndex } from './designer-topology.mjs';

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
  const nodeSearch = ref('');
  const nodeKindFilter = ref<ApprovalNode['kind'][]>([]);
  const nodeRenderLimit = ref(120);
  const collapsedBranchNodeIds = ref<Set<string>>(new Set());

  let suspended = true;
  let saveTimer: ReturnType<typeof setTimeout> | undefined;
  let sequence = 1;

  const topology = computed<ApprovalDesignerTopologyIndex<ApprovalNode>>(() =>
    buildApprovalDesignerTopologyIndex(draft.value?.definition.nodes ?? []),
  );
  const selectedNode = computed(() => topology.value.byId.get(selectedNodeId.value));
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
  const nodeOptions = computed(() => topology.value.orderedNodes.map(node => ({
    label: `${node.name} · ${node.id}`,
    value: node.id,
  })));
  const approvalNodes = computed(() => topology.value.orderedNodes.filter(
    (node): node is ApprovalStep => node.kind === 'APPROVAL',
  ));
  const visibleNodes = computed(() => filterApprovalDesignerNodes(
    topology.value,
    nodeSearch.value,
    nodeKindFilter.value,
  ));
  const renderedNodes = computed(() => visibleNodes.value.slice(0, nodeRenderLimit.value));
  const hasMoreVisibleNodes = computed(
    () => renderedNodes.value.length < visibleNodes.value.length,
  );
  const filtersActive = computed(
    () => Boolean(nodeSearch.value.trim()) || nodeKindFilter.value.length > 0,
  );
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
  watch([nodeSearch, nodeKindFilter], () => {
    nodeRenderLimit.value = 120;
  }, { deep: true });
  watch(
    () => draft.value?.formPackage.packageVersion,
    () => markChanged(),
    { flush: 'sync' },
  );
  if (typeof window !== 'undefined') {
    window.addEventListener('beforeunload', handleBeforeUnload);
    window.addEventListener('keydown', handleKeyboardShortcut);
  }
  onBeforeRouteLeave(() => confirmDiscardChanges('离开流程设计器'));
  onBeforeUnmount(() => {
    if (saveTimer) clearTimeout(saveTimer);
    if (typeof window !== 'undefined') {
      window.removeEventListener('beforeunload', handleBeforeUnload);
      window.removeEventListener('keydown', handleKeyboardShortcut);
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
      nodeSearch.value = '';
      nodeKindFilter.value = [];
      nodeRenderLimit.value = 120;
      collapsedBranchNodeIds.value = new Set(
        loaded.definition.nodes.length >= 100
          ? loaded.definition.nodes
              .filter(node => ['CONDITION', 'PARALLEL_SPLIT'].includes(node.kind))
              .map(node => node.id)
          : [],
      );
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
    const end = topology.value.orderedNodes.find(node => node.kind === 'END');
    const predecessorId = end
      ? topology.value.incomingById.get(end.id)?.[0]
      : undefined;
    const predecessor = predecessorId
      ? topology.value.byId.get(predecessorId)
      : undefined;
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
    const entry = branch ? topology.value.byId.get(branch.next) : undefined;
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

  async function deleteNode() {
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
    approvalNodes,
    amount,
    archive,
    busy,
    collapseAllBranches,
    collapsedBranchNodeIds,
    conflict,
    copyNode,
    createDraft,
    decisions,
    deleteNode,
    draft,
    drafts,
    editable,
    expandAllBranches,
    errors,
    filtersActive,
    focusNode,
    hasMoreVisibleNodes,
    hasPendingChanges,
    json,
    keyword,
    loadDrafts,
    moveNode,
    nodeKindFilter,
    nodeOptions,
    nodeSearch,
    openDraft,
    preflight,
    publish,
    published,
    renderedNodes,
    redo,
    redoStack,
    reload,
    reloadServerVersion,
    resolveNodeId,
    removeBranch,
    removeConditionRoute,
    save,
    saveState,
    showMoreNodes,
    selectedApproval,
    selectedCondition,
    selectedHandle,
    selectedNode,
    selectedNodeId,
    selectedParallel,
    simulate,
    simulation,
    status,
    toggleBranchCollapse,
    topology,
    undo,
    undoStack,
    validate,
    validation,
    visibleNodes,
    warnings,
    isBranchCollapsed,
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
