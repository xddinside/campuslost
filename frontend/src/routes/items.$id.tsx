import { createFileRoute, Link, useRouter } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
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
import {
  ArrowLeft,
  CalendarBlank,
  CheckCircle,
  Clock,
  EnvelopeSimple,
  MapPin,
  Phone,
  UserCircle,
} from '@phosphor-icons/react'
import { useRef, useState } from 'react'
import { ClaimBadge, StatusBadge } from '../components/StatusBadge'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { formatDate, timeAgo } from '../lib/format'
import type { Claim, Item } from '../lib/types'

export const Route = createFileRoute('/items/$id')({ component: ItemDetailPage })

function ItemDetailPage() {
  const { id } = Route.useParams()
  const { user, hydrated } = useAuth()

  const { data: item, isLoading, isError, error } = useQuery({
    queryKey: ['item', id],
    queryFn: () => api<Item>(`/items/${id}`),
  })

  const {
    data: myClaims,
    isLoading: isLoadingMyClaims,
    isError: isMyClaimsError,
  } = useQuery({
    queryKey: ['my-claims'],
    queryFn: () => api<Claim[]>('/me/claims'),
    enabled: hydrated && !!user,
  })

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
          <AlertDescription>Couldn't load item: {(error as Error).message}</AlertDescription>
        </Alert>
      </main>
    )
  }

  const canManage = hydrated && user && (user.role === 'ADMIN' || user.id === item.reporterId)
  const existingClaim = myClaims?.find((claim) => String(claim.itemId) === id)
  const canClaim = hydrated && user && !canManage && !existingClaim && item.status === 'OPEN'

  const occurredLabel = item.type === 'LOST' ? 'Lost on' : 'Found on'

  return (
    <main className="demo-page item-detail-page">
      <Link to="/" className="detail-back-link">
        <ArrowLeft aria-hidden="true" size={18} weight="bold" />
        Back to browse
      </Link>

      <div className="item-detail-layout rise-in">
        <Card className="item-detail-card gap-0 rounded-xl shadow-none ring-border">
          <CardContent className="p-0">
          <header>
            <div className="detail-badges">
              <Badge>
                {item.type === 'LOST' ? 'Lost item' : 'Found item'}
              </Badge>
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
              <div className="reporter-links">
                <a href={`mailto:${item.reporterEmail}`}>
                  <EnvelopeSimple aria-hidden="true" size={17} />
                  {item.reporterEmail}
                </a>
                {item.reporterPhone && (
                  <a href={`tel:${item.reporterPhone}`}>
                    <Phone aria-hidden="true" size={17} />
                    {item.reporterPhone}
                  </a>
                )}
              </div>
            </div>
          </section>
          </CardContent>
        </Card>

        <aside className="claim-sidebar" aria-label="Item actions">
          {canManage ? (
            <ManagePanel item={item} />
          ) : hydrated && user && isLoadingMyClaims ? (
            <Card className="rounded-xl shadow-none ring-border">
              <CardContent className="text-sm text-muted-foreground">
                Checking claim status…
              </CardContent>
            </Card>
          ) : existingClaim ? (
            <ExistingClaimPanel claim={existingClaim} item={item} />
          ) : hydrated && user && isMyClaimsError ? (
            <Alert variant="destructive">
              <AlertDescription>
                Unable to check your claim status. Refresh the page.
              </AlertDescription>
            </Alert>
          ) : canClaim ? (
            <ClaimPanel item={item} />
          ) : (
            <Card className="claim-closed-panel rounded-xl shadow-none ring-border">
              <CardContent>
              {item.status === 'OPEN' && hydrated && !user ? (
                <>
                  <h2>{item.type === 'LOST' ? 'Have you found this?' : 'Think this is yours?'}</h2>
                  <p>
                    {item.type === 'LOST'
                      ? `Log in to tell ${item.reporterName} where you saw or found it.`
                      : `Log in to send ${item.reporterName} proof that you own it.`}
                  </p>
                  <div className="flex flex-wrap gap-3">
                    <Button render={<Link to="/login" />}>
                      {item.type === 'LOST' ? 'Log in to respond' : 'Log in to claim'}
                    </Button>
                    <Button render={<Link to="/register" />} variant="outline">
                      Create account
                    </Button>
                  </div>
                </>
              ) : (
                <>
                  <h2>Responses are closed</h2>
                  <p>This item is no longer accepting responses.</p>
                </>
              )}
              </CardContent>
            </Card>
          )}

          {canManage && <ClaimsPanel item={item} />}
        </aside>
      </div>
    </main>
  )
}

function ManagePanel({ item }: { item: Item }) {
  const queryClient = useQueryClient()
  const router = useRouter()
  const { user } = useAuth()
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (status: 'OPEN' | 'RESOLVED' | 'CLOSED') =>
      api<Item>(`/items/${item.id}/status`, {
        method: 'PATCH',
        body: JSON.stringify({ status }),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['item', String(item.id)] })
      queryClient.invalidateQueries({ queryKey: ['items'] })
    },
    onError: (err) => setError((err as Error).message),
  })

  const remove = useMutation({
    mutationFn: () => api<void>(`/items/${item.id}`, { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['items'] }),
  })

  return (
    <Card className="rounded-xl shadow-none ring-border">
      <CardContent>
        <p className="island-kicker mb-3">You manage this item</p>
        <div className="grid gap-2.5">
        {item.status !== 'RESOLVED' && (
          <Button
            disabled={mutation.isPending}
            onClick={() => mutation.mutate('RESOLVED')}
          >
            Mark as resolved
          </Button>
        )}
        {item.status === 'RESOLVED' && (
          <Button
            variant="outline"
            disabled={mutation.isPending}
            onClick={() => mutation.mutate('OPEN')}
          >
            Reopen item
          </Button>
        )}
        {(user?.role === 'ADMIN' || item.status === 'OPEN') && (
          <AlertDialog>
            <AlertDialogTrigger
              render={
                <Button variant="destructive" disabled={remove.isPending}>
                  {remove.isPending ? 'Deleting…' : 'Delete item'}
                </Button>
              }
            />
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Delete this item?</AlertDialogTitle>
                <AlertDialogDescription>
                  This item and all of its claims will be deleted. This cannot be undone.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction
                  variant="destructive"
                  onClick={() => {
                    remove.mutate(undefined, {
                      onSuccess: () => router.navigate({ to: '/' }),
                    })
                  }}
                >
                  Delete item
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

function ClaimPanel({ item }: { item: Item }) {
  const queryClient = useQueryClient()
  const [message, setMessage] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [validationError, setValidationError] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const messageRef = useRef<HTMLTextAreaElement>(null)
  const isLostReport = item.type === 'LOST'

  const mutation = useMutation({
    mutationFn: (msg: string) =>
      api<Claim>(`/items/${item.id}/claims`, {
        method: 'POST',
        body: JSON.stringify({ message: msg }),
      }),
    onSuccess: () => {
      setDone(true)
      queryClient.invalidateQueries({ queryKey: ['item', String(item.id)] })
      queryClient.invalidateQueries({ queryKey: ['my-claims'] })
    },
    onError: (err) => setError(`Unable to send. ${(err as Error).message}`),
  })

  function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    const trimmedMessage = message.trim()

    if (trimmedMessage.length < 10) {
      setValidationError('Write at least 10 characters.')
      messageRef.current?.focus()
      return
    }

    setValidationError(null)
    mutation.mutate(trimmedMessage)
  }

  if (done) {
    return (
      <Alert className="claim-success" role="status">
        <CheckCircle aria-hidden="true" size={28} weight="fill" />
        <div>
          <AlertTitle>{isLostReport ? 'Message sent' : 'Claim sent'}</AlertTitle>
          <AlertDescription>{item.reporterName} will review it.</AlertDescription>
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
              : 'Share a detail that proves it’s yours.'}
          </p>
          <Label className="claim-label" htmlFor="claim-message">
            Message
          </Label>
          <Textarea
            ref={messageRef}
            id="claim-message"
            name="claim-message"
            className="claim-textarea rounded-lg border-border bg-input/50"
            value={message}
            onChange={(event) => {
              const nextMessage = event.target.value
              setMessage(nextMessage)
              if (nextMessage.trim().length >= 10) setValidationError(null)
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
          {validationError && (
            <p className="claim-error" id="claim-message-error">
              {validationError}
            </p>
          )}
          {error && (
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
          <Button
            className="claim-submit"
            type="submit"
            disabled={mutation.isPending}
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

function ExistingClaimPanel({ claim, item }: { claim: Claim; item: Item }) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const noun = item.type === 'LOST' ? 'Response' : 'Claim'
  const heading =
    claim.status === 'PENDING'
      ? `${noun} sent`
      : claim.status === 'APPROVED'
        ? `${noun} approved`
        : `${noun} declined`
  const detail =
    claim.status === 'PENDING'
      ? `${item.reporterName} hasn’t reviewed it yet.`
      : claim.status === 'APPROVED'
        ? `Contact ${item.reporterName} to arrange pickup.`
        : `${item.reporterName} didn’t confirm it.`

  const cancel = useMutation({
    mutationFn: () => api<void>(`/claims/${claim.id}`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['my-claims'] })
      queryClient.invalidateQueries({ queryKey: ['item', String(item.id)] })
    },
    onError: (err) => setError(`Unable to cancel. ${(err as Error).message}`),
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
        {error && (
          <Alert className="mt-3" variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
        {claim.status === 'PENDING' && (
          <AlertDialog>
            <AlertDialogTrigger
              render={
                <Button
                  className="mt-3"
                  variant="outline"
                  disabled={cancel.isPending}
                >
                  {cancel.isPending ? 'Cancelling…' : `Cancel ${noun.toLowerCase()}`}
                </Button>
              }
            />
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Cancel this {noun.toLowerCase()}?</AlertDialogTitle>
                <AlertDialogDescription>
                  Your message will be removed. You can send a new one later.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Keep it</AlertDialogCancel>
                <AlertDialogAction
                  variant="destructive"
                  onClick={() => cancel.mutate()}
                >
                  Cancel {noun.toLowerCase()}
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        )}
      </CardContent>
    </Card>
  )
}

function ClaimsPanel({ item }: { item: Item }) {
  const queryClient = useQueryClient()
  const { data: claims, isLoading } = useQuery({
    queryKey: ['claims', String(item.id)],
    queryFn: () => api<Claim[]>(`/items/${item.id}/claims`),
  })

  const decide = useMutation({
    mutationFn: ({ claimId, status }: { claimId: number; status: 'APPROVED' | 'REJECTED' }) =>
      api<Claim>(`/claims/${claimId}`, {
        method: 'PATCH',
        body: JSON.stringify({ status }),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['claims', String(item.id)] })
      queryClient.invalidateQueries({ queryKey: ['item', String(item.id)] })
    },
  })

  return (
    <Card className="rounded-xl shadow-none ring-border">
      <CardContent>
        <p className="island-kicker mb-3">
          Claims ({claims?.length ?? 0})
        </p>
      {isLoading && (
        <p className="m-0 text-sm text-muted-foreground">Loading claims…</p>
      )}
      {claims && claims.length === 0 && (
        <p className="m-0 text-sm text-muted-foreground">
          No claims yet. When someone claims this item, their message shows up
          here.
        </p>
      )}
      {claims && claims.length > 0 && (
        <ul className="m-0 grid gap-3 p-0">
          {claims.map((claim) => (
            <li key={claim.id}>
              <Card size="sm" className="rounded-xl bg-muted shadow-none ring-border">
                <CardContent>
              <div className="mb-1.5 flex flex-wrap items-center gap-2">
                <span className="text-sm font-bold text-foreground">
                  {claim.claimantName}
                </span>
                <ClaimBadge status={claim.status} />
                <span className="ml-auto text-xs text-muted-foreground">
                  {timeAgo(claim.createdAt)}
                </span>
              </div>
              <p className="m-0 mb-2 text-xs text-muted-foreground">
                {claim.claimantEmail}
              </p>
              <p className="m-0 mb-3 whitespace-pre-wrap text-sm text-foreground">
                {claim.message}
              </p>
              {claim.status === 'PENDING' && item.status === 'OPEN' && (
                <div className="flex gap-2">
                  <AlertDialog>
                    <AlertDialogTrigger
                      render={
                        <Button size="sm" disabled={decide.isPending}>
                          Approve
                        </Button>
                      }
                    />
                    <AlertDialogContent>
                      <AlertDialogHeader>
                        <AlertDialogTitle>Approve this claim?</AlertDialogTitle>
                        <AlertDialogDescription>
                          The item will be marked resolved and all other pending claims will be rejected.
                        </AlertDialogDescription>
                      </AlertDialogHeader>
                      <AlertDialogFooter>
                        <AlertDialogCancel>Cancel</AlertDialogCancel>
                        <AlertDialogAction
                          onClick={() =>
                            decide.mutate({ claimId: claim.id, status: 'APPROVED' })
                          }
                        >
                          Approve claim
                        </AlertDialogAction>
                      </AlertDialogFooter>
                    </AlertDialogContent>
                  </AlertDialog>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={decide.isPending}
                    onClick={() =>
                      decide.mutate({ claimId: claim.id, status: 'REJECTED' })
                    }
                  >
                    Reject
                  </Button>
                </div>
              )}
              {item.status !== 'OPEN' && (
                <p className="m-0 text-xs text-muted-foreground">
                  This item is closed, so this claim can't be approved or rejected.
                </p>
              )}
                </CardContent>
              </Card>
            </li>
          ))}
        </ul>
      )}
      </CardContent>
    </Card>
  )
}
