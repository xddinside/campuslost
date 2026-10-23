// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true

import { HandoverPanel } from './index'
import { ApiError, api } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { Claim, UserDto } from '../../lib/types'
import type { HandoverStatus } from './types'

vi.mock('../../lib/api', async (importOriginal) => {
  const real = await importOriginal<typeof import('../../lib/api')>()
  return { ...real, api: vi.fn() }
})
vi.mock('../../lib/auth', async (importOriginal) => {
  const real = await importOriginal<typeof import('../../lib/auth')>()
  return { ...real, useAuth: vi.fn() }
})

const apiMock = vi.mocked(api)
const authMock = vi.mocked(useAuth)

const claimant: UserDto = {
  id: 2, name: 'Alice', email: 'alice@demo.dev',
  phone: null, role: 'USER', createdAt: '2026-01-01T00:00:00Z',
}
const reviewer: UserDto = {
  id: 3, name: 'Bob', email: 'bob@demo.dev',
  phone: null, role: 'USER', createdAt: '2026-01-01T00:00:00Z',
}

function claimOf(id: number): Claim {
  return {
    id,
    itemId: 40,
    itemType: 'FOUND',
    itemTitle: 'Found umbrella',
    reporterId: reviewer.id,
    claimantId: claimant.id,
    claimantName: claimant.name,
    message: 'That is mine, the red one.',
    status: 'APPROVED',
    lostReportId: null,
    version: 1,
    createdAt: '2026-01-01T00:00:00Z',
    decidedAt: '2026-01-02T00:00:00Z',
    completedAt: null,
  }
}

function statusOf(id: number, state: HandoverStatus['state']): HandoverStatus {
  return { claimId: id, state, expiresAt: null, attemptsRemaining: null, verifiedAt: null }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function renderPanel(claim: Claim, user: UserDto) {
  authMock.mockReturnValue({
    user, hydrated: true, sessionExpired: false,
    login: vi.fn(), register: vi.fn(), logout: vi.fn(), dismissSessionExpired: vi.fn(),
  })
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <HandoverPanel claim={claim} onChanged={() => {}} />
    </QueryClientProvider>,
  )
}

async function settle() {
  for (let i = 0; i < 5; i++) {
    await act(async () => { await new Promise((r) => setTimeout(r, 0)) })
  }
}

describe('HandoverPanel', () => {
  beforeEach(() => {
    apiMock.mockReset()
    authMock.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  it('shows an issued code only for the claim it was issued on', async () => {
    const claimA = claimOf(5)
    const claimB = claimOf(9)
    apiMock.mockImplementation((path: string) =>
      path.endsWith('/handover')
        ? Promise.resolve(statusOf(Number(path.split('/')[2]), 'NOT_ISSUED'))
        : Promise.resolve({ code: '123456', expiresAt: '2030-01-01T00:00:00Z' }))

    const { rerender } = renderPanel(claimA, claimant)
    await settle()
    fireEvent.click(screen.getByRole('button', { name: /generate pickup code/i }))
    await settle()

    expect(screen.getByText('Pickup code: 1 2 3 4 5 6')).toBeTruthy()

    // Navigating to another claim must not show claim 5's code under it.
    rerender(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <HandoverPanel claim={claimB} onChanged={() => {}} />
      </QueryClientProvider>,
    )
    await settle()

    expect(screen.queryByText(/Pickup code: /)).toBeNull()
    expect(screen.getByRole('button', { name: /generate pickup code/i })).toBeTruthy()
  })

  it('lets the finder verify once polling reports a READY code', async () => {
    vi.useFakeTimers()
    const claim = claimOf(7)
    const states: HandoverStatus[] = [statusOf(7, 'NOT_ISSUED'), statusOf(7, 'READY')]
    let calls = 0
    apiMock.mockImplementation(() => Promise.resolve(states[Math.min(calls++, states.length - 1)]))

    renderPanel(claim, reviewer)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(screen.getByText('Waiting for a code')).toBeTruthy()
    expect(screen.getByRole('button', { name: /verify pickup/i })).toHaveProperty('disabled', true)

    // The status poll runs while the handover is not completed.
    await act(async () => { await vi.advanceTimersByTimeAsync(10_100) })

    expect(screen.getByText('A code is active')).toBeTruthy()
    fireEvent.change(screen.getByLabelText(/six-digit pickup code/i), { target: { value: '123456' } })
    expect(screen.getByRole('button', { name: /verify pickup/i })).toHaveProperty('disabled', false)
  })

  it('keeps the release dialog open on failure and closes on success', async () => {
    const claim = claimOf(8)
    apiMock.mockImplementation((path: string, options?: RequestInit) => {
      if (options?.method === 'POST' && path.endsWith('/release')) {
        return Promise.reject(new ApiError(400, 'INVALID_REASON', 'Give a short reason.'))
      }
      return Promise.resolve(statusOf(8, 'READY'))
    })

    renderPanel(claim, reviewer)
    await settle()

    fireEvent.click(screen.getByRole('button', { name: /release reservation/i }))
    await settle()
    expect(screen.getByText('Release this pickup?')).toBeTruthy()

    // Empty reason is rejected client-side — no request is sent.
    fireEvent.click(screen.getByRole('button', { name: /^release reservation$/i }))
    await settle()
    expect(screen.getByText(/a reason is required/i)).toBeTruthy()
    expect(apiMock.mock.calls.filter(([p]) => p.endsWith('/release'))).toHaveLength(0)
    expect(screen.getByText('Release this pickup?')).toBeTruthy()

    // A server rejection stays inside the open dialog.
    fireEvent.change(screen.getByLabelText(/reason/i), { target: { value: 'Owner never arrived' } })
    fireEvent.click(screen.getByRole('button', { name: /^release reservation$/i }))
    await settle()
    expect(screen.getByText('Give a short reason.')).toBeTruthy()
    expect(screen.getByText('Release this pickup?')).toBeTruthy()

    // A retry that succeeds closes the dialog.
    apiMock.mockImplementation((path: string, options?: RequestInit) => {
      if (options?.method === 'POST' && path.endsWith('/release')) return Promise.resolve(undefined)
      return Promise.resolve(statusOf(8, 'NOT_ISSUED'))
    })
    fireEvent.click(screen.getByRole('button', { name: /^release reservation$/i }))
    await settle()
    expect(screen.queryByText('Release this pickup?')).toBeNull()
  })

  it('aborts the in-flight status request when unmounted', async () => {
    const claim = claimOf(6)
    const status = deferred<HandoverStatus>()
    let signal: AbortSignal | undefined
    apiMock.mockImplementation((_path: string, options?: RequestInit) => {
      signal = options?.signal ?? undefined
      return status.promise
    })

    const { unmount } = renderPanel(claim, reviewer)
    await settle()
    unmount()

    expect(signal?.aborted).toBe(true)
  })
})
