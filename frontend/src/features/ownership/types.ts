export interface OwnershipQuestion {
  itemId: number
  question: string | null
  required: boolean
}

export interface ClaimEvidenceResult {
  claimId: number
  question: string | null
  answer: string | null
}

export const QUESTION_MIN = 10
export const QUESTION_MAX = 200
export const ANSWER_MIN = 10
export const ANSWER_MAX = 2000

export function questionValidity(value: string, isNew: boolean): {
  valid: boolean
  message?: string
} {
  const trimmed = value.trim()
  if (!trimmed) {
    return isNew
      ? {
          valid: false,
          message: 'Add a question only the true owner could answer.',
        }
      : { valid: true }
  }
  if (trimmed.length < QUESTION_MIN) {
    return {
      valid: false,
      message: `Use at least ${QUESTION_MIN} characters so the question is meaningful.`,
    }
  }
  if (trimmed.length > QUESTION_MAX) {
    return {
      valid: false,
      message: `Keep the question under ${QUESTION_MAX} characters.`,
    }
  }
  return { valid: true }
}

export function answerValidity(value: string, hasQuestion: boolean): {
  valid: boolean
  message?: string
} {
  if (!hasQuestion) return { valid: true }
  const trimmed = value.trim()
  if (!trimmed) {
    return {
      valid: false,
      message: 'Answer the finder’s ownership question.',
    }
  }
  if (trimmed.length < ANSWER_MIN) {
    return {
      valid: false,
      message: `Use at least ${ANSWER_MIN} characters in your answer.`,
    }
  }
  if (trimmed.length > ANSWER_MAX) {
    return {
      valid: false,
      message: `Keep the answer under ${ANSWER_MAX} characters.`,
    }
  }
  return { valid: true }
}
