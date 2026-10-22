// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, AUTH_EXPIRED_EVENT, clearAuth, readAuth, writeAuth } from './api'

const session = (token: string) => ({
  token,
  user: { id: 7, name: 'Alice', email: 'alice@demo.dev', role: 'USER' as const },
})

const unauth = () =>
  Promise.resolve(new Response('{"code":"UNAUTHORIZED","message":"expired"}', { status: 401 }))

describe('api auth expiry', () => {
  beforeEach(() => {
    clearAuth()
    vi.unstubAllGlobals()
  })

  it('dispatches the expiry event when the current token is rejected', async () => {
    writeAuth(session('token-a'))
    const seen: Event[] = []
    window.addEventListener(AUTH_EXPIRED_EVENT, (e) => seen.push(e))
    vi.stubGlobal('fetch', vi.fn(unauth))

    await expect(api('/me/claims')).rejects.toMatchObject({ status: 401 })
    expect(seen).toHaveLength(1)
  })

  it('does not expire a newer session when a stale request is rejected', async () => {
    writeAuth(session('token-a'))
    let resolveFetch: (r: Promise<Response>) => void = () => {}
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>((resolve) => { resolveFetch = resolve })),
    )
    const seen: Event[] = []
    window.addEventListener(AUTH_EXPIRED_EVENT, (e) => seen.push(e))

    // The old request is still in flight when a different account signs in.
    const pending = api('/me/claims')
    writeAuth(session('token-b'))
    resolveFetch(unauth())

    await expect(pending).rejects.toMatchObject({ status: 401 })
    expect(seen).toHaveLength(0)
    expect(readAuth()?.token).toBe('token-b')
  })

  it('aborts the underlying fetch when the caller cancels', async () => {
    writeAuth(session('token-a'))
    const signals: (AbortSignal | null)[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((_: string, init?: RequestInit) => {
        signals.push(init?.signal ?? null)
        return new Promise<Response>((resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
        })
      }),
    )
    const controller = new AbortController()
    const pending = api('/me/claims', { signal: controller.signal })
    controller.abort()

    await expect(pending).rejects.toThrow()
    expect(signals).toHaveLength(1)
    expect(signals[0]).toBe(controller.signal)
  })
})
