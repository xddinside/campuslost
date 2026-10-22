import { useEffect, useId, useMemo, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { LockKey, ShieldCheck } from '@phosphor-icons/react'
import { Input } from '#/components/ui/input.tsx'
import { Label } from '#/components/ui/label.tsx'
import { Textarea } from '#/components/ui/textarea.tsx'
import type {
  ClaimEvidenceProps,
  FieldValidity,
  OwnershipAnswerFieldProps,
  OwnershipQuestionFieldProps,
} from '../../lib/feature-contracts'
import { ApiError, api } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { queryKeys } from '../../lib/query'
import {
  ANSWER_MAX,
  QUESTION_MAX,
  answerValidity,
  questionValidity,
} from './types'
import type { ClaimEvidenceResult, OwnershipQuestion } from './types'
import './ownership.css'

/**
 * The finder's prompt. It is part of the private report input and must never
 * repeat identifying details that already appear in the public description.
 */
export function OwnershipQuestionField({
  type,
  isNew,
  value,
  onChange,
  error,
  onValidityChange,
}: OwnershipQuestionFieldProps) {
  const id = useId()
  const callback = useRef(onValidityChange)
  callback.current = onValidityChange

  const trimmed = value.trim()
  const validity = useMemo(() => questionValidity(value, isNew), [value, isNew])

  useEffect(() => {
    callback.current?.(validity)
  }, [validity])

  useEffect(() => {
    return () => callback.current?.({ valid: true })
  }, [])

  if (type !== 'FOUND') return null

  const message = error ?? (validity.valid ? null : validity.message)
  const helpId = `${id}-help`
  const errorId = `${id}-error`

  return (
    <section className="ownership-field" aria-labelledby={`${id}-label`}>
      <div className="ownership-field-head">
        <span className="ownership-eyebrow">
          <LockKey aria-hidden="true" size={14} weight="bold" />
          Private verification
        </span>
        <Label id={`${id}-label`} htmlFor={id}>
          Ownership question
        </Label>
      </div>

      <p className="ownership-help" id={helpId}>
        Ask about one detail that is deliberately missing from your public
        description — something the true owner would know. Never ask for a
        passcode, an ID number, or payment details.
      </p>

      <Input
        id={id}
        className="ownership-input"
        value={value}
        maxLength={QUESTION_MAX}
        onChange={(event) => onChange(event.target.value)}
        placeholder="e.g. What is engraved inside the bottle lid?"
        aria-invalid={message ? 'true' : undefined}
        aria-describedby={message ? `${helpId} ${errorId}` : helpId}
      />

      <div className="ownership-field-meta">
        <span className="tabular-nums">{trimmed.length}/{QUESTION_MAX}</span>
        {message ? (
          <span className="ownership-error" id={errorId}>{message}</span>
        ) : (
          <span className="ownership-hint">
            {trimmed ? 'Only signed-in claimants will see this.' : 'Add a question to post this found report.'}
          </span>
        )}
      </div>

      {!isNew && !trimmed && (
        <p className="ownership-note">
          <ShieldCheck aria-hidden="true" size={16} weight="fill" />
          This legacy report has no question. Reviewers will see “No question
          supplied”, and you can add one before a new claim is sent.
        </p>
      )}

      <p className="ownership-safety">
        Keep the answer out of the public description. If the item is
        identifiable by a public detail, ask about something else.
      </p>
    </section>
  )
}

/**
 * Loads the current prompt from the private endpoint. The answer is sent only
 * with the claim and is never written to the public report or the claim list.
 */
export function OwnershipAnswerField({
  itemId,
  value,
  onChange,
  error,
  onValidityChange,
}: OwnershipAnswerFieldProps) {
  const id = useId()
  const { user, hydrated } = useAuth()
  const userId = user?.id ?? 0
  const callback = useRef(onValidityChange)
  callback.current = onValidityChange

  const questionQuery = useQuery<OwnershipQuestion, ApiError>({
    queryKey: queryKeys.account(userId, 'item', itemId, 'ownership-question'),
    queryFn: () => api<OwnershipQuestion>(`/items/${itemId}/ownership-question`),
    enabled: hydrated && !!user,
    retry: false,
  })

  const question = questionQuery.data?.question?.trim() ?? null
  const hasQuestion = question !== null && question.length > 0

  const validity = useMemo<FieldValidity>(() => {
    if (!hydrated || !user) {
      return { valid: false, message: 'Sign in to answer the ownership question.' }
    }
    if (questionQuery.isPending) {
      return { valid: false, message: 'Loading the finder’s question…' }
    }
    if (questionQuery.isError) {
      return {
        valid: false,
        message: questionQuery.error instanceof ApiError
          ? questionQuery.error.message
          : 'Could not load the finder’s question.',
      }
    }
    return answerValidity(value, hasQuestion)
  }, [hydrated, user, questionQuery.isPending, questionQuery.isError,
      questionQuery.error, value, hasQuestion])

  useEffect(() => {
    callback.current?.(validity)
  }, [validity])

  useEffect(() => {
    return () => callback.current?.({ valid: true })
  }, [])

  if (!hydrated || !user) {
    return (
      <div className="ownership-answer-empty" role="status">
        Sign in to see the finder’s private question.
      </div>
    )
  }

  if (questionQuery.isPending) {
    return (
      <div className="ownership-answer-empty" role="status">
        Checking whether this report has an ownership question…
      </div>
    )
  }

  if (questionQuery.isError) {
    return (
      <div className="ownership-answer-empty ownership-answer-problem" role="alert">
        <LockKey aria-hidden="true" size={16} weight="bold" />
        <span>{questionQuery.error.message}</span>
      </div>
    )
  }

  if (!hasQuestion) {
    return (
      <div className="ownership-answer-empty">
        <span className="ownership-eyebrow">
          <ShieldCheck aria-hidden="true" size={14} weight="bold" />
          No question supplied
        </span>
        <p>
          This report has no private ownership question. Add a message explaining
          why the item is yours; the finder reviews it manually.
        </p>
      </div>
    )
  }

  const message = error ?? (validity.valid ? null : validity.message)
  const helpId = `${id}-help`
  const errorId = `${id}-error`

  return (
    <section className="ownership-answer" aria-labelledby={`${id}-label`}>
      <div className="ownership-question-card">
        <span className="ownership-eyebrow">
          <LockKey aria-hidden="true" size={14} weight="bold" />
          Finder’s private question
        </span>
        <p className="ownership-question-text" id={`${id}-label`}>{question}</p>
      </div>

      <Label className="ownership-answer-label" htmlFor={id}>Your answer</Label>
      <Textarea
        id={id}
        className="ownership-answer-input"
        value={value}
        maxLength={ANSWER_MAX}
        onChange={(event) => onChange(event.target.value)}
        placeholder="Describe the detail exactly as the finder asked. This stays private."
        aria-invalid={message ? 'true' : undefined}
        aria-describedby={message ? `${helpId} ${errorId}` : helpId}
      />
      <div className="ownership-field-meta">
        <span className="tabular-nums">{value.trim().length}/{ANSWER_MAX}</span>
        {message ? (
          <span className="ownership-error" id={errorId}>{message}</span>
        ) : (
          <span className="ownership-hint" id={helpId}>
            Only the finder and administrators can read this.
          </span>
        )}
      </div>
      <p className="ownership-safety">
        A matching answer is a lead, not proof. The finder still decides, and
        pickup is confirmed separately.
      </p>
    </section>
  )
}

/** Authorized evidence view for the claimant, the report owner, or an admin. */
export function ClaimEvidence({ claimId }: ClaimEvidenceProps) {
  const { user, hydrated } = useAuth()
  const userId = user?.id ?? 0

  const evidenceQuery = useQuery<ClaimEvidenceResult, ApiError>({
    queryKey: queryKeys.account(userId, 'claim', claimId, 'evidence'),
    queryFn: () => api<ClaimEvidenceResult>(`/claims/${claimId}/evidence`),
    enabled: hydrated && !!user,
    retry: false,
  })

  if (!hydrated || !user) return null

  if (evidenceQuery.isPending) {
    return (
      <section className="ownership-evidence ownership-evidence-muted" aria-busy="true">
        Loading private ownership evidence…
      </section>
    )
  }

  if (evidenceQuery.isError) {
    return (
      <section className="ownership-evidence ownership-evidence-muted" role="status">
        <span className="ownership-eyebrow">
          <LockKey aria-hidden="true" size={14} weight="bold" />
          Ownership evidence
        </span>
        <p>Private evidence isn’t available for this response.</p>
      </section>
    )
  }

  const question = evidenceQuery.data.question?.trim() ?? ''
  const answer = evidenceQuery.data.answer?.trim() ?? ''

  return (
    <section className="ownership-evidence" aria-label="Private ownership evidence">
      <header className="ownership-evidence-head">
        <span className="ownership-eyebrow">
          <LockKey aria-hidden="true" size={14} weight="bold" />
          Ownership evidence
        </span>
        <span className="ownership-private-pill">Private review</span>
      </header>

      {question ? (
        <dl className="ownership-evidence-list">
          <div>
            <dt>Question</dt>
            <dd>{question}</dd>
          </div>
          <div>
            <dt>Answer</dt>
            <dd>{answer || 'No answer submitted.'}</dd>
          </div>
        </dl>
      ) : (
        <p className="ownership-evidence-empty">
          No ownership question was supplied for this report. Review the response
          manually, using the report’s public details.
        </p>
      )}

      <p className="ownership-evidence-foot">
        Evidence is never shown publicly, in notifications, or to other
        claimants. A typed answer does not prove identity on its own.
      </p>
    </section>
  )
}
