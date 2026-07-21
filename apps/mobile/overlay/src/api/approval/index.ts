import {
  mobileApprovalMutationHeaders,
  mobileApprovalRequest,
} from '@/api/approval/transport'

export interface PendingTaskItem {
  amount: number
  businessKey: string
  definitionKey: string
  initiatorId: string
  instanceId: string
  purchaseOrderReference: string
  supplier: string
  taskCreatedAt: string
  taskDefinitionKey: string
  taskId: string
  taskName: string
  taskUpdatedAt: string
}

export interface PendingTaskPage {
  hasMore: boolean
  items: PendingTaskItem[]
  limit: number
  offset: number
  total: number
}

export interface TransferCandidate {
  displayName: string
  userId: string
}

export interface PendingTaskDetails {
  amount: number
  attachmentIds: string[]
  businessKey: string
  compilerVersion: string
  contentHash: string
  definitionKey: string
  definitionVersion: number
  formKey: string
  formVersion: number
  initiatorId: string
  instanceCreatedAt: string
  instanceId: string
  instanceUpdatedAt: string
  purchaseOrderReference: string
  supplier: string
  taskCreatedAt: string
  taskDefinitionKey: string
  taskId: string
  taskName: string
  taskUpdatedAt: string
  transferCandidates: TransferCandidate[]
}

export interface StartedInstanceItem {
  amount: number
  attachmentIds: string[]
  businessKey: string
  createdAt: string
  currentTaskDefinitionKey?: string
  currentTaskName?: string
  definitionKey: string
  initiatorId: string
  instanceId: string
  messageCount: number
  purchaseOrderReference: string
  readCount: number
  status: 'COMPLETED' | 'REJECTED' | 'RUNNING' | 'WITHDRAWN'
  supplier: string
  updatedAt: string
  withdrawable: boolean
}

export interface StartedInstancePage {
  hasMore: boolean
  items: StartedInstanceItem[]
  limit: number
  offset: number
  total: number
}

export interface ProcessedTaskItem {
  amount: number
  businessKey: string
  completedAt: string
  definitionKey: string
  initiatorId: string
  instanceId: string
  instanceStatus: 'COMPLETED' | 'REJECTED' | 'RUNNING' | 'WITHDRAWN'
  purchaseOrderReference: string
  retrievable: boolean
  supplier: string
  taskDefinitionKey: string
  taskId: string
  taskName: string
}

export interface ProcessedTaskPage {
  hasMore: boolean
  items: ProcessedTaskItem[]
  limit: number
  offset: number
  total: number
}

export interface ApprovalTimelineItem {
  action: string
  aggregateId: string
  aggregateType: string
  attributes: Record<string, string>
  eventId: string
  occurredAt: string
  operatorId: string
  requestId: string
  traceId?: string
}

export interface ApprovalTimeline {
  instanceId: string
  items: ApprovalTimelineItem[]
}

export interface TaskActionResult {
  activeTasks: PendingTaskItem[]
  completedAt: string
  completedTaskId: string
  instanceId: string
  instanceStatus: 'COMPLETED' | 'RUNNING'
}

export interface TransferResult {
  instanceId: string
  previousAssigneeId: string
  targetAssigneeId: string
  taskId: string
  transferredAt: string
}

export interface WithdrawResult {
  instanceId: string
  instanceStatus: 'WITHDRAWN'
  withdrawnAt: string
}

export interface RetrieveResult {
  activeTasks: PendingTaskItem[]
  completedTaskId: string
  instanceId: string
  retrievedAt: string
}

export interface ApprovalUserOption {
  displayName: string
  userId: string
}

export interface CollaborationOptions {
  activeAssignees: ApprovalUserOption[]
  canUrge: boolean
  copyCandidates: ApprovalUserOption[]
  instanceId: string
}

export interface MessageActionResult {
  createdAt: string
  createdMessages: number
  instanceId: string
  recipients: string[]
}

export interface ApprovalMessageItem {
  amount: number
  body: string
  businessKey: string
  createdAt: string
  instanceId: string
  instanceStatus: StartedInstanceItem['status']
  messageId: string
  messageType: 'COPY' | 'URGE'
  metadata: Record<string, string>
  purchaseOrderReference: string
  read: boolean
  readAt?: string
  senderId: string
  supplier: string
  taskId?: string
  title: string
}

export interface ApprovalMessagePage {
  hasMore: boolean
  items: ApprovalMessageItem[]
  limit: number
  offset: number
  total: number
}

export interface MessageReceipt {
  messageId: string
  messageType: 'COPY' | 'URGE'
  read: boolean
  readAt?: string
  recipientId: string
  senderId: string
  sentAt: string
}

interface PageParameters {
  keyword?: string
  limit: number
  offset: number
}

type TaskAction = 'approve' | 'reject' | 'resubmit'

function pageQuery(parameters: PageParameters) {
  const query: string[] = [
    `limit=${encodeURIComponent(String(parameters.limit))}`,
    `offset=${encodeURIComponent(String(parameters.offset))}`,
  ]
  const keyword = parameters.keyword?.trim()
  if (keyword) query.push(`keyword=${encodeURIComponent(keyword)}`)
  return query.join('&')
}

export function findPendingTasks(parameters: PageParameters) {
  return mobileApprovalRequest<PendingTaskPage>(
    `/approval/tasks/pending?${pageQuery(parameters)}`,
  )
}

export function findStartedInstances(parameters: PageParameters) {
  return mobileApprovalRequest<StartedInstancePage>(
    `/approval/instances/started?${pageQuery(parameters)}`,
  )
}

export function findProcessedTasks(parameters: PageParameters) {
  return mobileApprovalRequest<ProcessedTaskPage>(
    `/approval/tasks/processed?${pageQuery(parameters)}`,
  )
}

export function findPendingTask(taskId: string) {
  return mobileApprovalRequest<PendingTaskDetails>(
    `/approval/tasks/pending/${encodeURIComponent(taskId)}`,
  )
}

export function findApprovalTimeline(instanceId: string) {
  return mobileApprovalRequest<ApprovalTimeline>(
    `/approval/instances/${encodeURIComponent(instanceId)}/timeline`,
  )
}

function submitTaskAction(taskId: string, action: TaskAction, comment: string) {
  return mobileApprovalRequest<TaskActionResult>(
    `/approval/tasks/${encodeURIComponent(taskId)}/${action}`,
    {
      method: 'POST',
      data: { comment: comment.trim() || null },
      header: mobileApprovalMutationHeaders(`mobile-${action}`),
    },
  )
}

export function approveTask(taskId: string, comment: string) {
  return submitTaskAction(taskId, 'approve', comment)
}

export function rejectTask(taskId: string, comment: string) {
  return submitTaskAction(taskId, 'reject', comment)
}

export function resubmitTask(taskId: string, comment: string) {
  return submitTaskAction(taskId, 'resubmit', comment)
}

export function transferTask(
  taskId: string,
  targetUserId: string,
  comment: string,
) {
  return mobileApprovalRequest<TransferResult>(
    `/approval/tasks/${encodeURIComponent(taskId)}/transfer`,
    {
      method: 'POST',
      data: { comment: comment.trim(), targetUserId },
      header: mobileApprovalMutationHeaders('mobile-transfer'),
    },
  )
}

export function withdrawInstance(instanceId: string, comment: string) {
  return mobileApprovalRequest<WithdrawResult>(
    `/approval/instances/${encodeURIComponent(instanceId)}/withdraw`,
    {
      method: 'POST',
      data: { comment: comment.trim() || null },
      header: mobileApprovalMutationHeaders('mobile-withdraw'),
    },
  )
}

export function retrieveTask(taskId: string, comment: string) {
  return mobileApprovalRequest<RetrieveResult>(
    `/approval/tasks/${encodeURIComponent(taskId)}/retrieve`,
    {
      method: 'POST',
      data: { comment: comment.trim() || null },
      header: mobileApprovalMutationHeaders('mobile-retrieve'),
    },
  )
}

export function findCollaborationOptions(instanceId: string) {
  return mobileApprovalRequest<CollaborationOptions>(
    `/approval/instances/${encodeURIComponent(instanceId)}/collaboration-options`,
  )
}

export function urgeInstance(instanceId: string, comment: string) {
  return mobileApprovalRequest<MessageActionResult>(
    `/approval/instances/${encodeURIComponent(instanceId)}/urge`,
    {
      method: 'POST',
      data: { comment: comment.trim() || null },
      header: mobileApprovalMutationHeaders('mobile-urge'),
    },
  )
}

export function copyInstance(
  instanceId: string,
  recipientIds: string[],
  comment: string,
) {
  return mobileApprovalRequest<MessageActionResult>(
    `/approval/instances/${encodeURIComponent(instanceId)}/copy`,
    {
      method: 'POST',
      data: { comment: comment.trim() || null, recipientIds },
      header: mobileApprovalMutationHeaders('mobile-copy'),
    },
  )
}

export function findMessageReceipts(instanceId: string) {
  return mobileApprovalRequest<MessageReceipt[]>(
    `/approval/instances/${encodeURIComponent(instanceId)}/receipts`,
  )
}

export function findMessages(unreadOnly: boolean, limit: number, offset: number) {
  const query = [
    `unreadOnly=${encodeURIComponent(String(unreadOnly))}`,
    `limit=${encodeURIComponent(String(limit))}`,
    `offset=${encodeURIComponent(String(offset))}`,
  ].join('&')
  return mobileApprovalRequest<ApprovalMessagePage>(`/approval/messages?${query}`)
}

export function findUnreadMessageCount() {
  return mobileApprovalRequest<{ unread: number }>('/approval/messages/unread-count')
}

export function markMessageRead(messageId: string) {
  return mobileApprovalRequest<{ firstRead: boolean, messageId: string, readAt: string }>(
    `/approval/messages/${encodeURIComponent(messageId)}/read`,
    {
      method: 'POST',
      header: mobileApprovalMutationHeaders('mobile-message-read'),
    },
  )
}

export function markAllMessagesRead() {
  return mobileApprovalRequest<{ readAt: string, updatedMessages: number }>(
    '/approval/messages/read-all',
    {
      method: 'POST',
      header: mobileApprovalMutationHeaders('mobile-message-read-all'),
    },
  )
}
