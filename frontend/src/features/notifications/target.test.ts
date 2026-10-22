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
  it('opens claim status alerts on that claim’s history page', () => {
    for (const type of ['CLAIM_APPROVED', 'CLAIM_REJECTED', 'HANDOVER_COMPLETED'] as const) {
      expect(notificationLinkTarget(alert({ type, claimId: 9 }))).toEqual({
        to: '/claims/$claimId',
        params: { claimId: '9' },
        search: { itemId: 42 },
      })
    }
  })

  it('reaches claim history even when the report cannot be opened publicly', () => {
    const target = notificationLinkTarget(
      alert({ type: 'HANDOVER_COMPLETED', claimId: 9, itemId: null }),
    )
    expect(target.to).toBe('/claims/$claimId')
    expect(target.params).toEqual({ claimId: '9' })
  })

  it('keeps the lost report link on match alerts', () => {
    expect(notificationLinkTarget(alert({ type: 'MATCH_FOUND', lostReportId: 17 }))).toEqual({
      to: '/items/$id',
      params: { id: '42' },
      search: { lostReportId: 17 },
    })
  })

  it('still reaches claim history when the alert carries no report id', () => {
    expect(notificationLinkTarget(alert({ type: 'CLAIM_APPROVED', itemId: null, claimId: 3 })))
      .toEqual({ to: '/claims/$claimId', params: { claimId: '3' }, search: undefined })
  })

  it('falls back to the account page only for claim-less alerts without a report', () => {
    expect(notificationLinkTarget(alert({ type: 'CLAIM_APPROVED', itemId: null })))
      .toEqual({ to: '/my' })
  })

  it('opens the report plainly when nothing needs selecting', () => {
    expect(notificationLinkTarget(alert({ type: 'MATCH_FOUND' }))).toEqual({
      to: '/items/$id',
      params: { id: '42' },
    })
  })
})
