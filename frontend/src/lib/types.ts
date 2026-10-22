export type ItemType = 'LOST' | 'FOUND'
export type ItemStatus = 'OPEN' | 'RESOLVED' | 'CLOSED'
export type ClaimStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type Role = 'USER' | 'ADMIN'

export interface UserDto {
  id: number
  name: string
  email: string
  phone: string | null
  role: Role
  createdAt: string
}

export interface AuthResponse {
  token: string
  user: UserDto
}

export interface Item {
  id: number
  type: ItemType
  title: string
  description: string
  category: string
  location: string
  dateOccurred: string
  status: ItemStatus
  reporterId: number
  reporterName: string
  reporterEmail: string
  reporterPhone: string | null
  createdAt: string
  resolvedAt: string | null
  pendingClaims: number
}

export interface ItemInput {
  type: ItemType
  title: string
  description: string
  category: string
  location: string
  dateOccurred: string
}

export interface Claim {
  id: number
  itemId: number
  itemTitle: string
  claimantId: number
  claimantName: string
  claimantEmail: string
  message: string
  status: ClaimStatus
  createdAt: string
}

export interface AdminStats {
  items: number
  open: number
  resolved: number
  claims: number
  users: number
}
