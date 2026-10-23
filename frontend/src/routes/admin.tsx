import { createFileRoute, Link, Navigate } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
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
import { Alert, AlertDescription } from '#/components/ui/alert.tsx'
import { Badge } from '#/components/ui/badge.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Card, CardContent } from '#/components/ui/card.tsx'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '#/components/ui/table.tsx'
import { StatusBadge } from '../components/StatusBadge'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { timeAgo } from '../lib/format'
import { invalidateWorkflow, queryKeys } from '../lib/query'
import type { AdminStats, Item } from '../lib/types'

export const Route = createFileRoute('/admin')({ component: AdminPage })

function AdminPage() {
  const { user, hydrated } = useAuth()
  const queryClient = useQueryClient()
  const isAdmin = hydrated && user?.role === 'ADMIN'

  const {
    data: stats,
    isError: statsFailed,
    error: statsError,
    refetch: refetchStats,
  } = useQuery({
    queryKey: queryKeys.adminStats(),
    queryFn: ({ signal }) => api<AdminStats>('/admin/stats', { signal }),
    enabled: isAdmin,
  })

  const { data: items, isLoading, isError, error, refetch } = useQuery({
    queryKey: queryKeys.adminItems(),
    queryFn: ({ signal }) => api<Item[]>('/admin/items', { signal }),
    enabled: isAdmin,
  })

  const [removeError, setRemoveError] = useState<string | null>(null)
  const [confirmId, setConfirmId] = useState<number | null>(null)

  const remove = useMutation({
    mutationFn: (id: number) => api<void>(`/items/${id}`, { method: 'DELETE' }),
    onSuccess: async () => {
      setRemoveError(null)
      setConfirmId(null)
      await invalidateWorkflow(queryClient)
    },
    onError: (err, id) => {
      const title = items?.find((i) => i.id === id)?.title ?? `report ${id}`
      setRemoveError(`Couldn't delete “${title}”: ${(err as Error).message}`)
    },
  })

  if (hydrated && user?.role !== 'ADMIN') {
    return <Navigate to="/" />
  }

  const cards: Array<[string, number | undefined]> = [
    ['Items', stats?.items],
    ['Open', stats?.open],
    ['Reserved', stats?.reserved],
    ['Resolved', stats?.resolved],
    ['Closed', stats?.closed],
    ['Claims', stats?.claims],
    ['Users', stats?.users],
  ]

  return (
    <main className="demo-page demo-page-wide">
      <p className="island-kicker mb-2">Admin</p>
      <h1 className="demo-title mb-8">Dashboard</h1>

      {statsFailed && (
        <Alert variant="destructive" className="mb-4">
          <AlertDescription className="flex items-center gap-3">
            <span>{(statsError as Error).message}</span>
            <Button size="sm" variant="outline" onClick={() => void refetchStats()}>
              Retry
            </Button>
          </AlertDescription>
        </Alert>
      )}
      <div className="mb-8 grid grid-cols-2 gap-4 sm:grid-cols-4 lg:grid-cols-7">
        {cards.map(([label, value]) => (
          <Card key={label} className="gap-0 py-0 text-center">
            <CardContent className="p-5">
              <div className="display-title text-[1.75rem] font-bold tracking-[-0.03em] text-foreground tabular-nums">
                {value ?? '—'}
              </div>
              <div className="mt-0.5 text-[0.8125rem] text-muted-foreground">{label}</div>
            </CardContent>
          </Card>
        ))}
      </div>

      {!hydrated && (
        <p className="text-sm text-muted-foreground">Checking your account…</p>
      )}

      <Card className="gap-0 overflow-hidden py-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>ID</TableHead>
              <TableHead>Type</TableHead>
              <TableHead>Title</TableHead>
              <TableHead>Reporter</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Posted</TableHead>
              <TableHead><span className="sr-only">Actions</span></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading && (
              <TableRow>
                <TableCell colSpan={7} className="h-20 text-center text-muted-foreground">
                  Loading…
                </TableCell>
              </TableRow>
            )}
            {isError && (
              <TableRow>
                <TableCell colSpan={7} className="h-20 text-center">
                  <Alert variant="destructive">
                    <AlertDescription className="flex items-center justify-center gap-3">
                      <span>{(error as Error).message}</span>
                      <Button size="sm" variant="outline" onClick={() => void refetch()}>
                        Retry
                      </Button>
                    </AlertDescription>
                  </Alert>
                </TableCell>
              </TableRow>
            )}
            {items?.map((item) => (
              <TableRow key={item.id}>
                <TableCell className="text-xs text-muted-foreground">#{item.id}</TableCell>
                <TableCell><Badge variant="outline">{item.type}</Badge></TableCell>
                <TableCell>
                  <Link
                    to="/items/$id"
                    params={{ id: String(item.id) }}
                    className="font-semibold no-underline"
                  >
                    {item.title}
                  </Link>
                  <span className="block text-xs text-[var(--muted-foreground)]">
                    {item.category} · {item.location}
                  </span>
                </TableCell>
                <TableCell className="text-sm">{item.reporterName}</TableCell>
                <TableCell><StatusBadge status={item.status} /></TableCell>
                <TableCell className="text-xs text-muted-foreground">
                  {timeAgo(item.createdAt)}
                </TableCell>
                <TableCell>
                  {item.status === 'RESERVED' ? (
                    <span className="text-xs text-muted-foreground">Release first</span>
                  ) : (
                    <AlertDialog
                      open={confirmId === item.id}
                      onOpenChange={(open) => {
                        if (open) {
                          setConfirmId(item.id)
                          setRemoveError(null)
                        } else if (!remove.isPending) {
                          setConfirmId(null)
                        }
                      }}
                    >
                      <AlertDialogTrigger
                        render={
                          <Button variant="destructive" size="sm" disabled={remove.isPending}>
                            Delete
                          </Button>
                        }
                      />
                      <AlertDialogContent>
                        <AlertDialogHeader>
                          <AlertDialogTitle>Delete this report?</AlertDialogTitle>
                          <AlertDialogDescription>
                            “{item.title}” disappears from browsing and matching. Claim
                            history is preserved and this cannot be undone from the app.
                          </AlertDialogDescription>
                        </AlertDialogHeader>
                        {removeError && (
                          <Alert variant="destructive">
                            <AlertDescription>{removeError}</AlertDescription>
                          </Alert>
                        )}
                        <AlertDialogFooter>
                          <AlertDialogCancel disabled={remove.isPending}>Cancel</AlertDialogCancel>
                          <AlertDialogAction
                            variant="destructive"
                            disabled={remove.isPending}
                            onClick={(event) => {
                              // The dialog stays open until the server accepts
                              // the delete, so a failure keeps its context.
                              event.preventDefault()
                              remove.mutate(item.id)
                            }}
                          >
                            {remove.isPending ? 'Deleting…' : 'Delete report'}
                          </AlertDialogAction>
                        </AlertDialogFooter>
                      </AlertDialogContent>
                    </AlertDialog>
                  )}
                </TableCell>
              </TableRow>
            ))}
            {items && items.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} className="h-20 text-center text-muted-foreground">
                  No reports yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </Card>
    </main>
  )
}
