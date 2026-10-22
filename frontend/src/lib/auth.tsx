import { createContext, useContext, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { api, clearAuth, readAuth, writeAuth } from './api'
import type { UserDto } from './types'

interface AuthContextValue {
  user: UserDto | null
  hydrated: boolean
  login: (email: string, password: string) => Promise<void>
  register: (name: string, email: string, password: string, phone?: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserDto | null>(null)
  const [hydrated, setHydrated] = useState(false)

  useEffect(() => {
    const stored = readAuth()
    if (stored) setUser(stored.user)
    setHydrated(true)
  }, [])

  async function login(email: string, password: string) {
    const res = await api<{ token: string; user: UserDto }>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
    writeAuth(res)
    setUser(res.user)
  }

  async function register(name: string, email: string, password: string, phone?: string) {
    const res = await api<{ token: string; user: UserDto }>('/auth/register', {
      method: 'POST',
      body: JSON.stringify({ name, email, password, phone }),
    })
    writeAuth(res)
    setUser(res.user)
  }

  function logout() {
    clearAuth()
    setUser(null)
  }

  return (
    <AuthContext.Provider value={{ user, hydrated, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
