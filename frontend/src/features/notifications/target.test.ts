import { describe, expect, it } from 'vitest'
import { notificationLinkTarget } from './target'
import type { NotificationItem, NotificationType } from './types'

function alert(overrides: Partial<NotificationItem> & { type: NotificationType }): NotificationItem {
  return {
    id: 1,
    title: 'Alert',
    body: 'Something happened.',
    itemId: 42,
    createdAt: new Date().toISOString(),
    readAt: null,
    ...overrides,
  }
}

describe('notificationLinkTarget', () => {
  it('opens claim status alerts on that claim, not the default panel', () => {
    for (const type of ['CLAIM_APPROVED', 'CLAIM_REJECTED', 'HANDOVER_COMPLETED'] as const) {
      expect(notificationLinkTarget(alert({ type, claimId: 9 }))).toEqual({
        to: '/items/$id',
        params: { id: '42' },
        search: { claimId: 9 },
      })
    }
  })

  it('keeps the lost report link on match alerts', () => {
    expect(notificationLinkTarget(alert({ type: 'MATCH_FOUND', lostReportId: 17 }))).toEqual({
      to: '/items/$id',
      params: { id: '42' },
      search: { lostReportId: 17 },
    })
  })

  it('falls back to the account page when no report is attached', () => {
    expect(notificationLinkTarget(alert({ type: 'CLAIM_APPROVED', itemId: null, claimId: 3 })))
      .toEqual({ to: '/my' })
  })

  it('opens the report plainly when nothing needs selecting', () => {
    expect(notificationLinkTarget(alert({ type: 'MATCH_FOUND' }))).toEqual({
      to: '/items/$id',
      params: { id: '42' },
    })
  })
})
