import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  ArrowLeft,
  CalendarBlank,
  CheckCircle,
  Clock,
  MapPin,
  PencilSimple,
  UserCircle,
} from '@phosphor-icons/react'
import { Alert, AlertDescription, AlertTitle } from '#/components/ui/alert.tsx'
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
import { Badge } from '#/components/ui/badge.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Card, CardContent } from '#/components/ui/card.tsx'
import { Label } from '#/components/ui/label.tsx'
import { Textarea } from '#/components/ui/textarea.tsx'
import { ContactPanel } from '../components/ContactPanel'
import { ClaimBadge, StatusBadge } from '../components/StatusBadge'
import { HandoverPanel } from '../features/handover'
import { MatchPanel } from '../features/matching'
import { ClaimEvidence, OwnershipAnswerField } from '../features/ownership'
import { ApiError, api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { formatDate, isActiveClaim, timeAgo } from '../lib/format'
import { invalidateWorkflow, queryKeys } from '../lib/query'
import type { Claim, ClaimInput, Item } from '../lib/types'

interface ItemSearch {
  lostReportId?: number
  claimId?: number
}

export const Route = createFileRoute('/items/$id')({
  validateSearch: (search: Record<string, unknown>): ItemSearch => {
    const parsed: ItemSearch = {}
    const lost = typeof search.lostReportId === 'number' ? search.lostReportId : Number(search.lostReportId)
    if (Number.isInteger(lost) && lost > 0) parsed.lostReportId = lost
    const claim = typeof search.claimId === 'number' ? search.claimId : Number(search.claimId)
    if (Number.isInteger(claim) && claim > 0) parsed.claimId = claim
    return parsed
  },
  component: ItemDetailPage,
})

function ItemDetailPage() {
  const { id } = Route.useParams()
  const { lostReportId, claimId } = Route.useSearch()
  const { user, hydrated } = useAuth()

  const { data: item, isLoading, isError, error } = useQuery({
    queryKey: queryKeys.publicItem(id),
    queryFn: ({ signal }) => api<Item>(`/items/${id}`, { signal }),
  })

  const {
    data: myClaims,
    isLoading: isLoadingMyClaims,
    isError: isErrorMyClaims,
    refetch: refetchMyClaims,
  } = useQuery({
    queryKey: queryKeys.accountClaims(user?.id ?? 0),
    queryFn: ({ signal }) => api<Claim[]>('/me/claims', { signal }),
    enabled: hydrated && !!user,
  })

  const activeClaim = item && myClaims?.find(
    (claim) => claim.itemId === item.id && isActiveClaim(claim.status),
  )
  // A claim notification links straight to that claim's history, including
  // terminal states the active-claim lookup cannot see.
  const selectedClaim = item && claimId
    ? myClaims?.find((claim) => claim.id === claimId && claim.itemId === item.id)
    : undefined
  const canManage = Boolean(
    item && user && (user.role === 'ADMIN' || user.id === item.reporterId),
  )

  if (isLoading) {
    return (
      <main className="demo-page">
        <Card className="rounded-xl shadow-none ring-border">
          <CardContent className="text-center text-sm text-muted-foreground">Loading…</CardContent>
        </Card>
      </main>
    )
  }
  if (isError || !item) {
    return (
      <main className="demo-page">
        <Alert variant="destructive">
          <AlertDescription>
            Couldn't load this report: {(error as Error).message}
          </AlertDescription>
        </Alert>
        <Button render={<Link to="/" />} variant="outline" className="mt-4">
          Back to browse
        </Button>
      </main>
    )
  }

  // The claim form only opens once the claimant's history is known — a
  // failed lookup must never offer a form that could duplicate a claim.
  const canClaim = Boolean(
    hydrated && user && !canManage && myClaims && !activeClaim && item.status === 'OPEN',
  )
  const occurredLabel = item.type === 'LOST' ? 'Lost on' : 'Found on'
  const authReturnTo = lostReportId && item.type === 'FOUND'
    ? `/items/${item.id}?lostReportId=${lostReportId}`
    : `/items/${item.id}`

  return (
    <main className="demo-page item-detail-page">
      <Link to="/" className="detail-back-link">
        <ArrowLeft aria-hidden="true" size={18} weight="bold" />
        Back to browse
      </Link>

      <div className="item-detail-layout rise-in">
        <Card className="item-detail-card gap-0 rounded-xl shadow-none ring-border">
          <CardContent className="p-0">
            {item.photoUrl && (
              <img className="detail-photo" src={item.photoUrl} alt={`Photo of ${item.title}`} />
            )}

            <header>
              <div className="detail-badges">
                <Badge>{item.type === 'LOST' ? 'Lost item' : 'Found item'}</Badge>
                <StatusBadge status={item.status} />
                <Badge variant="outline">{item.category}</Badge>
              </div>

              <h1 className="detail-title">{item.title}</h1>
              <p className="detail-description">{item.description}</p>
            </header>

            <dl className="detail-meta-grid">
              <div className="detail-meta-item">
                <MapPin aria-hidden="true" size={20} />
                <div>
                  <dt>Location</dt>
                  <dd>{item.location}</dd>
                </div>
              </div>
              <div className="detail-meta-item">
                <CalendarBlank aria-hidden="true" size={20} />
                <div>
                  <dt>{occurredLabel}</dt>
                  <dd><time dateTime={item.dateOccurred}>{formatDate(item.dateOccurred)}</time></dd>
                </div>
              </div>
              <div className="detail-meta-item">
                <Clock aria-hidden="true" size={20} />
                <div>
                  <dt>Posted</dt>
                  <dd><time dateTime={item.createdAt}>{timeAgo(item.createdAt)}</time></dd>
                </div>
              </div>
            </dl>

            <section className="reporter-card" aria-labelledby="reporter-heading">
              <UserCircle aria-hidden="true" className="reporter-icon" size={24} />
              <div className="min-w-0">
                <h2 id="reporter-heading">Posted by {item.reporterName}</h2>
                <p className="m-0 mt-1 text-xs text-muted-foreground">
                  Email and phone are shared only with the people in an approved claim.
                </p>
              </div>
            </section>
          </CardContent>
        </Card>

        <aside className="claim-sidebar" aria-label="Report actions">
          {canManage && user ? (
            <>
              <ManagePanel item={item} />
              <ClaimsPanel item={item} userId={user.id} />
              <MatchPanel item={item} />
            </>
          ) : hydrated && user && isLoadingMyClaims ? (
            <Card className="rounded-xl shadow-none ring-border">
              <CardContent className="text-sm text-muted-foreground">
                Checking your response status…
              </CardContent>
            </Card>
          ) : hydrated && user && isErrorMyClaims ? (
            <Card className="rounded-xl shadow-none ring-border">
              <CardContent className="flex items-center gap-3 text-sm">
                <span className="text-destructive">Couldn't load your response history.</span>
                <Button size="sm" variant="outline" onClick={() => void refetchMyClaims()}>
                  Retry
                </Button>
              </CardContent>
            </Card>
          ) : selectedClaim && user ? (
            <ExistingClaimPanel
              key={`${user.id}:${item.id}:${selectedClaim.id}`}
              claim={selectedClaim}
              item={item}
              userId={user.id}
            />
          ) : activeClaim && user ? (
            <ExistingClaimPanel
              key={`${user.id}:${item.id}:${activeClaim.id}`}
              claim={activeClaim}
              item={item}
              userId={user.id}
            />
          ) : canClaim && user ? (
            <ClaimPanel
              key={`${user.id}:${item.id}`}
              item={item}
              presetLostReportId={lostReportId}
            />
          ) : (
            <Card className="claim-closed-panel rounded-xl shadow-none ring-border">
              <CardContent>
                {item.status === 'OPEN' && hydrated && !user ? (
                  <>
                    <h2>{item.type === 'LOST' ? 'Have you found this?' : 'Think this is yours?'}</h2>
                    <p>
                      {item.type === 'LOST'
                        ? `Log in to tell ${item.reporterName} where you saw or found it.`
                        : `Log in to send ${item.reporterName} a claim.`}
                    </p>
                    <div className="flex flex-wrap gap-3">
                      <Button
                        render={<Link to="/login" search={{ redirect: authReturnTo }} />}
                      >
                        {item.type === 'LOST' ? 'Log in to respond' : 'Log in to claim'}
                      </Button>
                      <Button
                        render={<Link to="/register" search={{ redirect: authReturnTo }} />}
                        variant="outline"
                      >
                        Create account
                      </Button>
                    </div>
                  </>
                ) : (
                  <>
                    <h2>Responses are closed</h2>
                    <p>
                      {item.status === 'RESERVED'
                        ? 'This item has an approved pickup in progress.'
                        : 'This report is no longer accepting responses.'}
                    </p>
                  </>
                )}
              </CardContent>
            </Card>
          )}
        </aside>
      </div>
    </main>
  )
}

function ManagePanel({ item }: { item: Item }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  const changeStatus = useMutation({
    mutationFn: (status: 'OPEN' | 'RESOLVED' | 'CLOSED') =>
      api<Item>(`/items/${item.id}/status`, {
        method: 'PATCH',
        body: JSON.stringify({ status }),
      }),
    onSuccess: async () => {
      setError(null)
      await invalidateWorkflow(queryClient)
    },
    onError: (err) => setError((err as Error).message),
  })

  const remove = useMutation({
    mutationFn: () => api<void>(`/items/${item.id}`, { method: 'DELETE' }),
    onSuccess: async () => {
      await invalidateWorkflow(queryClient)
      await navigate({ to: '/' })
    },
    onError: (err) => setError((err as Error).message),
  })

  const editable = item.status === 'OPEN' || item.status === 'RESOLVED'

  return (
    <Card className="rounded-xl shadow-none ring-border">
      <CardContent>
        <p className="island-kicker mb-3">You manage this report</p>
        <div className="grid gap-2.5">
          {editable && (
            <Button
              render={<Link to="/items/$id/edit" params={{ id: String(item.id) }} />}
              variant="outline"
            >
              <PencilSimple aria-hidden="true" size={16} />
              Edit report
            </Button>
          )}

          {item.status === 'OPEN' && (
            <Button
              disabled={changeStatus.isPending}
              onClick={() => changeStatus.mutate('RESOLVED')}
            >
              Mark as resolved
            </Button>
          )}
          {item.status === 'OPEN' && (
            <Button
              variant="outline"
              disabled={changeStatus.isPending}
              onClick={() => changeStatus.mutate('CLOSED')}
            >
              Close report
            </Button>
          )}
          {item.status === 'RESOLVED' && (
            <Button
              variant="outline"
              disabled={changeStatus.isPending}
              onClick={() => changeStatus.mutate('OPEN')}
            >
              Reopen report
            </Button>
          )}
          {item.status === 'RESERVED' && (
            <p className="m-0 text-xs text-muted-foreground">
              Release the approved claim below before editing or changing this report.
            </p>
          )}
          {item.status === 'CLOSED' && (
            <p className="m-0 text-xs text-muted-foreground">
              Closed reports are final and cannot be edited.
            </p>
          )}

          {item.status !== 'RESERVED' && item.status !== 'CLOSED' && (
            <AlertDialog>
              <AlertDialogTrigger
                render={
                  <Button variant="destructive" disabled={remove.isPending}>
                    {remove.isPending ? 'Deleting…' : 'Delete report'}
                  </Button>
                }
              />
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Delete this report?</AlertDialogTitle>
                  <AlertDialogDescription>
                    It disappears from browsing and matching. Claim history is preserved,
                    but this cannot be undone from the app.
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel>Cancel</AlertDialogCancel>
                  <AlertDialogAction variant="destructive" onClick={() => remove.mutate()}>
                    Delete report
                  </AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          )}

          {error && (
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

function ClaimPanel({ item, presetLostReportId }: { item: Item; presetLostReportId?: number }) {
  const queryClient = useQueryClient()
  const [message, setMessage] = useState('')
  const [ownershipAnswer, setOwnershipAnswer] = useState('')
  const [answerValidity, setAnswerValidity] = useState<{ valid: boolean; message?: string }>({ valid: true })
  const [error, setError] = useState<string | null>(null)
  const [answerError, setAnswerError] = useState<string | null>(null)
  const [validationError, setValidationError] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const isLostReport = item.type === 'LOST'
  const lostReportId = item.type === 'FOUND' ? presetLostReportId : undefined

  const { data: linkedReport } = useQuery({
    queryKey: queryKeys.publicItem(lostReportId ?? 0),
    queryFn: ({ signal }) => api<Item>(`/items/${lostReportId}`, { signal }),
    enabled: !!lostReportId,
  })

  const mutation = useMutation({
    mutationFn: (input: ClaimInput) =>
      api<Claim>(`/items/${item.id}/claims`, {
        method: 'POST',
        body: JSON.stringify(input),
      }),
    onSuccess: async () => {
      setDone(true)
      await invalidateWorkflow(queryClient)
    },
    onError: (err) => {
      const reason = err instanceof ApiError ? err.message : 'Please try again.'
      setError(`Unable to send: ${reason}`)
      if (err instanceof ApiError && err.fieldErrors?.ownershipAnswer) {
        setAnswerError(err.fieldErrors.ownershipAnswer)
      }
    },
  })

  function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setAnswerError(null)
    const trimmedMessage = message.trim()
    if (trimmedMessage.length < 10) {
      setValidationError('Write at least 10 characters.')
      return
    }
    if (!answerValidity.valid) {
      setValidationError(answerValidity.message ?? 'Answer the ownership question first.')
      return
    }
    setValidationError(null)
    mutation.mutate({
      message: trimmedMessage,
      lostReportId,
      ownershipAnswer: ownershipAnswer.trim() || undefined,
    })
  }

  if (done) {
    return (
      <Alert className="claim-success" role="status">
        <CheckCircle aria-hidden="true" size={28} weight="fill" />
        <div>
          <AlertTitle>{isLostReport ? 'Message sent' : 'Claim sent'}</AlertTitle>
          <AlertDescription>
            {item.reporterName} will review it. Contact details unlock after approval.
          </AlertDescription>
        </div>
      </Alert>
    )
  }

  return (
    <form onSubmit={onSubmit} noValidate>
      <Card className="claim-panel rounded-xl shadow-none ring-border">
        <CardContent>
          <h2>{isLostReport ? 'Found this item?' : 'Claim this item'}</h2>
          <p className="claim-intro">
            {isLostReport
              ? `Tell ${item.reporterName} where it is.`
              : `Share a detail only the owner would know. ${item.reporterName} reviews it.`}
          </p>

          {lostReportId && (
            <p className="m-0 mb-3 rounded-lg bg-muted p-2 text-xs text-muted-foreground">
              Linking to your lost report
              {linkedReport ? ` “${linkedReport.title}”` : ` #${lostReportId}`}.
            </p>
          )}

          <Label className="claim-label" htmlFor="claim-message">Message</Label>
          <Textarea
            id="claim-message"
            name="claim-message"
            className="claim-textarea rounded-lg border-border bg-input/50"
            value={message}
            onChange={(event) => {
              const next = event.target.value
              setMessage(next)
              if (next.trim().length >= 10) setValidationError(null)
            }}
            maxLength={2000}
            aria-invalid={validationError ? 'true' : undefined}
            aria-describedby={validationError ? 'claim-message-error' : undefined}
            placeholder={
              isLostReport
                ? 'East entrance, second bench, around 4 pm…'
                : 'One glove has blue stitching inside the cuff…'
            }
          />
          <div className="claim-field-meta">
            <span className="tabular-nums">{message.length}/2000</span>
          </div>

          {!isLostReport && (
            <div className="mt-3">
              <OwnershipAnswerField
                itemId={item.id}
                value={ownershipAnswer}
                onChange={setOwnershipAnswer}
                error={answerError}
                onValidityChange={setAnswerValidity}
              />
            </div>
          )}

          {validationError && (
            <p className="claim-error" id="claim-message-error">{validationError}</p>
          )}
          {error && (
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}

          <Button
            className="claim-submit"
            type="submit"
            disabled={mutation.isPending || !answerValidity.valid}
          >
            {mutation.isPending
              ? 'Sending…'
              : isLostReport
                ? 'Send message'
                : 'Send claim'}
          </Button>
        </CardContent>
      </Card>
    </form>
  )
}

function ExistingClaimPanel({ claim, item, userId }: { claim: Claim; item: Item; userId: number }) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const noun = item.type === 'LOST' ? 'Response' : 'Claim'
  const heading =
    claim.status === 'PENDING'
      ? `${noun} sent`
      : claim.status === 'APPROVED'
        ? `${noun} approved`
        : claim.status === 'COMPLETED'
          ? `${noun} completed`
          : `${noun} cancelled`

  const detail =
    claim.status === 'PENDING'
      ? `${item.reporterName} hasn’t reviewed it yet.`
      : claim.status === 'APPROVED'
        ? item.type === 'FOUND'
          ? `Approved. Use the contact details below to arrange pickup.`
          : `Approved. ${item.reporterName} marked your response resolved.`
        : claim.status === 'COMPLETED'
          ? item.type === 'FOUND'
            ? 'This pickup is complete.'
            : `Resolved by ${item.reporterName}.`
          : 'This response is no longer active. The report may still be open.'

  const cancel = useMutation({
    mutationFn: () => api<void>(`/claims/${claim.id}`, { method: 'DELETE' }),
    onSuccess: async () => { await invalidateWorkflow(queryClient) },
    onError: (err) => setError(`Unable to cancel: ${(err as Error).message}`),
  })

  return (
    <Card className="existing-claim-panel rounded-xl shadow-none ring-border">
      <CardContent>
        <div className="existing-claim-heading">
          <h2>{heading}</h2>
          <ClaimBadge status={claim.status} />
        </div>
        <p>{detail}</p>
        <blockquote>{claim.message}</blockquote>
        {claim.lostReportId && (
          <Link
            to="/items/$id"
            params={{ id: String(claim.lostReportId) }}
            className="text-xs font-bold no-underline"
          >
            Linked lost report →
          </Link>
        )}

        {claim.status === 'PENDING' && (
          <AlertDialog>
            <AlertDialogTrigger
              render={
                <Button className="mt-3" variant="outline" disabled={cancel.isPending}>
                  {cancel.isPending ? 'Cancelling…' : `Cancel ${noun.toLowerCase()}`}
                </Button>
              }
            />
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Cancel this {noun.toLowerCase()}?</AlertDialogTitle>
                <AlertDialogDescription>
                  The message stays in history as cancelled. You can send a new one later
                  while the report is open.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Keep it</AlertDialogCancel>
                <AlertDialogAction variant="destructive" onClick={() => cancel.mutate()}>
                  Cancel {noun.toLowerCase()}
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        )}

        <div className="mt-3 grid gap-3">
          <ContactPanel claim={claim} userId={userId} />
          {item.type === 'FOUND' && (claim.status === 'APPROVED' || claim.status === 'COMPLETED') && (
            <HandoverPanel
              claim={claim}
              onChanged={() => { void invalidateWorkflow(queryClient) }}
            />
          )}
          <ClaimEvidence claimId={claim.id} />
        </div>

        {error && (
          <Alert className="mt-3" variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
      </CardContent>
    </Card>
  )
}

function ClaimsPanel({ item, userId }: { item: Item; userId: number }) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const { data: claims, isLoading, isError, refetch } = useQuery({
    queryKey: queryKeys.accountItemClaims(userId, item.id),
    queryFn: ({ signal }) => api<Claim[]>(`/items/${item.id}/claims`, { signal }),
  })

  const decide = useMutation({
    mutationFn: ({ claimId, status }: { claimId: number; status: 'APPROVED' | 'REJECTED' }) =>
      api<Claim>(`/claims/${claimId}`, {
        method: 'PATCH',
        body: JSON.stringify({ status }),
      }),
    onSuccess: async () => {
      setError(null)
      await invalidateWorkflow(queryClient)
    },
    onError: (err) => setError((err as Error).message),
  })

  return (
    <Card className="rounded-xl shadow-none ring-border">
      <CardContent>
        <p className="island-kicker mb-3">Responses ({claims?.length ?? 0})</p>
        {isLoading && <p className="m-0 text-sm text-muted-foreground">Loading responses…</p>}
        {isError && (
          <div className="flex items-center gap-2">
            <p className="m-0 text-sm text-destructive">Couldn't load responses.</p>
            <Button size="sm" variant="outline" onClick={() => void refetch()}>Retry</Button>
          </div>
        )}
        {claims && claims.length === 0 && (
          <p className="m-0 text-sm text-muted-foreground">
            No responses yet.
          </p>
        )}
        {claims && claims.length > 0 && (
          <ul className="m-0 grid gap-3 p-0">
            {claims.map((claim) => (
              <li key={claim.id} className="list-none">
                <ClaimCard
                  claim={claim}
                  item={item}
                  userId={userId}
                  busy={decide.isPending}
                  onDecide={(status) => decide.mutate({ claimId: claim.id, status })}
                />
              </li>
            ))}
          </ul>
        )}
        {error && (
          <Alert className="mt-3" variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
      </CardContent>
    </Card>
  )
}

function ClaimCard({
  claim,
  item,
  userId,
  busy,
  onDecide,
}: {
  claim: Claim
  item: Item
  userId: number
  busy: boolean
  onDecide: (status: 'APPROVED' | 'REJECTED') => void
}) {
  const queryClient = useQueryClient()
  return (
    <Card size="sm" className="rounded-xl bg-muted shadow-none ring-border">
      <CardContent>
        <div className="mb-1.5 flex flex-wrap items-center gap-2">
          <span className="text-sm font-bold text-foreground">{claim.claimantName}</span>
          <ClaimBadge status={claim.status} />
          <span className="ml-auto text-xs text-muted-foreground">
            {timeAgo(claim.createdAt)}
          </span>
        </div>
        {claim.lostReportId && (
          <p className="m-0 mb-2 text-xs text-muted-foreground">
            Links lost report #{claim.lostReportId}
          </p>
        )}
        <p className="m-0 mb-3 whitespace-pre-wrap text-sm text-foreground">{claim.message}</p>

        {claim.status === 'PENDING' && item.status === 'OPEN' && (
          <div className="flex flex-wrap gap-2">
            <AlertDialog>
              <AlertDialogTrigger
                render={<Button size="sm" disabled={busy}>Approve</Button>}
              />
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Approve this response?</AlertDialogTitle>
                  <AlertDialogDescription>
                    {item.type === 'FOUND'
                      ? 'The item is reserved for this person and other pending responses are rejected. Contact details unlock now; pickup is not complete until handover is verified.'
                      : 'The report is resolved and this response completes. Other pending responses are rejected.'}
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel>Cancel</AlertDialogCancel>
                  <AlertDialogAction onClick={() => onDecide('APPROVED')}>
                    Approve response
                  </AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
            <Button
              variant="outline"
              size="sm"
              disabled={busy}
              onClick={() => onDecide('REJECTED')}
            >
              Reject
            </Button>
          </div>
        )}

        {claim.status === 'PENDING' && item.status !== 'OPEN' && (
          <p className="m-0 text-xs text-muted-foreground">
            This report is no longer open, so the response stays pending history only.
          </p>
        )}

        <div className="mt-3 grid gap-3">
          <ContactPanel claim={claim} userId={userId} />
          {item.type === 'FOUND' && (claim.status === 'APPROVED' || claim.status === 'COMPLETED') && (
            <HandoverPanel
              claim={claim}
              onChanged={() => { void invalidateWorkflow(queryClient) }}
            />
          )}
          <ClaimEvidence claimId={claim.id} />
        </div>
      </CardContent>
    </Card>
  )
}
