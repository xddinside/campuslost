import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError, AUTH_EXPIRED_EVENT, AUTH_KEY, api, clearAuth, readAuth, writeAuth } from './api'
import type { AuthResponse, UserDto } from './types'

interface AuthContextValue {
  user: UserDto | null
  /** True once the stored token has been checked (or no token existed). */
  hydrated: boolean
  sessionExpired: boolean
  login: (email: string, password: string) => Promise<void>
  register: (name: string, email: string, password: string, phone?: string) => Promise<void>
  logout: () => Promise<void>
  dismissSessionExpired: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [user, setUser] = useState<UserDto | null>(null)
  const [hydrated, setHydrated] = useState(false)
  const [sessionExpired, setSessionExpired] = useState(false)

  // An in-flight session validation from a previous identity generation:
  // adopting or clearing a session aborts it, and every outcome is checked
  // against the token still being current.
  const validationAbort = useRef<AbortController | null>(null)
  // Login, registration, logout, and expiry all share one session generation:
  // every authentication call captures the generation it started on, and an
  // older response may never adopt over a newer session.
  const sessionGen = useRef(0)

  const clearIdentity = useCallback(async () => {
    const generation = ++sessionGen.current
    validationAbort.current?.abort()
    clearAuth()
    setUser(null)
    await queryClient.cancelQueries()
    queryClient.clear()
    if (generation === sessionGen.current) setHydrated(true)
  }, [queryClient])

  // Adopts whatever is in shared storage: nothing on mount, or the session
  // another tab just wrote. Private caches are dropped before the new
  // identity may render, and every outcome is checked against the generation
  // and the token still being current.
  const restoreStoredSession = useCallback(async () => {
    const generation = ++sessionGen.current
    validationAbort.current?.abort()
    const controller = new AbortController()
    validationAbort.current = controller

    const stored = readAuth()
    await queryClient.cancelQueries()
    queryClient.clear()
    // Superseded while caches drained — the newer operation owns hydration.
    if (generation !== sessionGen.current || controller.signal.aborted) return

    if (!stored) {
      setUser(null)
      setSessionExpired(false)
      setHydrated(true)
      return
    }

    // A stored profile is a hint, not proof. Validate the token once before
    // private queries are allowed to run. Every outcome — success, a 401,
    // or a network failure — only applies while the stored token is still
    // the current session.
    const isCurrentSession = () =>
      !controller.signal.aborted
      && generation === sessionGen.current
      && readAuth()?.token === stored.token
    try {
      const current = await api<UserDto>('/auth/me', { signal: controller.signal })
      if (!isCurrentSession()) return
      writeAuth({ token: stored.token, user: current })
      setUser(current)
    } catch (error) {
      if (!isCurrentSession()) return
      if (error instanceof ApiError && error.status === 401) {
        clearAuth()
        setUser(null)
      } else {
        // Offline or backend unavailable: keep the local identity so the
        // app can recover, but private requests still fail honestly.
        setUser(stored.user)
      }
    } finally {
      // Hydration is a one-shot: a cleared session still counts as settled.
      if (generation === sessionGen.current) setHydrated(true)
    }
  }, [queryClient])

  useEffect(() => {
    void restoreStoredSession()
    return () => validationAbort.current?.abort()
  }, [restoreStoredSession])

  // Session changes in another tab: re-read storage and run the same guarded
  // restoration, so a cross-tab logout clears this tab's private state and a
  // cross-tab login swaps the displayed identity to the new account.
  useEffect(() => {
    function onStorage(event: StorageEvent) {
      if (event.storageArea !== window.localStorage || event.key !== AUTH_KEY) return
      void restoreStoredSession()
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [restoreStoredSession])

  useEffect(() => {
    function onExpired() {
      void clearIdentity()
      setSessionExpired(true)
    }
    window.addEventListener(AUTH_EXPIRED_EVENT, onExpired)
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, onExpired)
  }, [clearIdentity])

  const adoptSession = useCallback(async (response: AuthResponse, generation: number) => {
    if (generation !== sessionGen.current) return
    validationAbort.current?.abort()
    await queryClient.cancelQueries()
    queryClient.clear()
    // The generation can move while cache teardown was awaited — a newer
    // login that landed meanwhile owns the session now.
    if (generation !== sessionGen.current) return
    writeAuth(response)
    setUser(response.user)
    setSessionExpired(false)
    setHydrated(true)
  }, [queryClient])

  const login = useCallback(async (email: string, password: string) => {
    const generation = ++sessionGen.current
    try {
      const response = await api<AuthResponse>('/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password }),
      })
      await adoptSession(response, generation)
    } finally {
      // A failed or superseded call still settles hydration — unless an even
      // newer session operation is now in charge of it.
      if (generation === sessionGen.current) setHydrated(true)
    }
  }, [adoptSession])

  const register = useCallback(async (name: string, email: string, password: string, phone?: string) => {
    const generation = ++sessionGen.current
    try {
      const response = await api<AuthResponse>('/auth/register', {
        method: 'POST',
        body: JSON.stringify({ name, email, password, phone: phone || null }),
      })
      await adoptSession(response, generation)
    } finally {
      if (generation === sessionGen.current) setHydrated(true)
    }
  }, [adoptSession])

  const logout = useCallback(async () => {
    await clearIdentity()
    setSessionExpired(false)
  }, [clearIdentity])

  const value = useMemo<AuthContextValue>(() => ({
    user,
    hydrated,
    sessionExpired,
    login,
    register,
    logout,
    dismissSessionExpired: () => setSessionExpired(false),
  }), [user, hydrated, sessionExpired, login, register, logout])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
