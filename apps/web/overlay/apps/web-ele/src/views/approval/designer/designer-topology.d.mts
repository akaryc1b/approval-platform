export type ApprovalDesignerTopologyNodeKind =
  | 'APPROVAL'
  | 'CONDITION'
  | 'END'
  | 'HANDLE'
  | 'PARALLEL_JOIN'
  | 'PARALLEL_SPLIT'
  | 'START';

export interface ApprovalDesignerTopologyNode {
  branches?: ReadonlyArray<{ next: string }>;
  defaultNext?: string;
  id: string;
  kind: ApprovalDesignerTopologyNodeKind;
  name: string;
  next?: string;
  rejectNext?: string;
  routes?: ReadonlyArray<{ next: string }>;
}

export interface ApprovalDesignerTopologyIndex<
  T extends ApprovalDesignerTopologyNode = ApprovalDesignerTopologyNode,
> {
  byId: ReadonlyMap<string, T>;
  incomingById: ReadonlyMap<string, readonly string[]>;
  kindCounts: Readonly<Record<ApprovalDesignerTopologyNodeKind, number>>;
  orderById: ReadonlyMap<string, number>;
  orderedNodes: readonly T[];
  outgoingById: ReadonlyMap<string, readonly string[]>;
}

export interface ApprovalDesignerDeletionImpact {
  deletable: boolean;
  incomingNodeIds: string[];
  joinNodeId?: string;
  nodeId: string;
  outgoingNodeIds: string[];
  reason?: string;
}

export function buildApprovalDesignerTopologyIndex<
  T extends ApprovalDesignerTopologyNode,
>(nodes: readonly T[]): ApprovalDesignerTopologyIndex<T>;

export function filterApprovalDesignerNodes<
  T extends ApprovalDesignerTopologyNode,
>(
  index: ApprovalDesignerTopologyIndex<T>,
  search: string,
  kinds?: readonly ApprovalDesignerTopologyNodeKind[],
): readonly T[];

export function resolveApprovalDesignerNodeId(
  index: ApprovalDesignerTopologyIndex,
  subject?: string,
): string | undefined;

export function describeApprovalDesignerDeletion(
  index: ApprovalDesignerTopologyIndex,
  nodeId: string,
): ApprovalDesignerDeletionImpact;

export function collectOutgoingNodeIds(
  node: ApprovalDesignerTopologyNode,
): readonly string[];
