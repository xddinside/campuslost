import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { MagnifyingGlass, X } from '@phosphor-icons/react'
import { Alert, AlertDescription } from '#/components/ui/alert.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Card, CardContent } from '#/components/ui/card.tsx'
import { Input } from '#/components/ui/input.tsx'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '#/components/ui/select.tsx'
import { Skeleton } from '#/components/ui/skeleton.tsx'
import { Tabs, TabsList, TabsTrigger } from '#/components/ui/tabs.tsx'
import { api, qs } from '../lib/api'
import { CATEGORIES, formatDate, statusLabel } from '../lib/format'
import { queryKeys } from '../lib/query'
import type { Item, ItemStatus, ItemType } from '../lib/types'

type TypeFilter = 'ALL' | ItemType
type StatusFilter = ItemStatus

interface BrowseSearch {
  q?: string
  type?: TypeFilter
  category?: string
  location?: string
  status?: StatusFilter
}

const STATUSES: StatusFilter[] = ['OPEN', 'RESERVED', 'RESOLVED', 'CLOSED']

function asType(value: unknown): TypeFilter | undefined {
  return value === 'LOST' || value === 'FOUND' || value === 'ALL' ? value : undefined
}

function asStatus(value: unknown): StatusFilter | undefined {
  return typeof value === 'string' && (STATUSES as string[]).includes(value)
    ? (value as StatusFilter)
    : undefined
}

function asText(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

export const Route = createFileRoute('/')({
  validateSearch: (search: Record<string, unknown>): BrowseSearch => ({
    q: asText(search.q),
    type: asType(search.type),
    category: asText(search.category),
    location: asText(search.location),
    status: asStatus(search.status),
  }),
  component: BrowsePage,
})

function BrowsePage() {
  const search = Route.useSearch()
  const navigate = useNavigate({ from: '/' })
  const type = search.type ?? 'ALL'
  const category = search.category ?? ''
  const status = search.status ?? 'OPEN'
  const [q, setQ] = useState(search.q ?? '')
  const [location, setLocation] = useState(search.location ?? '')

  useEffect(() => { setQ(search.q ?? '') }, [search.q])
  useEffect(() => { setLocation(search.location ?? '') }, [search.location])

  useEffect(() => {
    const timer = setTimeout(() => {
      if ((search.q ?? '') === q.trim()) return
      void navigate({
        search: (prev) => ({ ...prev, q: q.trim() || undefined }),
        replace: true,
      })
    }, 300)
    return () => clearTimeout(timer)
  }, [q, search.q, navigate])

  useEffect(() => {
    const timer = setTimeout(() => {
      if ((search.location ?? '') === location.trim()) return
      void navigate({
        search: (prev) => ({ ...prev, location: location.trim() || undefined }),
        replace: true,
      })
    }, 300)
    return () => clearTimeout(timer)
  }, [location, search.location, navigate])

  function update(patch: Partial<BrowseSearch>) {
    void navigate({
      search: (prev) => ({ ...prev, ...patch }),
      replace: true,
    })
  }

  const filters = {
    type: type === 'ALL' ? undefined : type,
    category: category || undefined,
    location: search.location,
    q: search.q,
    status,
  }

  const { data: items, isLoading, isError, error } = useQuery({
    queryKey: queryKeys.publicItems(filters),
    queryFn: () =>
      api<Item[]>(
        `/items${qs({
          type: filters.type,
          category: filters.category,
          location: filters.location,
          q: filters.q,
          status: filters.status,
        })}`,
      ),
  })

  const hasFilters = Boolean(
    search.q || search.location || (search.type && search.type !== 'ALL') || search.category,
  )
  const countLabel = items
    ? `${items.length} ${items.length === 1 ? 'report' : 'reports'}`
    : null

  return (
    <main className="page-wrap pb-10">
      <section className="browse-intro">
        <div>
          <h1 className="browse-title">Find what went missing.</h1>
          <p className="mb-0 mt-3 max-w-xl text-[var(--muted-foreground)]">
            Search campus reports or post an item so its owner can find it.
            Found something? Leave identifying details out of the public description.
          </p>
        </div>
        <Button render={<Link to="/report" />} size="lg" className="shrink-0">
          Report an item
        </Button>
      </section>

      <section className="filter-bar" aria-label="Filter reports">
        <Tabs
          value={type}
          onValueChange={(value) =>
            update({ type: value === 'ALL' ? undefined : (value as TypeFilter) })
          }
          aria-label="Report type"
        >
          <TabsList className="filter-tabs h-11 w-full rounded-lg p-1">
            {([
              ['ALL', 'All'],
              ['LOST', 'Lost'],
              ['FOUND', 'Found'],
            ] as const).map(([value, label]) => (
              <TabsTrigger key={value} value={value} className="h-full px-3">
                {label}
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>

        <div className="relative">
          <MagnifyingGlass
            aria-hidden="true"
            size={17}
            className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            className="h-11 rounded-lg border-border bg-card pl-9 pr-3"
            aria-label="Search reports"
            placeholder="Search reports"
            value={q}
            onChange={(event) => setQ(event.target.value)}
          />
        </div>

        <Input
          className="h-11 rounded-lg border-border bg-card px-3"
          aria-label="Filter by location"
          placeholder="Location"
          value={location}
          onChange={(event) => setLocation(event.target.value)}
        />

        <Select
          value={category || 'ALL'}
          onValueChange={(value) =>
            update({ category: !value || value === 'ALL' ? undefined : value })
          }
        >
          <SelectTrigger
            className="h-11 w-full rounded-lg border-border bg-card px-3"
            aria-label="Filter by category"
          >
            <SelectValue>{category || 'All categories'}</SelectValue>
          </SelectTrigger>
          <SelectContent align="start">
            <SelectItem value="ALL">All categories</SelectItem>
            {CATEGORIES.map((categoryName) => (
              <SelectItem key={categoryName} value={categoryName}>
                {categoryName}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select
          value={status}
          onValueChange={(value) => value && update({ status: value as StatusFilter })}
        >
          <SelectTrigger
            className="h-11 w-full rounded-lg border-border bg-card px-3"
            aria-label="Filter by status"
          >
            <SelectValue>{statusLabel[status]}</SelectValue>
          </SelectTrigger>
          <SelectContent align="start">
            {STATUSES.map((value) => (
              <SelectItem key={value} value={value}>
                {statusLabel[value]}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        {hasFilters && (
          <Button
            type="button"
            variant="ghost"
            className="h-11 rounded-lg px-3"
            onClick={() => {
              setQ('')
              setLocation('')
              void navigate({ search: { status: 'OPEN' }, replace: true })
            }}
          >
            <X aria-hidden="true" size={16} />
            Clear filters
          </Button>
        )}
      </section>

      <section>
        <div className="result-heading">
          <h2 className="m-0 text-sm font-bold text-[var(--foreground)]">Recent reports</h2>
          {countLabel && (
            <span className="text-xs text-[var(--muted-foreground)]" aria-live="polite">
              {countLabel} · {statusLabel[status].toLowerCase()}
            </span>
          )}
        </div>

        {isLoading && (
          <div className="item-list" aria-label="Loading reports">
            {Array.from({ length: 6 }).map((_, index) => (
              <div className="item-row" key={index} aria-hidden="true">
                <div className="w-full">
                  <Skeleton className="mb-3 h-3 w-2/5 rounded" />
                  <Skeleton className="mb-3 h-3 w-4/5 rounded" />
                  <Skeleton className="h-3 w-1/2 rounded" />
                </div>
              </div>
            ))}
          </div>
        )}

        {isError && (
          <Alert variant="destructive">
            <AlertDescription>
              Couldn't load reports: {(error as Error).message}
            </AlertDescription>
          </Alert>
        )}

        {items && items.length === 0 && (
          <Card className="gap-0 rounded-xl py-0 text-center shadow-none ring-border">
            <CardContent className="py-10 text-sm text-muted-foreground">
              <p className="m-0 font-bold text-foreground">No matching reports</p>
              <p className="mb-0 mt-1">
                {hasFilters
                  ? 'Try a broader search or clear one of the filters.'
                  : 'Nothing here yet. Report the first item.'}
              </p>
            </CardContent>
          </Card>
        )}

        {items && items.length > 0 && (
          <div className="item-list">
            {items.map((item) => (
              <ItemRow key={item.id} item={item} />
            ))}
          </div>
        )}
      </section>
    </main>
  )
}

function ItemRow({ item }: { item: Item }) {
  return (
    <Link
      to="/items/$id"
      params={{ id: String(item.id) }}
      className="item-row group no-underline"
    >
      {item.photoUrl && (
        <img
          src={item.photoUrl}
          alt=""
          className="item-row-photo"
          loading="lazy"
        />
      )}
      <div className="min-w-0 flex-1">
        <div className="item-row-heading">
          <h2 className="item-row-title">{item.title}</h2>
          {item.status !== 'OPEN' && (
            <span className="item-row-status">{statusLabel[item.status]}</span>
          )}
        </div>
        <p className="item-row-description line-clamp-1">{item.description}</p>
        <div className="item-row-meta">
          <span className="item-row-type">
            {item.type === 'LOST' ? 'Lost' : 'Found'}
          </span>
          <span aria-hidden="true">·</span>
          <span>{item.location}</span>
          <span aria-hidden="true">·</span>
          <time dateTime={item.dateOccurred}>{formatDate(item.dateOccurred)}</time>
          {item.pendingClaims > 0 && item.status === 'OPEN' && (
            <>
              <span aria-hidden="true">·</span>
              <span className="item-row-claim">
                {item.pendingClaims === 1
                  ? '1 pending response'
                  : `${item.pendingClaims} pending responses`}
              </span>
            </>
          )}
        </div>
      </div>
    </Link>
  )
}
