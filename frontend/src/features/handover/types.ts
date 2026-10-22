import type { Claim } from '../../lib/types'

export type HandoverState = 'NOT_ISSUED' | 'READY' | 'EXPIRED' | 'LOCKED' | 'COMPLETED'

/** Participant status view. Never includes a code or digest. */
export interface HandoverStatus {
  claimId: number
  state: HandoverState
  expiresAt: string | null
  attemptsRemaining: number | null
  verifiedAt: string | null
}

/** Plaintext code, returned only by issuance and held in component memory. */
export interface HandoverCode {
  code: string
  expiresAt: string
}

export interface HandoverVerification {
  claim: Claim
  verifiedAt: string
}
