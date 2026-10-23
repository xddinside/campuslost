import { HeadContent, Link, Scripts, createRootRoute } from '@tanstack/react-router'
import { QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import type { ReactNode } from 'react'
import { AuthProvider } from '../lib/auth'
import { makeQueryClient } from '../lib/query'
import Header from '../components/Header'

import appCss from '../styles.css?url'

const THEME_INIT_SCRIPT = `(function(){try{var stored=window.localStorage.getItem('theme');var mode=(stored==='light'||stored==='dark'||stored==='auto')?stored:'auto';var prefersDark=window.matchMedia('(prefers-color-scheme: dark)').matches;var resolved=mode==='auto'?(prefersDark?'dark':'light'):mode;var root=document.documentElement;root.classList.remove('light','dark');root.classList.add(resolved);if(mode==='auto'){root.removeAttribute('data-theme')}else{root.setAttribute('data-theme',mode)}root.style.colorScheme=resolved;}catch(e){}})();`

export const Route = createRootRoute({
  head: () => ({
    meta: [
      { charSet: 'utf-8' },
      { name: 'viewport', content: 'width=device-width, initial-scale=1' },
      { title: 'CampusLost — Lost & Found' },
      {
        name: 'description',
        content: 'Report, search, and recover lost items on campus.',
      },
    ],
    links: [{ rel: 'stylesheet', href: appCss }],
  }),
  shellComponent: RootDocument,
  notFoundComponent: NotFound,
})

function NotFound() {
  return (
    <main className="demo-page demo-center">
      <h1 className="demo-title mb-2">Page not found</h1>
      <p className="mb-6 text-sm text-muted-foreground">
        That page doesn’t exist. It may have been moved or the link is wrong.
      </p>
      <Link to="/" className="nav-link">
        Back to browse
      </Link>
    </main>
  )
}

function RootDocument({ children }: { children: ReactNode }) {
  // One client per SSR request; the browser reuses the same instance.
  const [queryClient] = useState(makeQueryClient)

  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />
        <HeadContent />
      </head>
      <body className="font-sans antialiased [overflow-wrap:anywhere] selection:bg-foreground selection:text-background">
        <QueryClientProvider client={queryClient}>
          <AuthProvider>
            <Header />
            {children}
          </AuthProvider>
        </QueryClientProvider>
        <Scripts />
      </body>
    </html>
  )
}
