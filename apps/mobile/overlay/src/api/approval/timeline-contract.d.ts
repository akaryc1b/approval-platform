import '@/api/approval';

declare module '@/api/approval' {
  interface ApprovalTimelineItem {
    schemaName: string
    schemaVersion: number
    summary: string
  }
}

export {}
