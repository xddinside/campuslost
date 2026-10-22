import type { Claim, Item, ItemType } from './types'

export interface FieldValidity {
  valid: boolean
  message?: string
}

export interface MatchPanelProps {
  item: Item
}

export interface OwnershipQuestionFieldProps {
  type: ItemType
  isNew: boolean
  value: string
  onChange: (value: string) => void
  error?: string | null
  onValidityChange?: (state: FieldValidity) => void
}

export interface OwnershipAnswerFieldProps {
  itemId: number
  value: string
  onChange: (value: string) => void
  error?: string | null
  onValidityChange?: (state: FieldValidity) => void
}

export interface ClaimEvidenceProps {
  claimId: number
}

export interface HandoverPanelProps {
  claim: Claim
  onChanged: () => void
}

export interface NotificationBellProps {
  /** Reserved for future placement options; the fixed Header passes nothing. */
  className?: string
}
