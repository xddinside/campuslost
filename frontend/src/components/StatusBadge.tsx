import { Badge } from '#/components/ui/badge.tsx'
import { claimLabel, statusLabel } from '../lib/format'
import type { ClaimStatus, ItemStatus } from '../lib/types'

function StatusBadge({ status }: { status: ItemStatus }) {
  const variant = status === 'OPEN' ? 'secondary' : 'outline'

  return <Badge variant={variant}>{statusLabel[status]}</Badge>
}

function ClaimBadge({ status }: { status: ClaimStatus }) {
  const variant =
    status === 'APPROVED' ? 'default' : status === 'PENDING' ? 'secondary' : 'outline'

  return <Badge variant={variant}>{claimLabel[status]}</Badge>
}

export { ClaimBadge, StatusBadge }
