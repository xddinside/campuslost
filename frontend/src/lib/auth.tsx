import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError, AUTH_EXPIRED_EVENT, api, clearAuth, readAuth, writeAuth } from './api'
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

  const clearIdentity = useCallback(async () => {
    validationAbort.current?.abort()
    clearAuth()
    setUser(null)
    await queryClient.cancelQueries()
    queryClient.clear()
  }, [queryClient])

  useEffect(() => {
    const controller = new AbortController()
    validationAbort.current = controller
    let cancelled = false
    const stored = readAuth()
    if (!stored) {
      setHydrated(true)
      return
    }

    // A stored profile is a hint, not proof. Validate the token once before
    // private queries are allowed to run. Every outcome — success, a 401,
    // or a network failure — only applies while the stored token is still
    // the current session.
    const isCurrentSession = () => !cancelled && readAuth()?.token === stored.token
    api<UserDto>('/auth/me', { signal: controller.signal })
      .then((current) => {
        if (!isCurrentSession()) return
        writeAuth({ token: stored.token, user: current })
        setUser(current)
      })
      .catch((error: unknown) => {
        if (!isCurrentSession()) return
        if (error instanceof ApiError && error.status === 401) {
          clearAuth()
        } else {
          // Offline or backend unavailable: keep the local identity so the
          // app can recover, but private requests still fail honestly.
          setUser(stored.user)
        }
      })
      .finally(() => {
        // Hydration is a one-shot: a cleared session still counts as settled.
        if (!cancelled) setHydrated(true)
      })

    return () => {
      cancelled = true
      controller.abort()
      if (validationAbort.current === controller) validationAbort.current = null
    }
  }, [])

  useEffect(() => {
    function onExpired() {
      void clearIdentity()
      setSessionExpired(true)
    }
    window.addEventListener(AUTH_EXPIRED_EVENT, onExpired)
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, onExpired)
  }, [clearIdentity])

  const adoptSession = useCallback(async (response: AuthResponse) => {
    validationAbort.current?.abort()
    await queryClient.cancelQueries()
    queryClient.clear()
    writeAuth(response)
    setUser(response.user)
    setSessionExpired(false)
  }, [queryClient])

  const login = useCallback(async (email: string, password: string) => {
    const response = await api<AuthResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
    await adoptSession(response)
  }, [adoptSession])

  const register = useCallback(async (name: string, email: string, password: string, phone?: string) => {
    const response = await api<AuthResponse>('/auth/register', {
      method: 'POST',
      body: JSON.stringify({ name, email, password, phone: phone || null }),
    })
    await adoptSession(response)
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
