export type ApprovalNodeType =
  | 'START'
  | 'APPROVAL'
  | 'HANDLE'
  | 'CC'
  | 'CONDITION'
  | 'PARALLEL'
  | 'SUB_PROCESS'
  | 'TIMER'
  | 'AUTOMATION'
  | 'WEBHOOK'
  | 'END';

export interface ApprovalNode {
  id: string;
  type: ApprovalNodeType;
  name?: string;
  next?: string;
  config?: Record<string, unknown>;
}

export interface ApprovalDefinition {
  schemaVersion: '1.0';
  definitionKey: string;
  name: string;
  start: string;
  nodes: ApprovalNode[];
}

export interface ValidationProblem {
  code: string;
  message: string;
  nodeId?: string;
}

export function validateDefinition(definition: ApprovalDefinition): ValidationProblem[] {
  const problems: ValidationProblem[] = [];
  const ids = new Set<string>();

  for (const node of definition.nodes) {
    if (ids.has(node.id)) {
      problems.push({ code: 'DUPLICATE_NODE_ID', message: `Duplicate node id: ${node.id}`, nodeId: node.id });
    }
    ids.add(node.id);
  }

  if (!ids.has(definition.start)) {
    problems.push({ code: 'START_NODE_NOT_FOUND', message: `Start node not found: ${definition.start}` });
  }

  for (const node of definition.nodes) {
    if (node.next && !ids.has(node.next)) {
      problems.push({ code: 'NEXT_NODE_NOT_FOUND', message: `Next node not found: ${node.next}`, nodeId: node.id });
    }
  }

  return problems;
}
