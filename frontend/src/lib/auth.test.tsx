// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// React's act() warns without this flag in a test environment.
;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
import { AuthProvider, useAuth } from './auth'
import { ApiError, api, readAuth, writeAuth } from './api'
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
  function Probe() {
    const { user, hydrated } = useAuth()
    return <div data-testid="who">{hydrated ? user?.email ?? 'none' : 'loading'}</div>
  }
  const client = new QueryClient()
  render(
    <QueryClientProvider client={client}>
      <AuthProvider><Probe /></AuthProvider>
    </QueryClientProvider>,
  )
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
})
