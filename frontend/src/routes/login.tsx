import { createFileRoute, Link, Navigate, useRouter } from '@tanstack/react-router'
import { useState } from 'react'
import { Alert, AlertDescription } from '#/components/ui/alert.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Card, CardContent } from '#/components/ui/card.tsx'
import { Input } from '#/components/ui/input.tsx'
import { Label } from '#/components/ui/label.tsx'
import { useAuth } from '../lib/auth'

export const Route = createFileRoute('/login')({ component: LoginPage })

function LoginPage() {
  const { login, user, hydrated } = useAuth()
  const router = useRouter()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  if (hydrated && user) {
    return <NavigateHome />
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await login(email, password)
      router.navigate({ to: '/' })
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="demo-page demo-center">
      <Card className="rise-in w-full max-w-md rounded-xl shadow-none ring-border">
        <CardContent>
          <p className="island-kicker mb-2">Welcome back</p>
          <h1 className="demo-title mb-6">Log in</h1>
          <form onSubmit={onSubmit} className="grid gap-4">
          <Label className="grid gap-1.5">
            Email
            <Input
              className="h-11 rounded-lg border-border bg-input/50 px-3"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
            />
          </Label>
          <Label className="grid gap-1.5">
            Password
            <Input
              className="h-11 rounded-lg border-border bg-input/50 px-3"
              type="password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
            />
          </Label>
          {error && (
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
          <Button className="mt-1 h-10 rounded-lg" type="submit" disabled={busy}>
            {busy ? 'Logging in…' : 'Log in'}
          </Button>
          </form>
          <p className="mt-5 text-center text-sm text-muted-foreground">
            No account yet?{' '}
            <Link to="/register" className="font-bold no-underline">
              Sign up
            </Link>
          </p>
          <div className="mt-5 rounded-xl border border-dashed border-border bg-muted p-3 text-xs text-muted-foreground">
            <p className="m-0 mb-1 font-bold">Demo accounts</p>
            <p className="m-0">
              Admin: admin@lostfound.dev / admin123 · Users: alice@demo.dev,
              bob@demo.dev … / demo123
            </p>
          </div>
        </CardContent>
      </Card>
    </main>
  )
}

function NavigateHome() {
  return <Navigate to="/" />
}
