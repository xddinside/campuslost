import type { ClaimStatus, ItemStatus } from './types'

export function formatDate(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

export function timeAgo(iso: string | null): string {
  if (!iso) return '—'
  const seconds = Math.floor((Date.now() - new Date(iso).getTime()) / 1000)
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days}d ago`
  return formatDate(iso)
}

/** ISO date (`yyyy-mm-dd`) suitable for the date picker, in local time. */
export function dateInputValue(iso: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 10)
}

export function dateInputToIso(value: string): string {
  return new Date(`${value}T12:00:00`).toISOString()
}

export const statusLabel: Record<ItemStatus, string> = {
  OPEN: 'Open',
  RESERVED: 'Reserved',
  RESOLVED: 'Resolved',
  CLOSED: 'Closed',
}

export const claimLabel: Record<ClaimStatus, string> = {
  PENDING: 'Pending',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
  COMPLETED: 'Completed',
}

export function isActiveClaim(status: ClaimStatus): boolean {
  return status === 'PENDING' || status === 'APPROVED'
}

export const CATEGORIES = [
  'Electronics',
  'Clothing',
  'Books',
  'Keys',
  'Wallet & Purses',
  'ID & Documents',
  'Jewelry',
  'Bags',
  'Other',
]
