import { mobileApprovalRequest } from '@/api/approval/transport'

export type ParticipantSlaStatus = 'ACTIVE' | 'PAUSED' | 'TERMINAL'
export type ParticipantTimingStatus = 'ACTIVE' | 'DUE' | 'OVERDUE' | 'PAUSED' | 'UPCOMING'

export interface ParticipantTaskSla {
  dueAt: string
  nextReminderAt?: string
  observedAt: string
  originalResponsibleUserId: string
  overdueAt: string
  remainingMillis: number
  responsibilityChanged: boolean
  responsibleUserId: string
  slaInstanceId: string
  status: ParticipantSlaStatus
  taskId: string
  timeZone: string
  timingStatus: ParticipantTimingStatus
}

export function findParticipantTaskSla(taskId: string) {
  return mobileApprovalRequest<ParticipantTaskSla | undefined>(
    `/approval/tasks/${encodeURIComponent(taskId)}/sla`,
    { allowNotFound: true },
  )
}
