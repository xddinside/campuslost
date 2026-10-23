import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Bell, CheckCircle, XCircle } from '@phosphor-icons/react'
import { Button } from '#/components/ui/button.tsx'
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverTitle,
  PopoverTrigger,
} from '#/components/ui/popover.tsx'
import { api, qs } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { timeAgo } from '../../lib/format'
import { queryKeys } from '../../lib/query'
import type { NotificationBellProps } from '../../lib/feature-contracts'
import type { NotificationItem, NotificationPage, NotificationType } from './types'
import { NOTIFICATIONS_PAGE_SIZE, NOTIFICATIONS_POLL_MS } from './types'
import { notificationLinkTarget } from './target'

interface UnreadCountResponse {
  unreadCount: number
}

/**
 * Header bell with a compact inbox. It polls only while signed in and the tab
 * is visible, refreshes on focus, and keeps all reads scoped to the account
 * query key so switching accounts can never display the previous badge.
 */
export function NotificationBell(_props: NotificationBellProps) {
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const userId = user?.id ?? 0
  const notificationsKey = queryKeys.account(userId, 'notifications')

  const query = useInfiniteQuery({
    queryKey: notificationsKey,
    queryFn: ({ pageParam, signal }) =>
      api<NotificationPage>(
        `/me/notifications${qs({
          limit: NOTIFICATIONS_PAGE_SIZE,
          beforeId: pageParam,
        })}`,
        { signal },
      ),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (lastPage) => lastPage.nextBeforeId ?? undefined,
    enabled: Boolean(user),
    refetchInterval: NOTIFICATIONS_POLL_MS,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    staleTime: 10_000,
  })

  const markRead = useMutation({
    mutationFn: (id: number) =>
      api<NotificationItem>(`/me/notifications/${id}/read`, { method: 'PATCH' }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: notificationsKey })
    },
  })

  const markAllRead = useMutation({
    mutationFn: () =>
      api<UnreadCountResponse>('/me/notifications/read-all', { method: 'POST' }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: notificationsKey })
    },
  })

  if (!user) return null

  const notifications = query.data?.pages.flatMap((page) => page.notifications) ?? []
  const unreadCount = query.data?.pages[0]?.unreadCount ?? 0
  const badgeLabel = unreadCount > 99 ? '99+' : String(unreadCount)
  const triggerLabel = unreadCount > 0
    ? `Notifications, ${unreadCount} unread`
    : 'Notifications'

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        render={
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label={triggerLabel}
            className="relative"
          >
            <Bell
              aria-hidden="true"
              size={19}
              weight={unreadCount > 0 ? 'fill' : 'regular'}
            />
            {unreadCount > 0 && (
              <span
                aria-hidden="true"
                className="absolute -top-0.5 -right-0.5 flex min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[0.625rem] leading-4 font-bold text-white shadow-sm"
              >
                {badgeLabel}
              </span>
            )}
          </Button>
        }
      />

      <PopoverContent
        align="end"
        side="bottom"
        sideOffset={8}
        className="w-[min(92vw,24rem)] gap-0 overflow-hidden rounded-3xl p-0"
      >
        <div className="flex items-center justify-between gap-3 border-b border-hairline px-4 py-3">
          <PopoverTitle className="text-sm font-bold">Notifications</PopoverTitle>
          {unreadCount > 0 ? (
            <Button
              type="button"
              variant="ghost"
              size="xs"
              onClick={() => markAllRead.mutate()}
              disabled={markAllRead.isPending}
              className="text-xs"
            >
              {markAllRead.isPending ? 'Marking…' : 'Mark all read'}
            </Button>
          ) : (
            <span className="text-[0.6875rem] font-medium text-muted-foreground">
              {query.isSuccess ? 'All caught up' : ''}
            </span>
          )}
        </div>
        <PopoverDescription className="sr-only">
          Alerts about possible matches for your reports and updates to your claims.
        </PopoverDescription>

        <div className="max-h-[min(65vh,26rem)] overflow-y-auto overscroll-contain">
          {query.isPending ? (
            <NotificationSkeleton />
          ) : query.isError && !query.data ? (
            <NotificationError onRetry={() => void query.refetch()} />
          ) : notifications.length === 0 ? (
            <NotificationEmpty />
          ) : (
            <>
              {query.isError && (
                <p role="status" className="m-0 border-b border-hairline px-4 py-2 text-xs text-muted-foreground">
                  Couldn't refresh just now. Showing the last loaded alerts.
                </p>
              )}
              {(markRead.isError || markAllRead.isError) && (
                <p role="alert" className="m-0 border-b border-hairline px-4 py-2 text-xs text-destructive">
                  Couldn't update read state. Try again.
                </p>
              )}
              <ul className="m-0 divide-y divide-hairline p-0">
                {notifications.map((notification) => (
                  <li key={notification.id} className="list-none">
                    <NotificationRow
                      notification={notification}
                      onOpen={() => {
                        if (!notification.readAt) markRead.mutate(notification.id)
                        // The alert's destination renders claims, reports, and
                        // matches that this notification just changed; drop the
                        // account cache and the destination's public item cache
                        // so the target page refetches both.
                        void queryClient.invalidateQueries({
                          queryKey: queryKeys.account(user?.id ?? 0),
                        })
                        if (notification.itemId) {
                          void queryClient.invalidateQueries({
                            queryKey: queryKeys.publicItem(String(notification.itemId)),
                          })
                        }
                        setOpen(false)
                      }}
                    />
                  </li>
                ))}
              </ul>
              {query.hasNextPage && (
                <div className="border-t border-hairline p-2">
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="w-full text-xs"
                    disabled={query.isFetchingNextPage}
                    onClick={() => void query.fetchNextPage()}
                  >
                    {query.isFetchingNextPage ? 'Loading…' : 'Load older alerts'}
                  </Button>
                </div>
              )}
            </>
          )}
        </div>
      </PopoverContent>
    </Popover>
  )
}

function NotificationRow({
  notification,
  onOpen,
}: {
  notification: NotificationItem
  onOpen: () => void
}) {
  const unread = !notification.readAt
  const className = [
    'flex gap-3 px-4 py-3 no-underline transition-colors',
    'text-foreground hover:bg-fill',
    'focus-visible:ring-3 focus-visible:ring-ring/30 focus-visible:outline-none focus-visible:ring-inset',
    unread ? 'bg-primary/6' : '',
  ].join(' ')

  const content = (
    <>
      <span
        className={`mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full ${toneFor(notification.type)}`}
      >
        <NotificationTypeIcon type={notification.type} />
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-start gap-2">
          <span
            className={`min-w-0 text-sm ${
              unread
                ? 'font-semibold text-foreground'
                : 'font-medium text-muted-foreground'
            }`}
          >
            {notification.title}
          </span>
          {unread && (
            <span
              aria-label="Unread"
              className="mt-1.5 size-1.5 shrink-0 rounded-full bg-primary"
            />
          )}
        </span>
        <span className="mt-0.5 block text-xs leading-snug text-muted-foreground">
          {notification.body}
        </span>
        <span className="mt-1 block text-[0.6875rem] font-medium text-muted-foreground">
          {timeAgo(notification.createdAt)}
        </span>
      </span>
    </>
  )

  const target = notificationLinkTarget(notification)
  return (
    <Link
      to={target.to}
      params={target.params}
      search={target.search}
      onClick={onOpen}
      className={className}
    >
      {content}
    </Link>
  )
}

function NotificationTypeIcon({ type }: { type: NotificationType }) {
  if (type === 'CLAIM_REJECTED') {
    return <XCircle aria-hidden="true" size={17} weight="fill" />
  }
  if (type === 'MATCH_FOUND') {
    return <Bell aria-hidden="true" size={17} weight="fill" />
  }
  return <CheckCircle aria-hidden="true" size={17} weight="fill" />
}

function toneFor(type: NotificationType): string {
  if (type === 'CLAIM_REJECTED') return 'bg-destructive/12 text-destructive-foreground'
  if (type === 'MATCH_FOUND') return 'bg-primary/10 text-primary'
  return 'bg-success/12 text-success-foreground'
}

function NotificationSkeleton() {
  return (
    <div aria-hidden="true" className="divide-y divide-hairline">
      {[0, 1, 2].map((row) => (
        <div key={row} className="flex gap-3 px-4 py-3">
          <span className="mt-0.5 size-8 animate-pulse rounded-full bg-fill" />
          <span className="flex-1 space-y-2">
            <span className="block h-3 w-3/4 animate-pulse rounded bg-[var(--muted)]" />
            <span className="block h-3 w-full animate-pulse rounded bg-[var(--muted)]" />
            <span className="block h-2.5 w-16 animate-pulse rounded bg-[var(--muted)]" />
          </span>
        </div>
      ))}
    </div>
  )
}

function NotificationEmpty() {
  return (
    <div className="px-6 py-8 text-center">
      <span className="mx-auto mb-3 flex size-10 items-center justify-center rounded-full bg-fill text-muted-foreground">
        <Bell aria-hidden="true" size={19} />
      </span>
      <p className="m-0 text-sm font-bold">No new matches</p>
      <p className="m-0 mt-1 text-xs leading-relaxed text-muted-foreground">
        When a new report looks like a match, or one of your claims changes,
        it will appear here.
      </p>
    </div>
  )
}

function NotificationError({ onRetry }: { onRetry: () => void }) {
  return (
    <div role="alert" className="px-6 py-8 text-center">
      <p className="m-0 text-sm font-bold">Could not load alerts</p>
      <p className="m-0 mt-1 text-xs leading-relaxed text-muted-foreground">
        Check your connection and try again.
      </p>
      <Button type="button" variant="outline" size="sm" className="mt-3" onClick={onRetry}>
        Retry
      </Button>
    </div>
  )
}
