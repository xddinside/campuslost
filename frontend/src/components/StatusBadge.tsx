import { Badge } from '#/components/ui/badge.tsx'
import { claimLabel, statusLabel } from '../lib/format'
import type { ClaimStatus, ItemStatus } from '../lib/types'

const STATUS_VARIANT: Record<ItemStatus, 'success' | 'warning' | 'info' | 'secondary'> = {
  OPEN: 'success',
  RESERVED: 'warning',
  RESOLVED: 'info',
  CLOSED: 'secondary',
}

const CLAIM_VARIANT: Record<ClaimStatus, 'warning' | 'info' | 'success' | 'destructive' | 'secondary'> = {
  PENDING: 'warning',
  APPROVED: 'info',
  COMPLETED: 'success',
  REJECTED: 'destructive',
  CANCELLED: 'secondary',
}

function StatusBadge({ status }: { status: ItemStatus }) {
  return <Badge variant={STATUS_VARIANT[status]}>{statusLabel[status]}</Badge>
}

function ClaimBadge({ status }: { status: ClaimStatus }) {
  return <Badge variant={CLAIM_VARIANT[status]}>{claimLabel[status]}</Badge>
}

export { ClaimBadge, StatusBadge }
