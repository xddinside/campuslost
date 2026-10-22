import type { NotificationItem } from './types'

export interface NotificationTarget {
  to: '/items/$id' | '/claims/$claimId' | '/my'
  params?: Record<string, string>
  search?: { claimId?: number; lostReportId?: number; itemId?: number }
}

/**
 * The destination an alert opens:
 * - claim status alerts open that claim's history page, which stays
 *   reachable even after the report is soft-deleted;
 * - match alerts keep the lost report link so claim forms stay prefilled;
 * - alerts without a report fall back to the account activity page.
 */
export function notificationLinkTarget(notification: NotificationItem): NotificationTarget {
  if (notification.claimId) {
    return {
      to: '/claims/$claimId',
      params: { claimId: String(notification.claimId) },
      search: notification.itemId ? { itemId: notification.itemId } : undefined,
    }
  }
  if (!notification.itemId) {
    return { to: '/my' }
  }
  const params = { id: String(notification.itemId) }
  if (notification.type === 'MATCH_FOUND' && notification.lostReportId) {
    return { to: '/items/$id', params, search: { lostReportId: notification.lostReportId } }
  }
  return { to: '/items/$id', params }
}
