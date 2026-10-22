import { Link, useNavigate } from '@tanstack/react-router'
import { Button } from '#/components/ui/button.tsx'
import { NotificationBell } from '#/features/notifications'
import { useAuth } from '../lib/auth'
import ThemeToggle from './ThemeToggle'

export default function Header() {
  const { user, hydrated, logout } = useAuth()
  const navigate = useNavigate()

  async function onLogout() {
    await logout()
    await navigate({ to: '/' })
  }

  return (
    <header className="sticky top-0 z-50 border-b border-[var(--border)] bg-[var(--background)] px-4">
      <nav className="page-wrap flex flex-wrap items-center gap-x-4 gap-y-2 py-3">
        <div className="m-0 flex-shrink-0 text-base font-bold tracking-tight">
          <Link
            to="/"
            className="inline-flex items-center gap-2 text-[var(--foreground)] no-underline"
          >
            <span className="brand-mark" aria-hidden="true">CL</span>
            CampusLost
          </Link>
        </div>

        <div className="order-3 flex w-full flex-wrap items-center gap-x-4 gap-y-1 pb-1 text-sm font-semibold sm:order-none sm:w-auto sm:flex-nowrap sm:pb-0">
          <Link
            to="/"
            className="nav-link"
            activeProps={{ className: 'nav-link is-active' }}
            activeOptions={{ exact: true }}
          >
            Browse
          </Link>
          <Link
            to="/report"
            className="nav-link"
            activeProps={{ className: 'nav-link is-active' }}
          >
            Report
          </Link>
          {hydrated && user && (
            <>
              <Link
                to="/my"
                className="nav-link"
                activeProps={{ className: 'nav-link is-active' }}
              >
                My items
              </Link>
              {user.role === 'ADMIN' && (
                <Link
                  to="/admin"
                  className="nav-link"
                  activeProps={{ className: 'nav-link is-active' }}
                >
                  Admin
                </Link>
              )}
            </>
          )}
        </div>

        <div className="ml-auto flex items-center gap-2 text-sm font-semibold">
          {hydrated && user ? (
            <>
              <NotificationBell />
              <span className="hidden text-[var(--muted-foreground)] lg:inline">
                {user.name}
              </span>
              <Button
                variant="outline"
                size="lg"
                onClick={() => void onLogout()}
                className="h-9 rounded-lg px-3 text-xs"
              >
                Log out
              </Button>
            </>
          ) : hydrated ? (
            <>
              <Button
                render={<Link to="/login" />}
                variant="outline"
                size="lg"
                className="h-9 rounded-lg px-3 text-xs"
              >
                Log in
              </Button>
              <Button
                render={<Link to="/register" />}
                size="lg"
                className="h-9 rounded-lg px-3 text-xs"
              >
                Sign up
              </Button>
            </>
          ) : null}
          <ThemeToggle />
        </div>
      </nav>
    </header>
  )
}
