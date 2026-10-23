// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// React's act() warns without this flag in a test environment.
;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
import { AuthProvider, useAuth } from './auth'
import { ApiError, api, clearAuth, readAuth, writeAuth } from './api'
import type { AuthResponse, UserDto } from './types'

vi.mock('./api', async (importOriginal) => {
  const real = await importOriginal<typeof import('./api')>()
  return { ...real, api: vi.fn() }
})

const apiMock = vi.mocked(api)

const alice: UserDto = {
  id: 2, name: 'Alice Chen', email: 'alice@demo.dev',
  phone: null, role: 'USER', createdAt: '2026-01-01T00:00:00Z',
}
const bob: UserDto = {
  id: 3, name: 'Bob Martins', email: 'bob@demo.dev',
  phone: null, role: 'USER', createdAt: '2026-01-01T00:00:00Z',
}
const session = (token: string, user: UserDto): AuthResponse => ({ token, user })

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function mountProvider() {
  let ctx: ReturnType<typeof useAuth> | null = null
  function Probe() {
    ctx = useAuth()
    return <div data-testid="who">{ctx.hydrated ? ctx.user?.email ?? 'none' : 'loading'}</div>
  }
  const client = new QueryClient()
  render(
    <QueryClientProvider client={client}>
      <AuthProvider><Probe /></AuthProvider>
    </QueryClientProvider>,
  )
  return {
    client,
    auth: () => {
      if (!ctx) throw new Error('provider not mounted')
      return ctx
    },
  }
}

/** What the browser fires in *this* tab when another tab writes storage. */
function fireStorage(newValue: string | null) {
  window.dispatchEvent(new window.StorageEvent('storage', {
    key: 'laf.auth',
    newValue,
    storageArea: window.localStorage,
  }))
}

describe('AuthProvider session restoration', () => {
  beforeEach(() => {
    localStorage.clear()
    apiMock.mockReset()
  })

  afterEach(cleanup)

  it('a delayed 401 cannot expire a session that superseded it', async () => {
    writeAuth(session('alice-token', alice))
    const me = deferred<UserDto>()
    apiMock.mockImplementation(() => me.promise)
    mountProvider()

    // A newer session lands while Alice's validation is still in flight.
    writeAuth(session('bob-token', bob))
    await act(async () => { me.reject(new ApiError(401, 'UNAUTHORIZED', 'Unauthorized')) })

    expect(readAuth()?.token).toBe('bob-token')
    expect(screen.getByTestId('who').textContent).not.toBe(alice.email)
  })

  it('a delayed network failure cannot restore the superseded identity', async () => {
    writeAuth(session('alice-token', alice))
    const me = deferred<UserDto>()
    apiMock.mockImplementation(() => me.promise)
    mountProvider()

    writeAuth(session('bob-token', bob))
    await act(async () => { me.reject(new TypeError('fetch failed')) })

    expect(readAuth()?.token).toBe('bob-token')
    expect(screen.getByTestId('who').textContent).not.toBe(alice.email)
  })

  it('a 401 still expires the session it belongs to', async () => {
    writeAuth(session('alice-token', alice))
    const me = deferred<UserDto>()
    apiMock.mockImplementation(() => me.promise)
    mountProvider()

    await act(async () => { me.reject(new ApiError(401, 'UNAUTHORIZED', 'Unauthorized')) })

    expect(readAuth()).toBeNull()
    expect(screen.getByTestId('who').textContent).toBe('none')
  })

  it('a successful validation restores the session it belongs to', async () => {
    writeAuth(session('alice-token', alice))
    apiMock.mockImplementation(() => Promise.resolve(alice))
    mountProvider()

    await act(async () => { await Promise.resolve() })

    expect(readAuth()?.token).toBe('alice-token')
    expect(screen.getByTestId('who').textContent).toBe(alice.email)
  })

  it('a delayed login cannot replace a newer registration session', async () => {
    const aliceLogin = deferred<AuthResponse>()
    apiMock.mockImplementation((path: string) =>
      path === '/auth/login' ? aliceLogin.promise : Promise.resolve(session('bob-token', bob)))
    const { auth } = mountProvider()

    let loginDone: Promise<void>
    let registerDone: Promise<void>
    await act(async () => {
      loginDone = auth().login(alice.email, 'demo123')
      registerDone = auth().register('Bob Martins', bob.email, 'demo123')
      await registerDone
    })

    // Alice's delayed response must never overwrite Bob's newer session.
    await act(async () => { aliceLogin.resolve(session('alice-token', alice)) })
    await act(async () => { await loginDone! })

    expect(readAuth()?.token).toBe('bob-token')
    expect(screen.getByTestId('who').textContent).toBe(bob.email)
  })

  it('a delayed login cannot replace a newer login either', async () => {
    const slow = deferred<AuthResponse>()
    apiMock.mockImplementation((path: string) =>
      path === '/auth/login'
        ? slow.promise
        : Promise.reject(new Error('unexpected call ' + path)))
    const { auth } = mountProvider()

    let staleLogin: Promise<void>
    let freshLogin: Promise<void>
    await act(async () => {
      staleLogin = auth().login(alice.email, 'demo123')
    })
    apiMock.mockImplementation(() => Promise.resolve(session('bob-token', bob)))
    await act(async () => {
      freshLogin = auth().login(bob.email, 'demo123')
      await freshLogin
    })
    await act(async () => { slow.resolve(session('alice-token', alice)) })
    await act(async () => { await staleLogin! })

    expect(readAuth()?.token).toBe('bob-token')
    expect(screen.getByTestId('who').textContent).toBe(bob.email)
  })

  it('a logout supersedes a login still in flight', async () => {
    const slow = deferred<AuthResponse>()
    apiMock.mockImplementation(() => slow.promise)
    const { auth } = mountProvider()

    let loginPromise: Promise<void>
    await act(async () => { loginPromise = auth().login(alice.email, 'demo123') })
    await act(async () => { await auth().logout() })
    await act(async () => { slow.resolve(session('alice-token', alice)) })
    await act(async () => { await loginPromise! })

    expect(readAuth()).toBeNull()
    expect(screen.getByTestId('who').textContent).toBe('none')
  })

  it('a login in another tab swaps this tab\'s identity and private cache', async () => {
    writeAuth(session('alice-token', alice))
    apiMock.mockResolvedValue(alice)
    const { client } = mountProvider()
    await act(async () => { await Promise.resolve() })
    expect(screen.getByTestId('who').textContent).toBe(alice.email)
    client.setQueryData(['private'], { secret: 'alice data' })

    // Bob signs in elsewhere: shared storage holds his token now.
    writeAuth(session('bob-token', bob))
    apiMock.mockResolvedValue(bob)
    await act(async () => { fireStorage(JSON.stringify(session('bob-token', bob))) })
    await act(async () => { await Promise.resolve() })

    expect(screen.getByTestId('who').textContent).toBe(bob.email)
    expect(client.getQueryCache().findAll()).toHaveLength(0)
  })

  it('a logout in another tab clears this tab\'s identity and private cache', async () => {
    writeAuth(session('alice-token', alice))
    apiMock.mockResolvedValue(alice)
    const { client } = mountProvider()
    await act(async () => { await Promise.resolve() })
    client.setQueryData(['private'], { secret: 'alice data' })

    // The other tab logged out: the key is gone from shared storage.
    clearAuth()
    await act(async () => { fireStorage(null) })
    await act(async () => { await Promise.resolve() })

    expect(screen.getByTestId('who').textContent).toBe('none')
    expect(readAuth()).toBeNull()
    expect(client.getQueryCache().findAll()).toHaveLength(0)
  })

  it('ignores storage events for other keys', async () => {
    writeAuth(session('alice-token', alice))
    apiMock.mockResolvedValue(alice)
    mountProvider()
    await act(async () => { await Promise.resolve() })
    const calls = apiMock.mock.calls.length

    window.dispatchEvent(new window.StorageEvent('storage', {
      key: 'unrelated',
      newValue: '1',
      storageArea: window.localStorage,
    }))
    await act(async () => { await Promise.resolve() })

    expect(apiMock.mock.calls.length).toBe(calls)
    expect(screen.getByTestId('who').textContent).toBe(alice.email)
  })
})
