import { createFileRoute, Navigate, useRouter } from '@tanstack/react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
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
import { PhotoField } from '../components/PhotoField'
import { OwnershipQuestionField } from '../features/ownership'
import { ApiError, api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { CATEGORIES, dateInputToIso } from '../lib/format'
import { invalidateWorkflow } from '../lib/query'
import type { ItemInput, ItemType } from '../lib/types'

export const Route = createFileRoute('/report')({ component: ReportPage })

function ReportPage() {
  const { user, hydrated } = useAuth()
  const router = useRouter()
  const queryClient = useQueryClient()
  const [type, setType] = useState<ItemType>('LOST')
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState(CATEGORIES[0])
  const [location, setLocation] = useState('')
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [ownershipQuestion, setOwnershipQuestion] = useState('')
  const [questionValidity, setQuestionValidity] = useState<{ valid: boolean; message?: string }>({ valid: true })
  const [photo, setPhoto] = useState<{ photoId: string | null; photoUrl: string | null }>({
    photoId: null,
    photoUrl: null,
  })
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const mutation = useMutation({
    mutationFn: (input: ItemInput) => api<{ id: number }>('/items', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
    onSuccess: async (item) => {
      await invalidateWorkflow(queryClient)
      await router.navigate({ to: '/items/$id', params: { id: String(item.id) } })
    },
    onError: (err) => {
      if (err instanceof ApiError) {
        setFieldErrors(err.fieldErrors)
        setError(err.message)
      } else {
        setError('Could not post this report.')
      }
    },
  })

  if (hydrated && !user) {
    return <Navigate to="/login" search={{ redirect: '/report' }} />
  }

  function onSubmit(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    setFieldErrors({})
    if (!questionValidity.valid) {
      setError(questionValidity.message ?? 'Fix the ownership question first.')
      return
    }
    const dateIso = date ? dateInputToIso(date) : new Date().toISOString()
    mutation.mutate({
      type,
      title: title.trim(),
      description: description.trim(),
      category,
      location: location.trim(),
      dateOccurred: dateIso,
      ownershipQuestion: type === 'FOUND' ? (ownershipQuestion.trim() || null) : undefined,
      photoId: photo.photoId,
    })
  }

  return (
    <main className="demo-page">
      <div className="rise-in">
        <p className="island-kicker mb-2">Report</p>
        <h1 className="demo-title mb-2">Report an item</h1>
        <p className="demo-muted mb-8">
          Be specific enough to help a match, but keep identifying details out of
          a found-item description.
        </p>
      </div>

      <form onSubmit={onSubmit} className="rise-in">
        <Card className="report-form-card rounded-xl">
          <CardContent className="grid gap-5">
            <ToggleGroup
              value={[type]}
              onValueChange={(values) => {
                const nextType = values[0]
                if (nextType) {
                  setType(nextType as ItemType)
                  if (nextType === 'LOST') setOwnershipQuestion('')
                }
              }}
              variant="default"
              spacing={1}
              className="w-full rounded-2xl border border-border bg-muted p-1.5"
            >
              {([
                ['LOST', 'I lost something'],
                ['FOUND', 'I found something'],
              ] as const).map(([value, label]) => (
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
                onChange={(event) => setTitle(event.target.value)}
                aria-invalid={fieldErrors.title ? 'true' : undefined}
                placeholder={
                  type === 'LOST'
                    ? 'e.g. Black MacBook Pro 14" with a blue case'
                    : 'e.g. Water bottle found near the gym'
                }
              />
              {fieldErrors.title && <span className="text-xs text-destructive">{fieldErrors.title}</span>}
            </Label>

            <Label className="grid gap-2">
              Description
              <Textarea
                className="report-field min-h-28 py-3"
                required
                maxLength={4000}
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                aria-invalid={fieldErrors.description ? 'true' : undefined}
                placeholder={
                  type === 'FOUND'
                    ? 'Where it was found and when. Leave out details that would let anyone claim it.'
                    : 'Include distinguishing details and what happened.'
                }
              />
              <span className="text-xs text-muted-foreground">
                {description.length}/4000 characters
              </span>
              {fieldErrors.description && (
                <span className="text-xs text-destructive">{fieldErrors.description}</span>
              )}
            </Label>

            <div className="grid gap-5 sm:grid-cols-2">
              <div className="grid gap-2">
                <Label htmlFor="item-category">Category</Label>
                <Select value={category} onValueChange={(value) => value && setCategory(value)}>
                  <SelectTrigger id="item-category" className="report-field w-full">
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
                  maxLength={120}
                  value={location}
                  onChange={(event) => setLocation(event.target.value)}
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

            {type === 'FOUND' && (
              <OwnershipQuestionField
                type={type}
                isNew
                value={ownershipQuestion}
                onChange={setOwnershipQuestion}
                onValidityChange={setQuestionValidity}
              />
            )}

            <PhotoField value={photo} onChange={setPhoto} disabled={mutation.isPending} />

            {error && (
              <Alert variant="destructive">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}

            <div className="flex gap-3">
              <Button
                className="h-10 rounded-lg px-6"
                type="submit"
                disabled={mutation.isPending || !questionValidity.valid}
              >
                {mutation.isPending ? 'Posting…' : 'Post report'}
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
