import { QueryClient } from '@tanstack/react-query'

/** One client per SSR request; the browser keeps a single long-lived client. */
export function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        retry: 1,
      },
    },
  })
}

export interface ItemFilters {
  type?: string
  category?: string
  location?: string
  q?: string
  status?: string
}

export const queryKeys = {
  publicItems: (filters: ItemFilters = {}) => ['public', 'items', filters] as const,
  publicItem: (itemId: number | string) => ['public', 'item', String(itemId)] as const,
  account: (userId: number, ...parts: Array<string | number>) =>
    ['account', userId, ...parts] as const,
  accountReports: (userId: number) => ['account', userId, 'reports'] as const,
  accountClaims: (userId: number) => ['account', userId, 'claims'] as const,
  accountItemEdit: (userId: number, itemId: number | string) =>
    ['account', userId, 'item', String(itemId), 'edit'] as const,
  accountItemClaims: (userId: number, itemId: number | string) =>
    ['account', userId, 'item', String(itemId), 'claims'] as const,
  accountContact: (userId: number, claimId: number | string) =>
    ['account', userId, 'contact', String(claimId)] as const,
  adminStats: () => ['admin', 'stats'] as const,
  adminItems: () => ['admin', 'items'] as const,
}

/**
 * Refresh every shared view after a report or claim mutation. Feature code
 * stores its queries under the account/admin prefixes, so they refresh too.
 */
export async function invalidateWorkflow(queryClient: QueryClient) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['public'] }),
    queryClient.invalidateQueries({ queryKey: ['account'] }),
    queryClient.invalidateQueries({ queryKey: ['admin'] }),
  ])
}
