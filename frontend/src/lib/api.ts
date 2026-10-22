import type { FieldErrorBody, PhotoResponse, UserDto } from './types'

const AUTH_KEY = 'laf.auth'
export const AUTH_EXPIRED_EVENT = 'campuslost:auth-expired'

export interface StoredAuth {
  token: string
  user: UserDto
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: Record<string, string>

  constructor(status: number, code: string, message: string,
              fieldErrors: Record<string, string> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }

  fieldError(field: string): string | undefined {
    return this.fieldErrors[field]
  }
}

export function readAuth(): StoredAuth | null {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.localStorage.getItem(AUTH_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<StoredAuth>
    if (!parsed.token || !parsed.user || typeof parsed.user.id !== 'number') return null
    return parsed as StoredAuth
  } catch {
    return null
  }
}

export function writeAuth(auth: StoredAuth) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(AUTH_KEY, JSON.stringify(auth))
}

export function clearAuth() {
  if (typeof window === 'undefined') return
  window.localStorage.removeItem(AUTH_KEY)
}

async function parseError(res: Response): Promise<ApiError> {
  let body: Partial<FieldErrorBody> = {}
  try {
    body = (await res.json()) as Partial<FieldErrorBody>
  } catch {
    /* not a JSON error body */
  }
  return new ApiError(
    res.status,
    body.code ?? `HTTP_${res.status}`,
    body.message ?? `Request failed (${res.status})`,
    body.fieldErrors ?? {},
  )
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  const isForm = typeof FormData !== 'undefined' && options.body instanceof FormData
  if (options.body !== undefined && !isForm && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  headers.set('Accept', 'application/json')

  const auth = readAuth()
  if (auth?.token) headers.set('Authorization', `Bearer ${auth.token}`)

  const res = await fetch(`/api${path}`, { ...options, headers })
  if (!res.ok) {
    // Only expire the session when the rejected request belongs to the token
    // that is still current. A delayed 401 from a previous account must not
    // clear the session someone else just opened.
    if (res.status === 401 && auth?.token && typeof window !== 'undefined'
        && readAuth()?.token === auth.token) {
      window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT))
    }
    throw await parseError(res)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

export async function uploadPhoto(file: File): Promise<PhotoResponse> {
  const form = new FormData()
  form.append('file', file)
  return api<PhotoResponse>('/photos', { method: 'POST', body: form })
}

export function qs(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && String(value).length > 0) {
      search.set(key, String(value))
    }
  }
  const query = search.toString()
  return query ? `?${query}` : ''
}
