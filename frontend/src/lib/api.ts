import type { AuthResponse, UserDto } from './types'

export interface StoredAuth {
  token: string
  user: UserDto
}

export function readAuth(): StoredAuth | null {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.localStorage.getItem('laf.auth')
    return raw ? (JSON.parse(raw) as StoredAuth) : null
  } catch {
    return null
  }
}

export function writeAuth(auth: AuthResponse) {
  window.localStorage.setItem('laf.auth', JSON.stringify(auth))
}

export function clearAuth() {
  window.localStorage.removeItem('laf.auth')
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string> | undefined),
  }
  const auth = readAuth()
  if (auth?.token) headers.Authorization = `Bearer ${auth.token}`

  const res = await fetch(`/api${path}`, { ...options, headers })
  if (!res.ok) {
    let message = `Request failed (${res.status})`
    try {
      const body = await res.json()
      if (body?.message) message = body.message
    } catch {
      /* not json */
    }
    throw new Error(message)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

export function qs(params: Record<string, string | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value) search.set(key, value)
  }
  const s = search.toString()
  return s ? `?${s}` : ''
}
