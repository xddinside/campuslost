import { createFileRoute, Link, Navigate, useRouter } from '@tanstack/react-router'
import { useState } from 'react'
import { Alert, AlertDescription } from '#/components/ui/alert.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Card, CardContent } from '#/components/ui/card.tsx'
import { Input } from '#/components/ui/input.tsx'
import { Label } from '#/components/ui/label.tsx'
import { ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'

interface LoginSearch {
  redirect?: string
}

export const Route = createFileRoute('/login')({
  validateSearch: (search: Record<string, unknown>): LoginSearch => {
    const redirect = typeof search.redirect === 'string' ? search.redirect : undefined
    return redirect && redirect.startsWith('/') && !redirect.startsWith('//')
      ? { redirect }
      : {}
  },
  component: LoginPage,
})

function LoginPage() {
  const { login, user, hydrated, sessionExpired } = useAuth()
  const router = useRouter()
  const { redirect } = Route.useSearch()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const demoMode = import.meta.env.VITE_DEMO_MODE === 'true'
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
      await login(email.trim(), password)
      await router.history.push(target)
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message)
        setFieldErrors(err.fieldErrors)
      } else {
        setError('Could not log in. Try again.')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="demo-page demo-center">
      <Card className="rise-in w-full max-w-md">
        <CardContent>
          <p className="island-kicker mb-2">Welcome back</p>
          <h1 className="demo-title mb-6">Log in</h1>

          {sessionExpired && (
            <Alert className="mb-4" role="status">
              <AlertDescription>
                Your session expired. Log in again to continue.
              </AlertDescription>
            </Alert>
          )}

          <form onSubmit={onSubmit} className="grid gap-4">
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
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                aria-invalid={fieldErrors.password ? 'true' : undefined}
                placeholder="••••••••"
              />
              {fieldErrors.password && (
                <span className="text-xs text-destructive">{fieldErrors.password}</span>
              )}
            </Label>
            {error && (
              <Alert variant="destructive">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
            <Button className="mt-1 h-11" type="submit" disabled={busy}>
              {busy ? 'Logging in…' : 'Log in'}
            </Button>
          </form>

          <p className="mt-5 text-center text-sm text-muted-foreground">
            No account yet?{' '}
            <Link
              to="/register"
              search={redirect ? { redirect } : {}}
              className="font-bold no-underline"
            >
              Sign up
            </Link>
          </p>

          {demoMode && (
            <div className="mt-6 rounded-2xl bg-fill p-4 text-xs leading-relaxed text-muted-foreground">
              <p className="m-0 mb-1 font-bold">Local demo accounts</p>
              <p className="m-0">
                Admin: admin@lostfound.dev / admin123
                <br />
                Users: alice@demo.dev, bob@demo.dev … / demo123
              </p>
            </div>
          )}
        </CardContent>
      </Card>
    </main>
  )
}
