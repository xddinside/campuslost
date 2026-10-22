import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
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
import type { Item, ItemType } from '../lib/types'

export const Route = createFileRoute('/')({ component: BrowsePage })

type TypeFilter = 'ALL' | ItemType

function BrowsePage() {
  const [type, setType] = useState<TypeFilter>('ALL')
  const [category, setCategory] = useState('')
  const [q, setQ] = useState('')
  const [debouncedQ, setDebouncedQ] = useState('')

  useEffect(() => {
    const t = setTimeout(() => setDebouncedQ(q), 300)
    return () => clearTimeout(t)
  }, [q])

  const { data: items, isLoading, isError, error } = useQuery({
    queryKey: ['items', type, category, debouncedQ],
    queryFn: () =>
      api<Item[]>(
        `/items${qs({
          type: type === 'ALL' ? undefined : type,
          category: category || undefined,
          q: debouncedQ || undefined,
        })}`,
      ),
  })

  return (
    <main className="page-wrap pb-10">
      <section className="browse-intro">
        <div>
          <h1 className="browse-title">Find what went missing.</h1>
          <p className="mb-0 mt-3 max-w-xl text-[var(--muted-foreground)]">
            Search recent campus reports or post an item so its owner can find it.
          </p>
        </div>
        <Button render={<Link to="/report" />} size="lg" className="shrink-0">
          Report an item
        </Button>
      </section>

      <section className="filter-bar" aria-label="Filter items">
        <Tabs
          value={type}
          onValueChange={(value) => setType(value as TypeFilter)}
          aria-label="Item type"
        >
          <TabsList className="filter-tabs h-11 w-full rounded-lg p-1">
          {(
            [
              ['ALL', 'All'],
              ['LOST', 'Lost'],
              ['FOUND', 'Found'],
            ] as const
          ).map(([value, label]) => (
            <TabsTrigger
              key={value}
              value={value}
              className="h-full px-3"
            >
              {label}
            </TabsTrigger>
          ))}
          </TabsList>
        </Tabs>
        <Input
          className="h-11 rounded-lg border-border bg-card px-3"
          aria-label="Search items"
          placeholder="Search items"
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
        <Select
          value={category || 'ALL'}
          onValueChange={(value) => setCategory(value === 'ALL' ? '' : (value ?? ''))}
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
      </section>

      <section>
        <div className="result-heading">
          <h2 className="m-0 text-sm font-bold text-[var(--foreground)]">Recent reports</h2>
          {items && (
            <span className="text-xs text-[var(--muted-foreground)]">
              {items.length === 1 ? '1 item' : `${items.length} items`}
            </span>
          )}
        </div>
        {isLoading && (
          <div className="item-list" aria-label="Loading items">
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
              Couldn't load items: {(error as Error).message}
            </AlertDescription>
          </Alert>
        )}
        {items && items.length === 0 && (
          <Card className="gap-0 rounded-xl py-0 text-center shadow-none ring-border">
            <CardContent className="py-10 text-sm text-muted-foreground">
              <p className="m-0 font-bold text-foreground">No matching reports</p>
              <p className="mb-0 mt-1">Try a broader search or clear one of the filters.</p>
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
        {item.pendingClaims > 0 && (
          <>
            <span aria-hidden="true">·</span>
            <span className="item-row-claim">
              {item.pendingClaims === 1
                ? '1 pending claim'
                : `${item.pendingClaims} pending claims`}
            </span>
          </>
        )}
      </div>
    </Link>
  )
}
