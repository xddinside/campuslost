import { useMutation, useQuery } from '@tanstack/react-query'
import {
  ArrowClockwise,
  CheckCircle,
  Clock,
  Key,
  LockKey,
  ShieldCheck,
  WarningCircle,
} from '@phosphor-icons/react'
import { useEffect, useState, type ReactNode } from 'react'
import { Alert, AlertDescription } from '#/components/ui/alert.tsx'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '#/components/ui/alert-dialog.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Input } from '#/components/ui/input.tsx'
import { Label } from '#/components/ui/label.tsx'
import { Textarea } from '#/components/ui/textarea.tsx'
import { api } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { HandoverPanelProps } from '../../lib/feature-contracts'
import { queryKeys } from '../../lib/query'
import type {
  HandoverCode,
  HandoverState,
  HandoverStatus,
  HandoverVerification,
} from './types'

/**
 * OTP pickup handover for an approved (or completed) found-item claim.
 *
 * The plaintext code exists only in this component's memory after issuance; it
 * is never written to a URL, a query cache, a notification or the DOM outside
 * the visible code itself.
 */
export function HandoverPanel({ claim, onChanged }: HandoverPanelProps) {
  const { user, hydrated } = useAuth()
  const now = useNow()

  const isFound = claim.itemType === 'FOUND'
  const isClaimant = Boolean(user && user.id === claim.claimantId)
  const isReviewer = Boolean(
    user && (user.role === 'ADMIN' || user.id === claim.reporterId),
  )
  const canUse = Boolean(
    isFound && (isClaimant || isReviewer)
    && (claim.status === 'APPROVED' || claim.status === 'COMPLETED'),
  )

  // The issued code is tagged with the claim and the moment it arrived, so a
  // delayed response or a stale status can never display — or erase — a code
  // that belongs somewhere else.
  const [issued, setIssued] = useState<{
    claimId: number
    code: string
    expiresAt: string
    issuedAt: number
  } | null>(null)
  const code = issued && issued.claimId === claim.id ? issued.code : null
  const codeExpiresAt = issued && issued.claimId === claim.id ? issued.expiresAt : null
  const [isIssuing, setIsIssuing] = useState(false)
  const [cooldownUntil, setCooldownUntil] = useState(0)
  const [verifyCode, setVerifyCode] = useState('')
  const [verifyError, setVerifyError] = useState<string | null>(null)
  const [isVerifying, setIsVerifying] = useState(false)
  const [releaseReason, setReleaseReason] = useState('')
  const [releaseError, setReleaseError] = useState<string | null>(null)
  const [releaseOpen, setReleaseOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [confirmedAt, setConfirmedAt] = useState<string | null>(null)

  const {
    data: status,
    isLoading,
    isError: statusFailed,
    error: statusError,
    refetch: refetchStatus,
    dataUpdatedAt: statusUpdatedAt,
    isRefetching: statusRefetching,
  } = useQuery({
    queryKey: queryKeys.account(user?.id ?? 0, 'claims', claim.id, 'handover'),
    queryFn: ({ signal }) => api<HandoverStatus>(`/claims/${claim.id}/handover`, { signal }),
    enabled: hydrated && canUse,
    staleTime: 0,
    // The claimant can issue a code at any time; the finder must see the
    // verify form appear without reloading the page.
    refetchInterval: (query) =>
      query.state.data?.state === 'COMPLETED' ? false : 10_000,
  })

  useEffect(() => {
    setIssued(null)
    setVerifyCode('')
    setVerifyError(null)
    setConfirmedAt(null)
    setReleaseError(null)
  }, [claim.id, user?.id])

  useEffect(() => {
    if (!issued) return
    if (issued.expiresAt && Date.parse(issued.expiresAt) <= now) {
      setIssued(null)
      return
    }
    // A terminal status only clears a visible code when the status response
    // was fetched after that code was issued — a cached EXPIRED or LOCKED
    // snapshot must not erase it.
    if (statusUpdatedAt > issued.issuedAt
        && (status?.state === 'LOCKED'
          || status?.state === 'EXPIRED'
          || status?.state === 'COMPLETED')) {
      setIssued(null)
    }
  }, [issued, status?.state, statusUpdatedAt, now])

  async function issueCode() {
    setIsIssuing(true)
    try {
      const response = await api<HandoverCode>(`/claims/${claim.id}/handover/code`, {
        method: 'POST',
      })
      setIssued({
        claimId: claim.id,
        code: response.code,
        expiresAt: response.expiresAt,
        issuedAt: Date.now(),
      })
      setCooldownUntil(Date.now() + 60_000)
      setError(null)
      onChanged()
    } catch (err) {
      const message = messageOf(err)
      const retry = message.match(/(\d+)\s+seconds?/i)
      if (retry) {
        setCooldownUntil(Date.now() + Number(retry[1]) * 1000)
      }
      setError(message)
    } finally {
      setIsIssuing(false)
    }
  }

  async function verifyCodeNow() {
    if (verifyCode.length !== 6 || isVerifying) return
    setIsVerifying(true)
    try {
      const result = await api<HandoverVerification>(`/claims/${claim.id}/handover/verify`, {
        method: 'POST',
        body: JSON.stringify({ code: verifyCode }),
      })
      setConfirmedAt(result.verifiedAt)
      setVerifyCode('')
      setIssued(null)
      setVerifyError(null)
      setError(null)
      onChanged()
    } catch (err) {
      setVerifyError(messageOf(err))
      onChanged()
    } finally {
      setIsVerifying(false)
    }
  }

  const release = useMutation({
    mutationFn: (reason: string) =>
      api(`/claims/${claim.id}/handover/release`, {
        method: 'POST',
        body: JSON.stringify({ reason }),
      }),
    onSuccess: () => {
      setIssued(null)
      setVerifyCode('')
      setReleaseOpen(false)
      setReleaseReason('')
      setReleaseError(null)
      setError(null)
      onChanged()
    },
    onError: (err) => setReleaseError(messageOf(err)),
  })

  function submitRelease() {
    const reason = releaseReason.trim()
    if (!reason) {
      setReleaseError('A reason is required — the claimant sees it in their history.')
      return
    }
    release.mutate(reason)
  }

  if (!isFound || !canUse || !user) return null

  const completed = claim.status === 'COMPLETED' || status?.state === 'COMPLETED'
  const verifiedAt = status?.verifiedAt ?? confirmedAt
  const expiresAt = codeExpiresAt ?? status?.expiresAt ?? null
  const expiresAtMs = expiresAt ? Date.parse(expiresAt) : null
  const codeMsLeft = now > 0 && expiresAtMs ? Math.max(0, expiresAtMs - now) : null
  const codeExpired = codeMsLeft !== null && codeMsLeft <= 0
  const cooldownLeft = now > 0 && cooldownUntil > now
    ? Math.ceil((cooldownUntil - now) / 1000)
    : 0

  if (isLoading) {
    return (
      <StatusShell title="Pickup handover" tone="default">
        <p className="m-0 text-sm text-muted-foreground">Checking pickup status…</p>
      </StatusShell>
    )
  }

  if (statusFailed) {
    return (
      <StatusShell title="Pickup handover" tone="default">
        <Alert variant="destructive">
          <AlertDescription>{messageOf(statusError)}</AlertDescription>
        </Alert>
      </StatusShell>
    )
  }

  if (completed) {
    return (
      <StatusShell
        title={verifiedAt ? 'Pickup verified' : 'Pickup resolved'}
        icon={verifiedAt
          ? <ShieldCheck aria-hidden="true" size={18} weight="bold" />
          : <CheckCircle aria-hidden="true" size={18} weight="bold" />}
        tone={verifiedAt ? 'success' : 'default'}
      >
        <div className="grid gap-2">
          <p className="m-0 inline-flex items-center gap-1.5 text-sm font-semibold text-success-foreground">
            <CheckCircle aria-hidden="true" size={18} weight="fill" />
            {verifiedAt ? 'Handover confirmed in person' : 'Resolved without pickup verification'}
          </p>
          <p className="m-0 text-sm text-muted-foreground">
            {verifiedAt
              ? `The pickup code was verified at ${formatFullTime(verifiedAt)}. ${
                  claim.lostReportId
                    ? 'The found report and linked lost report are both resolved.'
                    : 'The found report is now resolved.'
                }`
              : 'This claim was resolved before pickup codes were introduced. No verified pickup was recorded for it.'}
          </p>
        </div>
      </StatusShell>
    )
  }

  return (
    <StatusShell
      title={isClaimant ? 'Your pickup code' : 'Verify pickup'}
      icon={
        isClaimant
          ? <Key aria-hidden="true" size={18} weight="bold" />
          : <ShieldCheck aria-hidden="true" size={18} weight="bold" />
      }
      tone="default"
    >
      <div className="grid gap-3.5">
        {isClaimant && (
          <div className="grid gap-3">
            {code && !codeExpired && status?.state !== 'LOCKED'
              && status?.state !== 'EXPIRED' && status?.state !== 'COMPLETED' ? (
              <div className="grid gap-2">
                <p className="m-0 text-xs font-bold uppercase tracking-[0.12em] text-muted-foreground">
                  Show this code to the finder
                </p>
                <div className="flex flex-wrap gap-1.5" aria-hidden="true">
                  {code.split('').map((digit, index) => (
                    <span
                      key={`${digit}-${index}`}
                      className="flex size-11 items-center justify-center rounded-lg border border-border bg-card text-2xl font-bold tabular-nums shadow-sm"
                    >
                      {digit}
                    </span>
                  ))}
                </div>
                <span className="sr-only">Pickup code: {code.split('').join(' ')}</span>
                <p className="m-0 inline-flex items-center gap-1.5 text-xs text-muted-foreground">
                  <Clock aria-hidden="true" size={14} />
                  Expires at {formatClockTime(expiresAt!)} ·{' '}
                  <span className="font-bold tabular-nums text-foreground">
                    {formatCountdown(codeMsLeft ?? 0)} left
                  </span>
                </p>
              </div>
            ) : (
              <p className="m-0 text-sm text-muted-foreground">
                {claimantHint(status?.state)}
              </p>
            )}

            {codeExpired && (
              <p className="m-0 inline-flex items-center gap-1.5 text-xs font-semibold text-warning-foreground">
                <WarningCircle aria-hidden="true" size={16} weight="fill" />
                That code has expired. Generate a fresh one below.
              </p>
            )}

            <p className="m-0 rounded-xl bg-card p-3 text-xs leading-relaxed text-muted-foreground ring-1 ring-hairline">
              Keep this code on your own device. The finder enters it on theirs, so the
              handover is proven without sharing it in a chat or photo.
            </p>

            <div className="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                onClick={() => void issueCode()}
                disabled={isIssuing || cooldownLeft > 0}
              >
                {isIssuing
                  ? 'Generating…'
                  : code
                    ? <><ArrowClockwise aria-hidden="true" size={16} />Generate new code</>
                    : 'Generate pickup code'}
              </Button>
              {cooldownLeft > 0 && (
                <span className="text-xs text-muted-foreground tabular-nums">
                  New code in {cooldownLeft}s
                </span>
              )}
            </div>

            {status?.state === 'READY' && !code && (
              <p className="m-0 text-xs text-muted-foreground">
                A code is already active on this claim. Generate a new one to display it
                again; the previous code stops working immediately.
              </p>
            )}
            {status?.state === 'LOCKED' && (
              <p className="m-0 inline-flex items-start gap-1.5 text-xs text-warning-foreground">
                <LockKey aria-hidden="true" size={15} weight="fill" className="mt-0.5" />
                Too many incorrect entries were tried. Generate a fresh code so the finder
                can try again.
              </p>
            )}
          </div>
        )}

        {isClaimant && isReviewer && <div className="h-px bg-border" />}

        {isReviewer && (
          <div className="grid gap-3">
            <div className="grid gap-1.5">
              <div className="flex items-center justify-between gap-2">
                <p className="m-0 text-sm font-bold">{reviewerStateTitle(status?.state)}</p>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-xs"
                  aria-label="Refresh pickup status"
                  onClick={() => void refetchStatus()}
                  disabled={statusRefetching}
                >
                  <ArrowClockwise aria-hidden="true" size={14} />
                </Button>
              </div>
              <p className="m-0 text-xs text-muted-foreground">
                {reviewerStateHint(status?.state, claim.claimantName)}
              </p>
              {(status?.state === 'READY' || status?.state === 'LOCKED')
                && status.attemptsRemaining !== null && (
                <p className="m-0 text-xs font-bold tabular-nums text-foreground">
                  {status.attemptsRemaining} of 5 attempts remaining
                </p>
              )}
              {status?.state === 'READY' && status.expiresAt && (
                <p className="m-0 inline-flex items-center gap-1.5 text-xs text-muted-foreground">
                  <Clock aria-hidden="true" size={14} />
                  Code expires at {formatClockTime(status.expiresAt)}
                  {codeMsLeft !== null && !codeExpired && (
                    <> · {formatCountdown(codeMsLeft)} left</>
                  )}
                </p>
              )}
            </div>

            <form
              className="grid gap-2"
              onSubmit={(event) => {
                event.preventDefault()
                void verifyCodeNow()
              }}
            >
              <Label htmlFor={`handover-code-${claim.id}`}>Six-digit pickup code</Label>
              <Input
                id={`handover-code-${claim.id}`}
                value={verifyCode}
                onChange={(event) => {
                  setVerifyCode(event.target.value.replace(/\D/g, '').slice(0, 6))
                  setVerifyError(null)
                }}
                inputMode="numeric"
                pattern="[0-9]*"
                maxLength={6}
                autoComplete="one-time-code"
                placeholder="000000"
                aria-invalid={verifyError ? 'true' : undefined}
                aria-describedby={verifyError ? `handover-code-error-${claim.id}` : undefined}
                className="h-14 rounded-2xl text-center text-2xl font-semibold tracking-[0.35em] tabular-nums"
              />
              {verifyError && (
                <p
                  id={`handover-code-error-${claim.id}`}
                  role="alert"
                  className="m-0 text-xs font-semibold text-destructive-foreground"
                >
                  {verifyError}
                </p>
              )}
              <Button
                type="submit"
                disabled={
                  isVerifying
                  || verifyCode.length !== 6
                  || status?.state !== 'READY'
                }
              >
                {isVerifying ? 'Verifying…' : 'Verify pickup'}
              </Button>
              <p className="m-0 text-xs text-muted-foreground">
                Ask the claimant to show the code on their device. Enter all six digits,
                including any leading zeros.
              </p>
            </form>

            <div className="h-px bg-border" />

            <AlertDialog open={releaseOpen} onOpenChange={setReleaseOpen}>
              <AlertDialogTrigger
                render={
                  <Button variant="outline" disabled={release.isPending}>
                    <LockKey aria-hidden="true" size={16} />
                    Release reservation
                  </Button>
                }
              />
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Release this pickup?</AlertDialogTitle>
                  <AlertDialogDescription>
                    The claim becomes cancelled, the found report and any linked lost
                    report reopen, and the current pickup code stops working. Use this if
                    the owner did not arrive or you cannot verify the handover.
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <div className="grid gap-2">
                  <Label htmlFor={`handover-release-${claim.id}`}>
                    Reason
                  </Label>
                  <Textarea
                    id={`handover-release-${claim.id}`}
                    value={releaseReason}
                    maxLength={500}
                    onChange={(event) => {
                      setReleaseReason(event.target.value)
                      setReleaseError(null)
                    }}
                    placeholder="Owner did not arrive, could not verify the code…"
                    aria-invalid={releaseError ? 'true' : undefined}
                  />
                  {releaseError && (
                    <p role="alert" className="m-0 text-xs font-semibold text-destructive-foreground">
                      {releaseError}
                    </p>
                  )}
                </div>
                <AlertDialogFooter>
                  <AlertDialogCancel>Keep reservation</AlertDialogCancel>
                  <AlertDialogAction
                    variant="destructive"
                    disabled={release.isPending}
                    onClick={(event) => {
                      // The dialog stays open until the server accepts the
                      // release, so a validation error can be corrected.
                      event.preventDefault()
                      submitRelease()
                    }}
                  >
                    {release.isPending ? 'Releasing…' : 'Release reservation'}
                  </AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          </div>
        )}

        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
      </div>
    </StatusShell>
  )
}

function StatusShell({
  title,
  icon,
  tone,
  children,
}: {
  title: string
  icon?: ReactNode
  tone: 'default' | 'success'
  children: ReactNode
}) {
  return (
    <section
      aria-label="Pickup handover"
      className={
        tone === 'success'
          ? 'grid gap-3 rounded-2xl bg-success/10 p-4 outline-1 -outline-offset-1 outline-success/25'
          : 'grid gap-3 rounded-2xl bg-fill p-4 outline-1 -outline-offset-1 outline-hairline'
      }
    >
      <div className="flex items-center gap-2.5">
        <span
          className={
            tone === 'success'
              ? 'inline-flex size-8 items-center justify-center rounded-full bg-success/15 text-success-foreground'
              : 'inline-flex size-8 items-center justify-center rounded-full bg-primary/10 text-primary'
          }
        >
          {icon ?? <Key aria-hidden="true" size={18} weight="bold" />}
        </span>
        <h3 className="m-0 text-sm font-bold">{title}</h3>
      </div>
      {children}
    </section>
  )
}

function claimantHint(state: HandoverState | undefined): string {
  switch (state) {
    case 'READY':
      return 'A pickup code is active for this claim. Generate a new one to display it again.'
    case 'EXPIRED':
      return 'The last pickup code expired. Generate a fresh one when you meet the finder.'
    case 'LOCKED':
      return 'Too many incorrect entries were tried. Generate a fresh code to continue.'
    case 'NOT_ISSUED':
    default:
      return 'Generate a six-digit code when you meet the finder. It expires after ten minutes.'
  }
}

function reviewerStateTitle(state: HandoverState | undefined): string {
  switch (state) {
    case 'READY':
      return 'A code is active'
    case 'EXPIRED':
      return 'The code expired'
    case 'LOCKED':
      return 'Verification is locked'
    case 'COMPLETED':
      return 'Pickup verified'
    case 'NOT_ISSUED':
    default:
      return 'Waiting for a code'
  }
}

function reviewerStateHint(state: HandoverState | undefined, claimantName: string): string {
  switch (state) {
    case 'READY':
      return `${claimantName} can show the code now. Enter it before it expires.`
    case 'EXPIRED':
      return `The code expired. Ask ${claimantName} to generate a new one.`
    case 'LOCKED':
      return `Too many incorrect attempts. ${claimantName} must generate a new code.`
    case 'COMPLETED':
      return 'This pickup has already been verified.'
    case 'NOT_ISSUED':
    default:
      return `${claimantName} has not generated a pickup code yet.`
  }
}

function messageOf(error: unknown): string {
  return error instanceof Error && error.message
    ? error.message
    : 'Something went wrong. Please try again.'
}

function formatClockTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, {
    hour: 'numeric',
    minute: '2-digit',
  })
}

function formatFullTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  })
}

function formatCountdown(milliseconds: number): string {
  const total = Math.max(0, Math.ceil(milliseconds / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

/** Mount-safe clock: starts at 0 so SSR and hydration render the same text. */
function useNow(): number {
  const [now, setNow] = useState(0)
  useEffect(() => {
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [])
  return now
}
