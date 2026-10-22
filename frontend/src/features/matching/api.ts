import { api } from '../../lib/api'
import type { MatchListResponse } from './types'

/** Owner/admin-only suggestions for one report. */
export function fetchMatches(itemId: number, signal?: AbortSignal): Promise<MatchListResponse> {
  return api<MatchListResponse>(`/items/${itemId}/matches`, { signal })
}
