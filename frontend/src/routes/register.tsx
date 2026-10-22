import { createFileRoute, Link, Navigate, useRouter } from '@tanstack/react-router'
import { useState } from 'react'
import { Alert, AlertDescription } from '#/components/ui/alert.tsx'
import { Button } from '#/components/ui/button.tsx'
import { Card, CardContent } from '#/components/ui/card.tsx'
import { Input } from '#/components/ui/input.tsx'
import { Label } from '#/components/ui/label.tsx'
import { useAuth } from '../lib/auth'

export const Route = createFileRoute('/register')({ component: RegisterPage })

function RegisterPage() {
  const { register, user, hydrated } = useAuth()
  const router = useRouter()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [phone, setPhone] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  if (hydrated && user) {
    return <Navigate to="/" />
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await register(name, email, password, phone || undefined)
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
          <p className="island-kicker mb-2">Join CampusLost</p>
          <h1 className="demo-title mb-6">Create your account</h1>
          <form onSubmit={onSubmit} className="grid gap-4">
          <Label className="grid gap-1.5">
            Name
            <Input
              className="h-11 rounded-lg border-border bg-input/50 px-3"
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Your name"
            />
          </Label>
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
              minLength={6}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="At least 6 characters"
            />
          </Label>
          <Label className="grid gap-1.5">
            Phone (optional)
            <Input
              className="h-11 rounded-lg border-border bg-input/50 px-3"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder="+1 555 000 0000"
            />
          </Label>
          {error && (
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
          <Button className="mt-1 h-10 rounded-lg" type="submit" disabled={busy}>
            {busy ? 'Creating account…' : 'Sign up'}
          </Button>
          </form>
          <p className="mt-5 text-center text-sm text-muted-foreground">
            Already have an account?{' '}
            <Link to="/login" className="font-bold no-underline">
              Log in
            </Link>
          </p>
        </CardContent>
      </Card>
    </main>
  )
}
