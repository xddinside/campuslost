import type {
  ClaimEvidenceProps,
  OwnershipAnswerFieldProps,
  OwnershipQuestionFieldProps,
} from '../../lib/feature-contracts'

/**
 * Ownership feature entrypoints. Empty on fixed main: the server fails closed
 * for question-bearing claims until this feature installs its policy.
 */
export function OwnershipQuestionField(_props: OwnershipQuestionFieldProps) {
  return null
}

export function OwnershipAnswerField(_props: OwnershipAnswerFieldProps) {
  return null
}

export function ClaimEvidence(_props: ClaimEvidenceProps) {
  return null
}
