import { Link, useNavigate } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import { MagnifyingGlass } from '@phosphor-icons/react'
import { Button } from '#/components/ui/button.tsx'
import { NotificationBell } from '#/features/notifications'
import { useAuth } from '../lib/auth'
import ThemeToggle from './ThemeToggle'

export default function Header() {
  const { user, hydrated, logout } = useAuth()
  const navigate = useNavigate()
  const [scrolled, setScrolled] = useState(false)

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 4)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  async function onLogout() {
    await logout()
    await navigate({ to: '/' })
  }

  return (
    <header className="site-header px-4" data-scrolled={scrolled || undefined}>
      <nav className="page-wrap flex flex-wrap items-center gap-x-3 gap-y-2 py-2.5">
        <Link
          to="/"
          className="inline-flex flex-shrink-0 items-center gap-2 text-[15px] font-semibold tracking-[-0.01em] text-foreground no-underline"
        >
          <span className="brand-mark" aria-hidden="true">
            <MagnifyingGlass size={14} weight="bold" />
          </span>
          CampusLost
        </Link>

        <div className="order-3 flex w-full flex-wrap items-center gap-0.5 sm:order-none sm:w-auto sm:flex-nowrap sm:pl-2">
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
                My activity
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

        <div className="ml-auto flex items-center gap-1.5">
          {hydrated && user ? (
            <>
              <NotificationBell />
              <span className="hidden pl-1 text-[13px] font-medium text-muted-foreground lg:inline">
                {user.name}
              </span>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => void onLogout()}
                className="text-muted-foreground"
              >
                Log out
              </Button>
            </>
          ) : hydrated ? (
            <>
              <Button
                render={<Link to="/login" />}
                variant="ghost"
                size="sm"
              >
                Log in
              </Button>
              <Button render={<Link to="/register" />} size="sm">
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
