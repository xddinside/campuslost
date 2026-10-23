import { createFileRoute, Link, Navigate, useRouter } from '@tanstack/react-router'
import { useState } from 'react'
import { Alert, AlertDescription } from '#/components/ui/alert.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Card, CardContent } from '#/components/ui/card.tsx'
import { Input } from '#/components/ui/input.tsx'
import { Label } from '#/components/ui/label.tsx'
import { ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'

interface RegisterSearch {
  redirect?: string
}

export const Route = createFileRoute('/register')({
  validateSearch: (search: Record<string, unknown>): RegisterSearch => {
    const redirect = typeof search.redirect === 'string' ? search.redirect : undefined
    return redirect && redirect.startsWith('/') && !redirect.startsWith('//')
      ? { redirect }
      : {}
  },
  component: RegisterPage,
})

function RegisterPage() {
  const { register, user, hydrated } = useAuth()
  const router = useRouter()
  const { redirect } = Route.useSearch()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [phone, setPhone] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const target = redirect ?? '/'

  if (hydrated && user) {
    return <Navigate to={target} />
  }

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    setFieldErrors({})
    setBusy(true)
    try {
      await register(name.trim(), email.trim(), password, phone.trim() || undefined)
      await router.history.push(target)
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message)
        setFieldErrors(err.fieldErrors)
      } else {
        setError('Could not create your account. Try again.')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="demo-page demo-center">
      <Card className="rise-in w-full max-w-md">
        <CardContent>
          <p className="island-kicker mb-2">Join CampusLost</p>
          <h1 className="demo-title mb-6">Create your account</h1>
          <form onSubmit={onSubmit} className="grid gap-4">
            <Label className="grid gap-1.5">
              Name
              <Input
                className="h-11"
                required
                maxLength={100}
                autoComplete="name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                aria-invalid={fieldErrors.name ? 'true' : undefined}
                placeholder="Your name"
              />
              {fieldErrors.name && <span className="text-xs text-destructive">{fieldErrors.name}</span>}
            </Label>
            <Label className="grid gap-1.5">
              Email
              <Input
                className="h-11"
                type="email"
                required
                autoComplete="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                aria-invalid={fieldErrors.email ? 'true' : undefined}
                placeholder="you@example.com"
              />
              {fieldErrors.email && <span className="text-xs text-destructive">{fieldErrors.email}</span>}
            </Label>
            <Label className="grid gap-1.5">
              Password
              <Input
                className="h-11"
                type="password"
                required
                minLength={6}
                autoComplete="new-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                aria-invalid={fieldErrors.password ? 'true' : undefined}
                placeholder="At least 6 characters"
              />
              {fieldErrors.password && (
                <span className="text-xs text-destructive">{fieldErrors.password}</span>
              )}
            </Label>
            <Label className="grid gap-1.5">
              Phone (optional)
              <Input
                className="h-11"
                maxLength={30}
                autoComplete="tel"
                value={phone}
                onChange={(event) => setPhone(event.target.value)}
                placeholder="+1 555 000 0000"
              />
              {fieldErrors.phone && <span className="text-xs text-destructive">{fieldErrors.phone}</span>}
            </Label>
            {error && (
              <Alert variant="destructive">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
            <Button className="mt-1 h-11" type="submit" disabled={busy}>
              {busy ? 'Creating account…' : 'Sign up'}
            </Button>
          </form>
          <p className="mt-5 text-center text-sm text-muted-foreground">
            Already have an account?{' '}
            <Link
              to="/login"
              search={redirect ? { redirect } : {}}
              className="font-bold no-underline"
            >
              Log in
            </Link>
          </p>
        </CardContent>
      </Card>
    </main>
  )
}
