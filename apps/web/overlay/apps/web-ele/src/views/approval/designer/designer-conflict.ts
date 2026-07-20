import type { ApprovalDesignDraft } from '#/api/approval/process-design';

export interface ApprovalDesignerSnapshot {
  definition: ApprovalDesignDraft['definition'];
  formPackageVersion: number;
  name: string;
}

export interface ApprovalDesignerValueChange {
  after: unknown;
  before: unknown;
  path: string;
  type: 'ADDED' | 'CHANGED' | 'REMOVED';
}

export interface ApprovalDesignerConflict {
  canAutoMerge: boolean;
  localChanges: ApprovalDesignerValueChange[];
  mergeableLocalChanges: ApprovalDesignerValueChange[];
  overlappingPaths: string[];
  serverChanges: ApprovalDesignerValueChange[];
}

export function snapshotApprovalDesignerDraft(
  draft: ApprovalDesignDraft,
): ApprovalDesignerSnapshot {
  return {
    definition: structuredClone(draft.definition),
    formPackageVersion: draft.formPackage.packageVersion,
    name: draft.name,
  };
}

export function analyzeDesignerConflict(
  base: ApprovalDesignerSnapshot,
  local: ApprovalDesignerSnapshot,
  server: ApprovalDesignerSnapshot,
): ApprovalDesignerConflict {
  const localChanges = diffDesignerValues(base, local);
  const serverChanges = diffDesignerValues(base, server);
  const overlappingPaths = [...new Set(
    localChanges
      .filter(localChange => serverChanges.some(
        serverChange => pathsOverlap(localChange.path, serverChange.path),
      ))
      .map(change => change.path),
  )].sort();
  const overlapSet = new Set(overlappingPaths);
  const mergeableLocalChanges = localChanges.filter(
    change => !overlapSet.has(change.path),
  );
  return {
    canAutoMerge: overlappingPaths.length === 0,
    localChanges,
    mergeableLocalChanges,
    overlappingPaths,
    serverChanges,
  };
}

export function mergeDesignerConflict(
  base: ApprovalDesignerSnapshot,
  local: ApprovalDesignerSnapshot,
  server: ApprovalDesignerSnapshot,
): ApprovalDesignerSnapshot {
  const analysis = analyzeDesignerConflict(base, local, server);
  if (!analysis.canAutoMerge) {
    throw new Error('存在重叠修改，不能自动合并');
  }
  const merged = structuredClone(server) as unknown as Record<string, unknown>;
  for (const change of orderChangesForApply(analysis.localChanges)) {
    applyChange(merged, change);
  }
  return merged as unknown as ApprovalDesignerSnapshot;
}

export function diffDesignerValues(
  before: unknown,
  after: unknown,
  path = '',
): ApprovalDesignerValueChange[] {
  if (deepEqual(before, after)) return [];
  if (Array.isArray(before) || Array.isArray(after)) {
    return [change(path, before, after)];
  }
  if (isRecord(before) && isRecord(after)) {
    const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].sort();
    return keys.flatMap((key) => {
      const nextPath = `${path}/${escapePointerToken(key)}`;
      if (!(key in before)) return [change(nextPath, undefined, after[key])];
      if (!(key in after)) return [change(nextPath, before[key], undefined)];
      return diffDesignerValues(before[key], after[key], nextPath);
    });
  }
  return [change(path, before, after)];
}

function change(
  path: string,
  before: unknown,
  after: unknown,
): ApprovalDesignerValueChange {
  return {
    after: cloneValue(after),
    before: cloneValue(before),
    path: path || '/',
    type: before === undefined
      ? 'ADDED'
      : after === undefined
        ? 'REMOVED'
        : 'CHANGED',
  };
}

function cloneValue(value: unknown) {
  return value === undefined ? undefined : structuredClone(value);
}

function pathsOverlap(left: string, right: string) {
  return left === right
    || left.startsWith(`${right}/`)
    || right.startsWith(`${left}/`);
}

function orderChangesForApply(changes: ApprovalDesignerValueChange[]) {
  return [...changes].sort((left, right) => {
    if (left.type === 'REMOVED' && right.type !== 'REMOVED') return -1;
    if (left.type !== 'REMOVED' && right.type === 'REMOVED') return 1;
    if (left.type === 'REMOVED' && right.type === 'REMOVED') {
      return right.path.split('/').length - left.path.split('/').length;
    }
    return left.path.localeCompare(right.path);
  });
}

function applyChange(
  root: Record<string, unknown>,
  changeValue: ApprovalDesignerValueChange,
) {
  const tokens = changeValue.path
    .split('/')
    .slice(1)
    .map(unescapePointerToken);
  if (tokens.length === 0) throw new Error('不支持替换设计器快照根节点');
  let target: Record<string, unknown> = root;
  for (const token of tokens.slice(0, -1)) {
    const child = target[token];
    if (!isRecord(child)) {
      target[token] = {};
    }
    target = target[token] as Record<string, unknown>;
  }
  const key = tokens.at(-1)!;
  if (changeValue.type === 'REMOVED') {
    delete target[key];
  } else {
    target[key] = cloneValue(changeValue.after);
  }
}

function deepEqual(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) return true;
  if (Array.isArray(left) && Array.isArray(right)) {
    return left.length === right.length
      && left.every((value, index) => deepEqual(value, right[index]));
  }
  if (isRecord(left) && isRecord(right)) {
    const leftKeys = Object.keys(left).sort();
    const rightKeys = Object.keys(right).sort();
    return leftKeys.length === rightKeys.length
      && leftKeys.every((key, index) => key === rightKeys[index]
        && deepEqual(left[key], right[key]));
  }
  return false;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function escapePointerToken(value: string) {
  return value.replaceAll('~', '~0').replaceAll('/', '~1');
}

function unescapePointerToken(value: string) {
  return value.replaceAll('~1', '/').replaceAll('~0', '~');
}
