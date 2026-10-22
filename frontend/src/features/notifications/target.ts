import type { NotificationItem } from './types'

export interface NotificationTarget {
  to: '/items/$id' | '/my'
  params?: { id: string }
  search?: { claimId?: number; lostReportId?: number }
}

/**
 * The destination an alert opens:
 * - claim status alerts select that claim's history on the report, since
 *   terminal claims are not the active claim shown by default;
 * - match alerts keep the lost report link so claim forms stay prefilled;
 * - alerts without a report fall back to the account activity page.
 */
export function notificationLinkTarget(notification: NotificationItem): NotificationTarget {
  if (!notification.itemId) {
    return { to: '/my' }
  }
  const params = { id: String(notification.itemId) }
  if (notification.claimId) {
    return { to: '/items/$id', params, search: { claimId: notification.claimId } }
  }
  if (notification.type === 'MATCH_FOUND' && notification.lostReportId) {
    return { to: '/items/$id', params, search: { lostReportId: notification.lostReportId } }
  }
  return { to: '/items/$id', params }
}
