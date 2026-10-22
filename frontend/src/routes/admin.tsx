import { createFileRoute, Link, Navigate } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
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
import type { AdminStats, Item } from '../lib/types'

export const Route = createFileRoute('/admin')({ component: AdminPage })

function AdminPage() {
  const { user, hydrated } = useAuth()

  if (hydrated && user?.role !== 'ADMIN') {
    return <Navigate to="/" />
  }

  const { data: stats } = useQuery({
    queryKey: ['admin-stats'],
    queryFn: () => api<AdminStats>('/admin/stats'),
    enabled: hydrated && user?.role === 'ADMIN',
  })

  const queryClient = useQueryClient()
  const { data: items, isLoading } = useQuery({
    queryKey: ['admin-items'],
    queryFn: () => api<Item[]>('/admin/items'),
    enabled: hydrated && user?.role === 'ADMIN',
  })

  const remove = useMutation({
    mutationFn: (id: number) => api<void>(`/items/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin-items'] })
      queryClient.invalidateQueries({ queryKey: ['admin-stats'] })
    },
  })

  return (
    <main className="demo-page demo-page-wide">
      <p className="island-kicker mb-2">Admin</p>
      <h1 className="demo-title mb-8">Dashboard</h1>

      <div className="mb-8 grid grid-cols-2 gap-4 sm:grid-cols-5">
        {[
          ['Items', stats?.items],
          ['Open', stats?.open],
          ['Resolved', stats?.resolved],
          ['Claims', stats?.claims],
          ['Users', stats?.users],
        ].map(([label, value]) => (
          <Card key={label} className="gap-0 rounded-xl py-0 text-center shadow-none ring-border">
            <CardContent className="p-5">
              <div className="display-title text-3xl font-bold text-primary">
                {value ?? '—'}
              </div>
              <div className="island-kicker mt-1">{label}</div>
            </CardContent>
          </Card>
        ))}
      </div>

      <Card className="gap-0 rounded-xl py-0 shadow-none ring-border">
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
            {items?.map((item) => (
              <TableRow key={item.id}>
                <TableCell className="text-xs text-muted-foreground">#{item.id}</TableCell>
                <TableCell>
                  <Badge variant="outline">{item.type}</Badge>
                </TableCell>
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
                <TableCell>
                  <StatusBadge status={item.status} />
                </TableCell>
                <TableCell className="text-xs text-muted-foreground">
                  {timeAgo(item.createdAt)}
                </TableCell>
                <TableCell>
                  <AlertDialog>
                    <AlertDialogTrigger
                      render={
                        <Button variant="destructive" size="sm" disabled={remove.isPending}>
                          Delete
                        </Button>
                      }
                    />
                    <AlertDialogContent>
                      <AlertDialogHeader>
                        <AlertDialogTitle>Delete this item?</AlertDialogTitle>
                        <AlertDialogDescription>
                          “{item.title}” and all of its claims will be deleted. This cannot be undone.
                        </AlertDialogDescription>
                      </AlertDialogHeader>
                      <AlertDialogFooter>
                        <AlertDialogCancel>Cancel</AlertDialogCancel>
                        <AlertDialogAction
                          variant="destructive"
                          onClick={() => remove.mutate(item.id)}
                        >
                          Delete item
                        </AlertDialogAction>
                      </AlertDialogFooter>
                    </AlertDialogContent>
                  </AlertDialog>
                </TableCell>
              </TableRow>
            ))}
            {items && items.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} className="h-20 text-center text-muted-foreground">
                  No items yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </Card>
    </main>
  )
}
