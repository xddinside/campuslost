import type { Item } from '../../lib/types'

/** One persisted LOST -> FOUND suggestion returned by the matching API. */
export interface MatchView {
  item: Item
  lostItemId: number
  foundItemId: number
  score: number
  reasons: string[]
}

export interface MatchListResponse {
  itemId: number
  matches: MatchView[]
}
