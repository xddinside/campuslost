export type ItemType = 'LOST' | 'FOUND'
export type ItemStatus = 'OPEN' | 'RESERVED' | 'RESOLVED' | 'CLOSED'
export type ClaimStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'COMPLETED'
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

/** Public report shape. Never contains contact or ownership evidence. */
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
  createdAt: string
  updatedAt: string
  resolvedAt: string | null
  version: number
  photoUrl: string | null
  pendingClaims: number
}

export interface ItemInput {
  type: ItemType
  title: string
  description: string
  category: string
  location: string
  dateOccurred: string
  ownershipQuestion?: string | null
  photoId?: string | null
}

/** PATCH body: omit a field to preserve it, send null to clear a nullable field. */
export interface ItemPatch {
  version: number
  type?: ItemType
  title?: string
  description?: string
  category?: string
  location?: string
  dateOccurred?: string
  ownershipQuestion?: string | null
  photoId?: string | null
}

/** Reporter/admin-only editable view, including private fields. */
export interface ItemEdit extends ItemInput {
  id: number
  status: ItemStatus
  version: number
  photoId: string | null
  photoUrl: string | null
  ownershipQuestion: string | null
}

export interface ClaimInput {
  message: string
  lostReportId?: number
  ownershipAnswer?: string
}

/** Authorized claim view. Never contains contact details or evidence answers. */
export interface Claim {
  id: number
  itemId: number
  itemType: ItemType
  itemTitle: string
  reporterId: number
  claimantId: number
  claimantName: string
  message: string
  status: ClaimStatus
  lostReportId: number | null
  version: number
  createdAt: string
  decidedAt: string | null
  completedAt: string | null
}

export interface ContactParticipant {
  name: string
  email: string
  phone: string | null
}

export interface ContactResponse {
  claimId: number
  reporter: ContactParticipant
  claimant: ContactParticipant
}

export interface PhotoResponse {
  id: string
  url: string
}

export interface AdminStats {
  items: number
  open: number
  reserved: number
  resolved: number
  closed: number
  claims: number
  users: number
}

export interface FieldErrorBody {
  code: string
  message: string
  fieldErrors?: Record<string, string>
}
