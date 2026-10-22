import { createFileRoute, Navigate, useRouter } from '@tanstack/react-router'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { Alert, AlertDescription } from '#/components/ui/alert.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Card, CardContent } from '#/components/ui/card.tsx'
import { DatePicker } from '../components/ui/date-picker'
import { Input } from '#/components/ui/input.tsx'
import { Label } from '#/components/ui/label.tsx'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '#/components/ui/select.tsx'
import { Textarea } from '#/components/ui/textarea.tsx'
import { ToggleGroup, ToggleGroupItem } from '#/components/ui/toggle-group.tsx'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { CATEGORIES } from '../lib/format'
import type { ItemInput, ItemType } from '../lib/types'

export const Route = createFileRoute('/report')({ component: ReportPage })

function ReportPage() {
  const { user, hydrated } = useAuth()
  const router = useRouter()
  const [type, setType] = useState<ItemType>('LOST')
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState(CATEGORIES[0])
  const [location, setLocation] = useState('')
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (input: ItemInput) => api<{ id: number }>('/items', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
    onSuccess: (item) => {
      router.navigate({ to: '/items/$id', params: { id: String(item.id) } })
    },
    onError: (err) => setError((err as Error).message),
  })

  if (hydrated && !user) {
    return <Navigate to="/login" />
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    const dateIso = date
      ? new Date(`${date}T12:00:00`).toISOString()
      : new Date().toISOString()
    mutation.mutate({
      type,
      title,
      description,
      category,
      location,
      dateOccurred: dateIso,
    })
  }

  return (
    <main className="demo-page">
      <div className="rise-in">
        <p className="island-kicker mb-2">Report</p>
        <h1 className="demo-title mb-2">Report an item</h1>
        <p className="demo-muted mb-8">
          Tell the community what happened — be specific so a match is easy to
          make.
        </p>
      </div>

      <form onSubmit={onSubmit} className="rise-in">
        <Card className="report-form-card rounded-xl">
          <CardContent className="grid gap-5">
            <ToggleGroup
              value={[type]}
              onValueChange={(values) => {
                const nextType = values[0]
                if (nextType) setType(nextType as ItemType)
              }}
              variant="default"
              spacing={1}
              className="w-full rounded-2xl border border-border bg-muted p-1.5"
            >
              {(
                [
                  ['LOST', 'I lost something'],
                  ['FOUND', 'I found something'],
                ] as const
              ).map(([value, label]) => (
                <ToggleGroupItem
                  key={value}
                  value={value}
                  className="h-10 flex-1 rounded-lg px-4 text-sm font-bold text-muted-foreground hover:bg-card/60 hover:text-foreground aria-pressed:bg-card aria-pressed:text-foreground aria-pressed:shadow-sm aria-pressed:hover:bg-card"
                >
                  {label}
                </ToggleGroupItem>
              ))}
            </ToggleGroup>

            <Label className="grid gap-2">
              Title
              <Input
                className="report-field"
                required
                maxLength={120}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder={
                  type === 'LOST'
                    ? 'e.g. Black MacBook Pro 14" with a blue case'
                    : 'e.g. Water bottle found near the gym'
                }
              />
            </Label>

            <Label className="grid gap-2">
              Description
              <Textarea
                className="report-field min-h-28 py-3"
                required
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="Include distinguishing details, what happened, where it was left, and how to verify ownership…"
              />
            </Label>

            <div className="grid gap-5 sm:grid-cols-2">
              <div className="grid gap-2">
                <Label htmlFor="item-category">Category</Label>
                <Select
                  value={category}
                  onValueChange={(value) => value && setCategory(value)}
                >
                  <SelectTrigger
                    id="item-category"
                    className="report-field w-full"
                  >
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent align="start">
                    {CATEGORIES.map((categoryName) => (
                      <SelectItem key={categoryName} value={categoryName}>
                        {categoryName}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <Label className="grid gap-2">
                Location
                <Input
                  className="report-field"
                  required
                  value={location}
                  onChange={(e) => setLocation(e.target.value)}
                  placeholder="e.g. Central Library, 2nd floor"
                />
              </Label>
              <div className="grid gap-2">
                <Label htmlFor="date-occurred">
                  {type === 'LOST' ? 'Lost on' : 'Found on'}
                </Label>
                <DatePicker
                  id="date-occurred"
                  required
                  max={new Date().toISOString().slice(0, 10)}
                  value={date}
                  onValueChange={setDate}
                  className="report-field"
                />
              </div>
            </div>

            {error && (
              <Alert variant="destructive">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}

            <div className="flex gap-3">
              <Button
                className="h-10 rounded-lg px-6"
                type="submit"
                disabled={mutation.isPending}
              >
                {mutation.isPending ? 'Posting…' : 'Post item'}
              </Button>
              <Button
                variant="outline"
                className="h-10 rounded-lg"
                type="button"
                onClick={() => router.history.back()}
              >
                Cancel
              </Button>
            </div>
          </CardContent>
        </Card>
      </form>
    </main>
  )
}
