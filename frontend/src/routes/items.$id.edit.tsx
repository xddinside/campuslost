import { createFileRoute, Link, Navigate, useNavigate } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ArrowLeft } from '@phosphor-icons/react'
import { Alert, AlertDescription } from '#/components/ui/alert.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Card, CardContent } from '#/components/ui/card.tsx'
import { DatePicker } from '#/components/ui/date-picker'
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
import { PhotoField } from '../components/PhotoField'
import { OwnershipQuestionField } from '../features/ownership'
import { ApiError, api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { CATEGORIES, dateInputToIso, dateInputValue } from '../lib/format'
import { invalidateWorkflow, queryKeys } from '../lib/query'
import type { ItemEdit, ItemPatch } from '../lib/types'

export const Route = createFileRoute('/items/$id/edit')({
  component: EditItemPage,
})

function EditItemPage() {
  const { id } = Route.useParams()
  const { user, hydrated } = useAuth()
  const userId = user?.id ?? 0

  const { data, isLoading, isError, error } = useQuery({
    queryKey: queryKeys.accountItemEdit(userId, id),
    queryFn: () => api<ItemEdit>(`/items/${id}/edit`),
    enabled: hydrated && !!user,
  })

  if (hydrated && !user) {
    return <Navigate to="/login" search={{ redirect: `/items/${id}/edit` }} />
  }

  if (!hydrated || isLoading) {
    return (
      <main className="demo-page">
        <Card className="rounded-xl shadow-none ring-border">
          <CardContent className="text-sm text-muted-foreground">Loading report…</CardContent>
        </Card>
      </main>
    )
  }

  if (isError || !data) {
    return (
      <main className="demo-page">
        <Alert variant="destructive">
          <AlertDescription>
            {(error as Error).message}
          </AlertDescription>
        </Alert>
        <Button render={<Link to="/items/$id" params={{ id }} />} variant="outline" className="mt-4">
          Back to report
        </Button>
      </main>
    )
  }

  return <EditForm key={data.version} edit={data} />
}

function EditForm({ edit }: { edit: ItemEdit }) {
  const { user } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [title, setTitle] = useState(edit.title)
  const [description, setDescription] = useState(edit.description)
  const [category, setCategory] = useState(edit.category)
  const [location, setLocation] = useState(edit.location)
  const [date, setDate] = useState(dateInputValue(edit.dateOccurred))
  const [ownershipQuestion, setOwnershipQuestion] = useState(edit.ownershipQuestion ?? '')
  const [questionValidity, setQuestionValidity] = useState<{ valid: boolean; message?: string }>({ valid: true })
  const [photo, setPhoto] = useState({
    photoId: edit.photoId,
    photoUrl: edit.photoUrl,
  })
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const mutation = useMutation({
    mutationFn: (patch: ItemPatch) =>
      api<ItemEdit>(`/items/${edit.id}`, {
        method: 'PATCH',
        body: JSON.stringify(patch),
      }),
    onSuccess: async () => {
      await invalidateWorkflow(queryClient)
      await navigate({ to: '/items/$id', params: { id: String(edit.id) } })
    },
    onError: async (err) => {
      if (err instanceof ApiError) {
        setFieldErrors(err.fieldErrors)
        setError(err.code === 'STALE_VERSION'
          ? 'Someone else changed this report while you were editing. Reload the latest version.'
          : err.message)
        if (err.code === 'STALE_VERSION') {
          await queryClient.invalidateQueries({
            queryKey: queryKeys.accountItemEdit(user?.id ?? 0, edit.id),
          })
        }
      } else {
        setError('Could not save your changes.')
      }
    },
  })

  function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setFieldErrors({})
    if (!questionValidity.valid) {
      setError(questionValidity.message ?? 'Fix the ownership question first.')
      return
    }
    mutation.mutate({
      version: edit.version,
      title: title.trim(),
      description: description.trim(),
      category,
      location: location.trim(),
      dateOccurred: date ? dateInputToIso(date) : edit.dateOccurred,
      ownershipQuestion: edit.type === 'FOUND'
        ? (ownershipQuestion.trim() || null)
        : undefined,
      photoId: photo.photoId,
    })
  }

  return (
    <main className="demo-page">
      <Link to="/items/$id" params={{ id: String(edit.id) }} className="detail-back-link">
        <ArrowLeft aria-hidden="true" size={18} weight="bold" />
        Back to report
      </Link>

      <div className="rise-in mb-6">
        <p className="island-kicker mb-2">Edit report</p>
        <h1 className="demo-title mb-2">{edit.title}</h1>
        <p className="demo-muted">
          {edit.type === 'LOST'
            ? 'Lost report — only the details you entered can change.'
            : 'Found report — keep identifying details out of the public description.'}
        </p>
      </div>

      <form onSubmit={onSubmit} className="rise-in">
        <Card className="report-form-card rounded-xl">
          <CardContent className="grid gap-5">
            <div className="grid gap-2">
              <span className="text-sm font-medium">Report type</span>
              <p className="m-0 rounded-lg bg-muted p-3 text-sm text-muted-foreground">
                {edit.type === 'LOST' ? 'Lost item' : 'Found item'} — a report cannot change
                between lost and found.
              </p>
            </div>

            <Label className="grid gap-2">
              Title
              <Input
                className="report-field"
                required
                maxLength={120}
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                aria-invalid={fieldErrors.title ? 'true' : undefined}
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
              />
              {fieldErrors.description && (
                <span className="text-xs text-destructive">{fieldErrors.description}</span>
              )}
            </Label>

            <div className="grid gap-5 sm:grid-cols-2">
              <div className="grid gap-2">
                <Label htmlFor="edit-category">Category</Label>
                <Select value={category} onValueChange={(value) => value && setCategory(value)}>
                  <SelectTrigger id="edit-category" className="report-field w-full">
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
                />
              </Label>
              <div className="grid gap-2">
                <Label htmlFor="edit-date">
                  {edit.type === 'LOST' ? 'Lost on' : 'Found on'}
                </Label>
                <DatePicker
                  id="edit-date"
                  required
                  max={new Date().toISOString().slice(0, 10)}
                  value={date}
                  onValueChange={setDate}
                  className="report-field"
                />
              </div>
            </div>

            {edit.type === 'FOUND' && (
              <OwnershipQuestionField
                type={edit.type}
                isNew={false}
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
              <Button type="submit" className="h-10 rounded-lg px-6" disabled={mutation.isPending}>
                {mutation.isPending ? 'Saving…' : 'Save changes'}
              </Button>
              <Button
                variant="outline"
                className="h-10 rounded-lg"
                type="button"
                render={<Link to="/items/$id" params={{ id: String(edit.id) }} />}
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
