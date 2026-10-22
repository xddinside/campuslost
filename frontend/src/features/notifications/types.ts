export type NotificationType =
  | 'MATCH_FOUND'
  | 'CLAIM_APPROVED'
  | 'CLAIM_REJECTED'
  | 'HANDOVER_COMPLETED'

export interface NotificationItem {
  id: number
  type: NotificationType
  title: string
  body: string
  itemId: number | null
  claimId?: number | null
  lostReportId?: number | null
  createdAt: string
  readAt: string | null
}

export interface NotificationPage {
  notifications: NotificationItem[]
  unreadCount: number
  nextBeforeId: number | null
}

export const NOTIFICATIONS_PAGE_SIZE = 20
export const NOTIFICATIONS_POLL_MS = 20_000
